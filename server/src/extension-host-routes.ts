import { createHash } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type WebSocket from "ws";
import { z } from "zod";
import type { GatewayServices } from "./app.js";
import type { AgentDisconnectedEvent, ExtensionViewEvent } from "./agent-registry.js";
import { AppError, errorMessage } from "./errors.js";

const MAX_BUFFER_BYTES = 16 * 1024 * 1024;
// Leave room for base64 expansion and the Agent response envelope within the 16 MiB transport limit.
const MAX_RESOURCE_BYTES = 12 * 1024 * 1024 - 4096;
const serviceParams = z.object({ serviceId: z.string().uuid() });
const viewQuery = z.object({
  clientId: z.string().uuid(),
  rendererId: z.string().uuid(),
  workspace: z.string().min(1).max(4096).refine((value) => value.startsWith("/") && !value.includes("\0")),
  after: z.coerce.number().int().nonnegative().safe().default(0),
  epoch: z.string().min(1).max(255).optional(),
});
const incomingMessage = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("message"),
    messageId: z.string().min(1).max(255),
    epoch: z.string().min(1).max(255),
    surfaceId: z.string().min(1).max(128).default("main"),
    payload: z.unknown(),
  }),
  z.object({ type: z.literal("ack"), sequence: z.number().int().nonnegative().safe() }),
  z.object({
    type: z.literal("host-response"),
    id: z.string().min(1).max(255),
    result: z.unknown().optional(),
    error: z.unknown().optional(),
  }),
  z.object({ type: z.literal("visibility"), visible: z.boolean() }),
  z.object({ type: z.literal("close-surface"), surfaceId: z.string().min(1).max(128) }),
]);
const resourceResponse = z.object({
  content: z.string(),
  mime: z.string().min(1).max(255).refine((value) => !/[\r\n]/.test(value)),
  sha256: z.string().regex(/^[a-f0-9]{64}$/i).optional(),
});

interface ViewSnapshot {
  viewId: string;
  epoch: string;
  sequence: number;
  version: string;
  origin: string;
  html: string;
  frames: ExtensionViewEvent["frame"][];
  pendingHostRequests?: unknown[];
  surfaces?: Array<{ id: string; title: string; kind: "panel"; html: string }>;
}

interface Attachment {
  socket: WebSocket;
  operations: Promise<void>;
  superseded: boolean;
}

