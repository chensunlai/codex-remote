import { createHash } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type WebSocket from "ws";
import { z } from "zod";
import type { GatewayServices } from "./app.js";
import type { AgentDisconnectedEvent, CodexChannelEvent } from "./agent-registry.js";
import { AppError, errorMessage } from "./errors.js";

const MAX_BUFFER_BYTES = 16 * 1024 * 1024;
const serviceParams = z.object({ serviceId: z.string().uuid() });
const channelQuery = z.object({
  clientId: z.string().uuid(),
  sessionId: z.string().uuid(),
  after: z.coerce.number().int().nonnegative().safe().default(0),
  epoch: z.string().min(1).max(255).optional(),
});
const incomingMessage = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("rpc"),
    messageId: z.string().min(1).max(255),
    epoch: z.string().min(1).max(255),
    message: z.record(z.string(), z.unknown()),
  }),
  z.object({ type: z.literal("ack"), sequence: z.number().int().nonnegative().safe() }),
  z.object({
    type: z.literal("coordination"),
    epoch: z.string().min(1).max(255),
    message: z.discriminatedUnion("type", [
      z.object({ type: z.literal("connect") }),
      z.object({ type: z.literal("message"), message: z.record(z.string(), z.unknown()) }),
    ]),
  }),
]);

interface ChannelSnapshot {
  channelId: string;
  epoch: string;
  sequence: number;
  initializeResult: unknown;
  frames: CodexChannelEvent["frame"][];
  pendingRequests: unknown[];
  runningVersion: string | null;
  resyncRequired: boolean;
}

interface Attachment {
  socket: WebSocket;
  attached: Promise<void>;
  superseded: boolean;
}

