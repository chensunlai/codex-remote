import { createHash } from "node:crypto";
import { promisify } from "node:util";
import { gunzip } from "node:zlib";
import type { FastifyInstance } from "fastify";
import type WebSocket from "ws";
import { z } from "zod";
import type { GatewayServices } from "./app.js";
import type { AgentDisconnectedEvent, ExtensionViewEvent } from "./agent-registry.js";
import { AppError, errorMessage } from "./errors.js";
import { ChunkedTransport } from "../../transport/websocket.cjs";
import type { GatewaySettingsStore } from "./gateway-settings.js";
import { isRemoteAbsolutePath } from "./remote-path.js";

const LEGACY_BUFFER_BYTES = 16 * 1024 * 1024;
// Resource downloads retain their existing, separate limit (including base64 headroom).
const MAX_RESOURCE_BYTES = 12 * 1024 * 1024 - 4096;
const gunzipAsync = promisify(gunzip);
const serviceParams = z.object({ serviceId: z.string().uuid() });
const viewQuery = z.object({
  clientId: z.string().uuid(),
  rendererId: z.string().uuid(),
  workspace: z.string().min(1).max(4096).refine(isRemoteAbsolutePath),
  after: z.coerce.number().int().nonnegative().safe().default(0),
  epoch: z.string().min(1).max(255).optional(),
});
const viewControl = viewQuery.pick({ clientId: true, workspace: true, epoch: true });
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
  packageSha256: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  encoding: z.literal("gzip").optional(),
});

interface ViewSnapshot {
  viewId: string;
  epoch: string;
  sequence: number;
  version: string;
  packageSha256?: string;
  origin: string;
  html: string;
  frames: ExtensionViewEvent["frame"][];
  pendingHostRequests?: unknown[];
  surfaces?: Array<{ id: string; title: string; kind: "panel"; html: string }>;
}

interface Attachment {
  socket: WebSocket;
  operations: Promise<void>;
  acknowledgements: Promise<void>;
  superseded: boolean;
  epoch?: string;
}