export function registerExtensionHostRoutes(app: FastifyInstance, services: GatewayServices): void {
  const attachments = new Map<string, Attachment>();
  const base = "/api/v3/services/:serviceId/extension";

  app.get(`${base}/status`, async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return { data: await services.agents.request(request.ownerId, serviceId, "extension.status", {}) };
  });
  app.post(`${base}/update`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const data = await services.agents.request(request.ownerId, serviceId, "extension.update", {});
    return reply.code(202).send({ data });
  });
  app.post(`${base}/apply`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const params = parse(z.object({ confirmed: z.boolean() }), request.body);
    const data = await services.agents.request(request.ownerId, serviceId, "extension.apply", params);
    return reply.code(202).send({ data });
  });
  app.get(`${base}/resource`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const params = parse(z.object({
      version: z.string().min(1).max(255),
      path: z.string().min(1).max(8192),
    }), request.query);
    const result = resourceResponse.safeParse(await services.agents.request(
      request.ownerId, serviceId, "extension.resource", params,
    ));
    if (!result.success) throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Invalid extension resource response");
    if (result.data.content.length > Math.ceil(MAX_RESOURCE_BYTES / 3) * 4) {
      throw new AppError(413, "EXTENSION_RESOURCE_TOO_LARGE", "Extension resource exceeds transport limit");
    }
    const resource = Buffer.from(result.data.content, "base64");
    if (resource.length > MAX_RESOURCE_BYTES) {
      throw new AppError(413, "EXTENSION_RESOURCE_TOO_LARGE", "Extension resource exceeds transport limit");
    }
    if (resource.toString("base64") !== result.data.content) {
      throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Invalid extension resource encoding");
    }
    if (result.data.sha256 && createHash("sha256").update(resource).digest("hex") !== result.data.sha256.toLowerCase()) {
      throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Extension resource checksum mismatch");
    }
    reply.type(result.data.mime).header("content-length", String(resource.length));
    if (result.data.sha256) reply.header("x-resource-sha256", result.data.sha256);
    return reply.send(resource);
  });

  app.get(base, { websocket: true }, (socket, request) => {
    const parsedParams = serviceParams.safeParse(request.params);
    const parsedQuery = viewQuery.safeParse(request.query);
    if (!parsedParams.success || !parsedQuery.success) {
      socket.close(1008, "Invalid extension view parameters");
      return;
    }
    const { serviceId } = parsedParams.data;
    const { clientId, rendererId, workspace, after, epoch } = parsedQuery.data;
    const ownerId = request.ownerId;
    const viewId = createHash("sha256")
      .update(JSON.stringify([ownerId, serviceId, clientId, workspace]))
      .digest("hex");
    const previous = attachments.get(viewId);
    const attachment: Attachment = { socket, operations: Promise.resolve(), superseded: false };
    attachments.set(viewId, attachment);
    if (previous) previous.superseded = true;
    previous?.socket.close(4001, "View attached by another connection");

    let closed = false;
    let snapshot: ViewSnapshot | undefined;
    let bufferedBytes = 0;
    let queuedBytes = 0;
    const buffered: ExtensionViewEvent["frame"][] = [];
    const current = () => !closed && socket.readyState === socket.OPEN
      && attachments.get(viewId) === attachment;
    const send = (message: unknown): void => {
      if (!current()) return;
      const data = JSON.stringify(message);
      const bytes = Buffer.byteLength(data);
      if (bytes > MAX_BUFFER_BYTES) {
        socket.close(1009, "Extension view message exceeds 16 MiB");
      } else if (socket.bufferedAmount + bytes > MAX_BUFFER_BYTES) {
        socket.close(1013, "Extension view consumer is too slow; reconnect to resume");
      } else {
        socket.send(data, (error) => { if (error) socket.terminate(); });
      }
    };
    const fail = (error: unknown, messageId?: string): void => {
      send({
        type: "error",
        ...(messageId === undefined ? {} : { messageId }),
        code: error instanceof AppError ? error.code : "EXTENSION_VIEW_ERROR",
        message: errorMessage(error),
        retryable: !(error instanceof AppError) || error.statusCode >= 500,
        ...(error instanceof AppError && error.details !== undefined ? { details: error.details } : {}),
      });
    };
    const onFrame = (event: ExtensionViewEvent): void => {
      if (!current() || event.ownerId !== ownerId || event.serviceId !== serviceId || event.frame.viewId !== viewId) return;
      if (!snapshot) {
        bufferedBytes += Buffer.byteLength(JSON.stringify(event.frame));
        if (bufferedBytes > MAX_BUFFER_BYTES) {
          socket.close(1013, "Extension view initialization overflow; reconnect to resume");
          return;
        }
        buffered.push(event.frame);
      } else if (event.frame.epoch !== snapshot.epoch) {
        send({ type: "resync-required", reason: "host-changed" });
        socket.close(1012, "Extension host changed; reconnect to resume");
      } else if (event.frame.sequence > snapshot.sequence) {
        snapshot.sequence = event.frame.sequence;
        send(event.frame);
      }
    };
    const onDisconnected = (event: AgentDisconnectedEvent): void => {
      if (event.ownerId === ownerId && event.serviceId === serviceId) {
        socket.close(1012, "Agent disconnected; reconnect to resume");
      }
    };
    services.agents.on("extension.view", onFrame);
    services.agents.on("disconnected", onDisconnected);
    let alive = true;
    socket.on("pong", () => { alive = true; });
    const heartbeat = setInterval(() => {
      if (!alive) {
        socket.terminate();
        return;
      }
      alive = false;
      if (socket.readyState === socket.OPEN) socket.ping();
    }, 30_000);
    heartbeat.unref();

    attachment.operations = (async () => {
      await previous?.operations;
      if (!current()) return;
      snapshot = await services.agents.request<ViewSnapshot>(ownerId, serviceId, "extension.view.attach", {
        viewId, rendererId, workspace, after, ...(epoch ? { epoch } : {}),
      }, 120_000);
      if (!current()) return;
      if (snapshot.viewId !== viewId) throw new Error("Extension host returned a different view");
      send({ type: "ready", ...snapshot });
      for (const frame of buffered.sort((a, b) => a.sequence - b.sequence)) onFrame({ ownerId, serviceId, frame });
      buffered.length = 0;
      bufferedBytes = 0;
    })().catch((error: unknown) => {
      fail(error);
      socket.close(1011, "Unable to attach extension view");
    });

    socket.on("message", (raw, binary) => {
      if (!current()) return;
      if (binary) {
        socket.close(1003, "Extension view accepts JSON text messages only");
        return;
      }
      const text = raw.toString();
      const bytes = Buffer.byteLength(text);
      queuedBytes += bytes;
      if (queuedBytes > MAX_BUFFER_BYTES) {
        socket.close(1013, "Too many queued extension view messages");
        return;
      }
      attachment.operations = attachment.operations.then(async () => {
        queuedBytes -= bytes;
        if (!current() || !snapshot) return;
        let parsed: z.infer<typeof incomingMessage>;
        try {
          parsed = incomingMessage.parse(JSON.parse(text));
        } catch {
          fail(new AppError(400, "INVALID_EXTENSION_MESSAGE", "Invalid extension view message"));
          socket.close(1008, "Invalid extension view message");
          return;
        }
        try {
          const identity = { viewId, rendererId };
          if (parsed.type === "message") {
            const delivery = await services.agents.request<Record<string, unknown>>(
              ownerId, serviceId, "extension.view.send", {
                ...identity, epoch: parsed.epoch, messageId: parsed.messageId, surfaceId: parsed.surfaceId, payload: parsed.payload,
              },
            );
            send({ ...delivery, type: "delivery", messageId: parsed.messageId });
          } else if (parsed.type === "ack") {
            await services.agents.request(ownerId, serviceId, "extension.view.ack", {
              ...identity, sequence: parsed.sequence,
            });
          } else if (parsed.type === "visibility") {
            await services.agents.request(ownerId, serviceId, "extension.view.visibility", {
              ...identity, visible: parsed.visible,
            });
          } else if (parsed.type === "close-surface") {
            await services.agents.request(ownerId, serviceId, "extension.view.surface.close", {
              ...identity, surfaceId: parsed.surfaceId,
            });
          } else {
            await services.agents.request(ownerId, serviceId, "extension.view.respond", {
              ...identity, id: parsed.id,
              ...("result" in parsed ? { result: parsed.result } : {}),
              ...("error" in parsed ? { error: parsed.error } : {}),
            });
          }
        } catch (error) {
          fail(error, parsed.type === "message" ? parsed.messageId : undefined);
        }
      });
    });
    socket.on("error", () => socket.terminate());
    socket.on("close", () => {
      closed = true;
      clearInterval(heartbeat);
      services.agents.off("extension.view", onFrame);
      services.agents.off("disconnected", onDisconnected);
      attachment.operations = attachment.operations.then(async () => {
        if (!attachment.superseded) {
          await services.agents.request(ownerId, serviceId, "extension.view.detach", { viewId, rendererId });
        }
      }).catch(() => {}).then(() => {
        if (attachments.get(viewId) === attachment) attachments.delete(viewId);
      });
    });
  });
}

function parse<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new AppError(400, "INVALID_REQUEST", result.error.issues.map((issue) => issue.message).join("; "));
  }
  return result.data;
}