export function registerCodexChannelRoutes(
  app: FastifyInstance,
  services: GatewayServices,
): void {
  const attachments = new Map<string, Attachment>();
  const base = "/api/v2/services/:serviceId/codex";

  app.get(`${base}/runtime`, async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return { data: await services.agents.request(request.ownerId, serviceId, "codex.runtime.status") };
  });
  app.post(`${base}/update`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const data = await services.agents.request(request.ownerId, serviceId, "codex.runtime.update");
    return reply.code(202).send({ data });
  });
  app.post(`${base}/restart`, async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const params = parse(z.object({ confirmed: z.boolean() }), request.body);
    const data = await services.agents.request(
      request.ownerId,
      serviceId,
      "codex.runtime.restart",
      params,
    );
    return reply.code(202).send({ data });
  });

  app.get(base, { websocket: true }, (socket, request) => {
    const parsedParams = serviceParams.safeParse(request.params);
    const parsedQuery = channelQuery.safeParse(request.query);
    if (!parsedParams.success || !parsedQuery.success) {
      socket.close(1008, "Invalid channel parameters");
      return;
    }
    const { serviceId } = parsedParams.data;
    const { clientId, sessionId, after, epoch } = parsedQuery.data;
    const ownerId = request.ownerId;
    const channelId = createHash("sha256")
      .update(JSON.stringify([ownerId, serviceId, clientId]))
      .digest("hex");
    const previous = attachments.get(channelId);
    const attachment: Attachment = { socket, attached: Promise.resolve(), superseded: false };
    attachments.set(channelId, attachment);
    if (previous) previous.superseded = true;
    previous?.socket.close(4001, "Channel attached by another connection");

    let closed = false;
    let snapshot: ChannelSnapshot | undefined;
    let bufferedBytes = 0;
    let queuedBytes = 0;
    const buffered: CodexChannelEvent["frame"][] = [];
    const current = () => !closed && socket.readyState === socket.OPEN
      && attachments.get(channelId) === attachment;
    const send = (message: unknown): void => {
      if (!current() || socket.readyState !== socket.OPEN) return;
      const data = JSON.stringify(message);
      if (Buffer.byteLength(data) > MAX_BUFFER_BYTES) {
        socket.close(1009, "Channel message exceeds 16 MiB");
      } else if (socket.bufferedAmount + Buffer.byteLength(data) > MAX_BUFFER_BYTES) {
        socket.close(1013, "Channel consumer is too slow; reconnect to resume");
      } else {
        socket.send(data, (error) => {
          if (error) socket.terminate();
        });
      }
    };
    const fail = (error: unknown, messageId?: string): void => {
      send({
        type: "error",
        ...(messageId === undefined ? {} : { messageId }),
        error: {
          code: error instanceof AppError ? error.code : "CHANNEL_ERROR",
          message: errorMessage(error),
          ...(error instanceof AppError && error.details !== undefined ? { details: error.details } : {}),
        },
      });
    };
    const onFrame = (event: CodexChannelEvent): void => {
      if (!current() || event.ownerId !== ownerId || event.serviceId !== serviceId
        || event.frame.channelId !== channelId) return;
      if (!snapshot) {
        bufferedBytes += Buffer.byteLength(JSON.stringify(event.frame));
        if (bufferedBytes > MAX_BUFFER_BYTES) {
          socket.close(1013, "Channel initialization overflow; reconnect to resume");
          return;
        }
        buffered.push(event.frame);
      } else if (event.frame.epoch === snapshot.epoch && event.frame.sequence > snapshot.sequence) {
        snapshot.sequence = event.frame.sequence;
        send(event.frame);
      } else if (event.frame.epoch !== snapshot.epoch) {
        send({ type: "resync-required", reason: "runtime-changed" });
        socket.close(1012, "Codex runtime changed; reconnect to resume");
      }
    };
    const onDisconnected = (event: AgentDisconnectedEvent): void => {
      if (event.ownerId === ownerId && event.serviceId === serviceId) {
        socket.close(1012, "Agent disconnected; reconnect to resume");
      }
    };
    services.agents.on("codex.channel", onFrame);
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

    attachment.attached = (async () => {
      await previous?.attached;
      if (!current()) return;
      snapshot = await services.agents.request<ChannelSnapshot>(
        ownerId,
        serviceId,
        "codex.channel.attach",
        { channelId, sessionId, afterSequence: after, ...(epoch ? { epoch } : {}) },
        120_000,
      );
      if (!current()) return;
      send({ type: "ready", ...snapshot });
      for (const frame of buffered) onFrame({ ownerId, serviceId, frame });
      buffered.length = 0;
      bufferedBytes = 0;
    })().catch((error: unknown) => {
      fail(error);
      socket.close(1011, "Unable to attach Codex channel");
    });
    let operations = attachment.attached;
    socket.on("message", (raw, binary) => {
      if (!current()) return;
      if (binary) {
        socket.close(1003, "Channel accepts JSON text messages only");
        return;
      }
      const text = raw.toString();
      const bytes = Buffer.byteLength(text);
      queuedBytes += bytes;
      if (queuedBytes > MAX_BUFFER_BYTES) {
        socket.close(1013, "Too many queued channel messages");
        return;
      }
      operations = operations.then(async () => {
        queuedBytes -= bytes;
        if (!current() || !snapshot) return;
        let parsed: z.infer<typeof incomingMessage>;
        try {
          parsed = incomingMessage.parse(JSON.parse(text));
        } catch {
          fail(new AppError(400, "INVALID_CHANNEL_MESSAGE", "Invalid channel message"));
          socket.close(1008, "Invalid channel message");
          return;
        }
        try {
          if (parsed.type === "rpc") {
            const delivery = await services.agents.request<Record<string, unknown>>(
              ownerId, serviceId, "codex.channel.send",
              { channelId, sessionId, epoch: parsed.epoch, messageId: parsed.messageId, message: parsed.message },
            );
            send({ ...delivery, type: "delivery", messageId: parsed.messageId });
          } else if (parsed.type === "coordination") {
            await services.agents.request(ownerId, serviceId, "codex.channel.coordinate", {
              channelId, sessionId, epoch: parsed.epoch, message: parsed.message,
            });
          } else {
            await services.agents.request(ownerId, serviceId, "codex.channel.ack", {
              channelId, sessionId, sequence: parsed.sequence,
            });
          }
        } catch (error) {
          fail(error, parsed.type === "rpc" ? parsed.messageId : undefined);
        }
      });
    });
    socket.on("error", () => socket.terminate());
    socket.on("close", () => {
      closed = true;
      clearInterval(heartbeat);
      services.agents.off("codex.channel", onFrame);
      services.agents.off("disconnected", onDisconnected);
      void attachment.attached.then(async () => {
        if (attachments.get(channelId) === attachment) attachments.delete(channelId);
        if (!attachment.superseded) {
          await services.agents.request(ownerId, serviceId, "codex.channel.detach", {
            channelId, sessionId,
          });
        }
      }).catch(() => {});
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