export function registerExtensionHostRoutes(app: FastifyInstance, services: GatewayServices, settings: GatewaySettingsStore): void {
  const attachments = new Map<string, Attachment>();
  const controlling = new Map<string, Promise<unknown>>();
  const negotiating = new Map<string, Set<WebSocket>>();
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
  for (const action of ["close", "restart"] as const) app.post(`${base}/view/${action}`, async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { clientId, workspace, epoch } = parse(viewControl, request.body);
    const ownerId = request.ownerId;
    const viewId = extensionViewId(ownerId, serviceId, clientId, workspace);
    const previous = controlling.get(viewId);
    const operation = (async () => {
      await previous?.catch(() => {});
      // Query the live peer so persisted capabilities cannot accidentally grant
      // this operation to an older Agent reconnecting under the same service ID.
      const description = await services.agents.request<{ capabilities?: string[] }>(ownerId, serviceId, "service.describe", {});
      if (!description.capabilities?.includes("extension-window-management-v1")) {
        throw new AppError(409, "UPGRADE_AGENT_REQUIRED", "请升级 Agent 以管理独立聊天窗口");
      }
      const attachment = attachments.get(viewId);
      if (epoch !== undefined && attachment?.epoch !== undefined && epoch !== attachment.epoch) {
        throw new AppError(409, "EXTENSION_VIEW_CHANGED", "窗口后端已更新，请刷新后重试");
      }
      for (const socket of negotiating.get(viewId) ?? []) socket.close(4002, "Window backend explicitly stopped");
      if (attachment) {
        attachment.superseded = true;
        attachment.socket.close(4002, "Window backend explicitly stopped");
        await Promise.all([attachment.operations, attachment.acknowledgements]);
      }
      const result = await services.agents.request<{ stopped: boolean; epoch?: string }>(
        ownerId, serviceId, `extension.view.${action}`, { viewId, ...(epoch ? { epoch } : {}) }, 120_000,
      );
      if (!result.stopped) throw new AppError(409, "EXTENSION_VIEW_CHANGED", "窗口后端已更新，请刷新后重试");
      return result;
    })();
    controlling.set(viewId, operation);
    try { return { data: await operation }; }
    finally { if (controlling.get(viewId) === operation) controlling.delete(viewId); }
  });
  app.get(`${base}/resource`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const params = parse(z.object({
      version: z.string().min(1).max(255),
      path: z.string().min(1).max(8192),
      packageSha256: z.string().regex(/^[a-f0-9]{64}$/).optional(),
    }), request.query);
    const result = resourceResponse.safeParse(await services.agents.request(
      request.ownerId, serviceId, "extension.resource", { ...params, acceptEncoding: "gzip" },
    ));
    if (!result.success) throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Invalid extension resource response");
    if (result.data.content.length > Math.ceil(MAX_RESOURCE_BYTES / 3) * 4) {
      throw new AppError(413, "EXTENSION_RESOURCE_TOO_LARGE", "Extension resource exceeds transport limit");
    }
    const transported = Buffer.from(result.data.content, "base64");
    if (transported.length > MAX_RESOURCE_BYTES) {
      throw new AppError(413, "EXTENSION_RESOURCE_TOO_LARGE", "Extension resource exceeds transport limit");
    }
    if (transported.toString("base64") !== result.data.content) {
      throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Invalid extension resource encoding");
    }
    let resource = transported;
    if (result.data.encoding === "gzip") {
      try { resource = await gunzipAsync(transported, { maxOutputLength: MAX_RESOURCE_BYTES }); }
      catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ERR_BUFFER_TOO_LARGE") {
          throw new AppError(413, "EXTENSION_RESOURCE_TOO_LARGE", "Extension resource exceeds transport limit");
        }
        throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Invalid compressed extension resource");
      }
    }
    const sha256 = createHash("sha256").update(resource).digest("hex");
    if (result.data.sha256 && sha256 !== result.data.sha256.toLowerCase()) {
      throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Extension resource checksum mismatch");
    }
    if (params.packageSha256 && params.packageSha256 !== result.data.packageSha256) {
      throw new AppError(502, "INVALID_EXTENSION_RESOURCE", "Extension resource package changed; reconnect to reload");
    }
    const compressed = result.data.encoding === "gzip" && acceptsGzip(request.headers["accept-encoding"]);
    const response = compressed ? transported : resource;
    reply.type(result.data.mime).header("content-length", String(response.length))
      .header("x-resource-sha256", sha256).header("vary", "Accept-Encoding");
    if (result.data.packageSha256) reply.header("x-extension-package-sha256", result.data.packageSha256);
    if (compressed) reply.header("content-encoding", "gzip");
    return reply.send(response);
  });

  for (const path of [base, "/api/v4/services/:serviceId/extension"]) app.get(path, { websocket: true }, async (socket, request) => {
    const parsedParams = serviceParams.safeParse(request.params);
    const parsedQuery = viewQuery.safeParse(request.query);
    if (!parsedParams.success || !parsedQuery.success) {
      socket.close(1008, "Invalid extension view parameters");
      return;
    }
    const { serviceId } = parsedParams.data;
    const { clientId, rendererId, workspace, after, epoch } = parsedQuery.data;
    const ownerId = request.ownerId;
    const viewId = extensionViewId(ownerId, serviceId, clientId, workspace);
    if (controlling.has(viewId)) {
      socket.close(4002, "Window backend operation in progress");
      return;
    }
    let transport: ChunkedTransport | undefined;
    let messageLimit = LEGACY_BUFFER_BYTES;
    if (path.startsWith("/api/v4/")) {
      const pending = negotiating.get(viewId) ?? new Set<WebSocket>();
      pending.add(socket);
      negotiating.set(viewId, pending);
      try {
        const agentLimit = services.agents.transportLimit(ownerId, serviceId);
        if (agentLimit === undefined) {
          socket.send(JSON.stringify({ type: "error", code: "UPGRADE_AGENT_REQUIRED", message: "请升级 Agent 以使用分块会话同步" }));
          socket.close(4400, "Upgrade Agent for fragmented transport");
          return;
        }
        messageLimit = Math.min(settings.current.maxMessageBytes, agentLimit);
        transport = new ChunkedTransport(socket, { server: true, maxMessageBytes: messageLimit });
        await transport.ready;
        messageLimit = transport.maxMessageBytes;
      } catch (error) {
        if (socket.readyState === socket.OPEN) socket.close(1012, errorMessage(error).slice(0, 100));
        return;
      } finally {
        pending.delete(socket);
        if (!pending.size && negotiating.get(viewId) === pending) negotiating.delete(viewId);
      }
      if (socket.readyState !== socket.OPEN) return;
    }
    const MAX_BUFFER_BYTES = messageLimit + (transport ? 4 * 1024 * 1024 : 0);

    // Transport negotiation may have yielded while a close/restart began.
    if (controlling.has(viewId)) {
      socket.close(4002, "Window backend operation in progress");
      return;
    }
    const previous = attachments.get(viewId);
    const attachment: Attachment = { socket, operations: Promise.resolve(), acknowledgements: Promise.resolve(), superseded: false };
    attachments.set(viewId, attachment);
    if (previous) previous.superseded = true;
    previous?.socket.close(4001, "View attached by another connection");

    let closed = false;
    let snapshot: ViewSnapshot | undefined;
    let bufferedBytes = 0;
    let queuedBytes = 0;
    let pendingAcknowledgement: number | undefined;
    let acknowledging = false;
    const buffered: ExtensionViewEvent["frame"][] = [];
    const current = () => !closed && socket.readyState === socket.OPEN
      && attachments.get(viewId) === attachment;
    const send = (message: unknown): void => {
      if (!current()) return;
      if (transport) {
        void transport.send(message).catch(() => { if (!transport.closed) socket.terminate(); });
        return;
      }
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
    const acknowledge = (): void => {
      if (!current() || !snapshot || acknowledging || pendingAcknowledgement === undefined) return;
      const sequence = pendingAcknowledgement;
      pendingAcknowledgement = undefined;
      acknowledging = true;
      // ACKs are cumulative bookkeeping. One slow ACK must not hold up page actions.
      attachment.acknowledgements = services.agents.request(ownerId, serviceId, "extension.view.ack", {
        viewId, rendererId, sequence,
      }).then(() => {}, (error: unknown) => { fail(error); }).finally(() => {
        acknowledging = false;
        acknowledge();
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
      await Promise.all([previous?.operations, previous?.acknowledgements]);
      if (!current()) return;
      snapshot = await services.agents.request<ViewSnapshot>(ownerId, serviceId, "extension.view.attach", {
        viewId, rendererId, workspace, after, ...(epoch ? { epoch } : {}),
      }, 120_000);
      if (!current()) return;
      if (snapshot.viewId !== viewId) throw new Error("Extension host returned a different view");
      attachment.epoch = snapshot.epoch;
      send({ type: "ready", ...snapshot });
      for (const frame of buffered.sort((a, b) => a.sequence - b.sequence)) onFrame({ ownerId, serviceId, frame });
      buffered.length = 0;
      bufferedBytes = 0;
      acknowledge();
    })().catch((error: unknown) => {
      fail(error);
      socket.close(1011, "Unable to attach extension view");
    });

    const receive = (raw: string | Buffer, binary = false): void => {
      if (!current()) return;
      if (binary) {
        socket.close(1003, "Extension view accepts JSON text messages only");
        return;
      }
      const text = raw.toString();
      let parsed: z.infer<typeof incomingMessage>;
      try {
        parsed = incomingMessage.parse(JSON.parse(text));
      } catch {
        fail(new AppError(400, "INVALID_EXTENSION_MESSAGE", "Invalid extension view message"));
        socket.close(transport ? 4400 : 1008, "Invalid extension view message");
        return;
      }
      if (parsed.type === "ack") {
        pendingAcknowledgement = Math.max(pendingAcknowledgement ?? 0, parsed.sequence);
        acknowledge();
        return;
      }
      const bytes = Buffer.byteLength(text);
      queuedBytes += bytes;
      if (queuedBytes > MAX_BUFFER_BYTES) {
        socket.close(1013, "Too many queued extension view messages");
        return;
      }
      attachment.operations = attachment.operations.then(async () => {
        queuedBytes -= bytes;
        if (!current() || !snapshot) return;
        try {
          const identity = { viewId, rendererId };
          if (parsed.type === "message") {
            const delivery = await services.agents.request<Record<string, unknown>>(
              ownerId, serviceId, "extension.view.send", {
                ...identity, epoch: parsed.epoch, messageId: parsed.messageId, surfaceId: parsed.surfaceId, payload: parsed.payload,
              },
            );
            send({ ...delivery, type: "delivery", messageId: parsed.messageId });
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
    };
    if (transport) transport.onMessage = text => receive(text);
    else socket.on("message", (raw, binary) => receive(Buffer.from(raw as Buffer), binary));
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

function extensionViewId(ownerId: string, serviceId: string, clientId: string, workspace: string): string {
  return createHash("sha256").update(JSON.stringify([ownerId, serviceId, clientId, workspace])).digest("hex");
}

function acceptsGzip(header: string | undefined): boolean {
  const encodings = (header ?? "").split(",").map((value) => {
    const [name, ...parameters] = value.trim().toLowerCase().split(";");
    const quality = parameters.find((parameter) => parameter.trim().startsWith("q="));
    return { name, quality: quality === undefined ? 1 : Number(quality.trim().slice(2)) };
  });
  const selected = encodings.find((value) => value.name === "gzip") ?? encodings.find((value) => value.name === "*");
  return !!selected && Number.isFinite(selected.quality) && selected.quality > 0 && selected.quality <= 1;
}

function parse<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new AppError(400, "INVALID_REQUEST", result.error.issues.map((issue) => issue.message).join("; "));
  }
  return result.data;
}
