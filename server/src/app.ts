import { resolve } from "node:path";
import type { IncomingMessage } from "node:http";
import type WebSocket from "ws";
import Fastify, { type FastifyInstance } from "fastify";
import multipart from "@fastify/multipart";
import websocket from "@fastify/websocket";
import { z, type ZodType } from "zod";
import { AgentRegistry, type TerminalEvent } from "./agent-registry.js";
import type { ServerConfig } from "./config.js";
import type { GatewayEvent } from "./domain.js";
import { AppError, ConflictError, errorMessage } from "./errors.js";
import { EventJournal } from "./event-journal.js";
import { ServiceStore } from "./service-store.js";
import { TokenStore, type TokenRole } from "./token-store.js";
import { GatewaySettingsStore } from "./gateway-settings.js";
import { GatewayRuntimeControl, type GatewayRuntimeController } from "./gateway-runtime-control.js";
import { registerAdminRoutes } from "./admin-routes.js";
import { APP_VERSION } from "./version.js";
import { registerCodexChannelRoutes } from "./codex-channel-routes.js";
import { registerExtensionHostRoutes } from "./extension-host-routes.js";
import { configureBunWebSocketServer } from "../../transport/bun-server.cjs";
import { ChunkedTransport } from "../../transport/websocket.cjs";
import { MAX_WEBSOCKET_BYTES } from "./websocket-limits.js";
import { isRemoteAbsolutePath } from "./remote-path.js";

declare module "fastify" {
  interface FastifyRequest {
    ownerId: string;
    tokenRole: TokenRole;
  }
}

export interface GatewayServices {
  agents: AgentRegistry;
  events: EventJournal;
  services: ServiceStore;
  tokens: TokenStore;
}

export interface BuiltGateway {
  app: FastifyInstance;
  services: GatewayServices;
}

const serviceParams = z.object({ serviceId: z.string().uuid() });
const filePath = z.string().min(1).max(4096).refine(isRemoteAbsolutePath, {
  message: "远端路径必须是绝对路径",
});

export async function buildGateway(config: ServerConfig, options: { runtime?: GatewayRuntimeController } = {}): Promise<BuiltGateway> {
  const events = await EventJournal.open(resolve(config.dataDirectory, "events.jsonl"));
  const services = new ServiceStore(resolve(config.dataDirectory, "services.json"));
  const tokens = await TokenStore.open(resolve(config.dataDirectory, "tokens.json"));
  await tokens.import(config.apiTokens);
  const settings = await GatewaySettingsStore.open(resolve(config.dataDirectory, "gateway-settings.json"), config);
  const runtime = options.runtime ?? new GatewayRuntimeControl(config.dataDirectory);
  const agents = new AgentRegistry(services, events);
  const builtServices = { agents, events, services, tokens };

  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      redact: ["req.headers.authorization"],
    },
    ...(config.tls ? { https: config.tls } : {}),
  });
  // Official extension synchronization sends complete conversation snapshots.
  await app.register(websocket, { options: { maxPayload: MAX_WEBSOCKET_BYTES, perMessageDeflate: {
    threshold: 1024, serverNoContextTakeover: true, clientNoContextTakeover: true,
  } } });
  configureBunWebSocketServer(app.websocketServer);
  await app.register(multipart, {
    limits: { files: 1 },
  });

  const socketOwners = new WeakMap<IncomingMessage, string>();
  const ownedSockets = new Map<string, Set<WebSocket>>();
  const revokeConnections = (ownerId: string) => {
    for (const socket of ownedSockets.get(ownerId) ?? []) socket.terminate();
    ownedSockets.delete(ownerId);
  };
  app.websocketServer.on("connection", (socket, request) => {
    const ownerId = socketOwners.get(request);
    if (!ownerId) { socket.terminate(); return; }
    const sockets = ownedSockets.get(ownerId) ?? new Set<WebSocket>();
    sockets.add(socket);
    ownedSockets.set(ownerId, sockets);
    socket.once("close", () => {
      sockets.delete(socket);
      if (!sockets.size) ownedSockets.delete(ownerId);
    });
  });
  // Local CLI/TUI mutations use the same store. Revalidate long-lived sockets
  // as well as HTTP requests so revoked keys cannot retain an old connection.
  const tokenRefresh = setInterval(() => {
    void tokens.list().then((records) => {
      const active = new Set(records.map((record) => record.id));
      for (const ownerId of ownedSockets.keys()) if (!active.has(ownerId)) revokeConnections(ownerId);
    }).catch((error: unknown) => app.log.error({ err: error }, "Token revalidation failed"));
  }, 10_000);
  tokenRefresh.unref();

  app.addHook("onSend", async (_request, reply, payload) => {
    reply.header("cache-control", "no-store");
    reply.header("x-content-type-options", "nosniff");
    return payload;
  });

  app.addHook("onRequest", async (request) => {
    if (request.url === "/healthz" || request.url === "/readyz") {
      request.ownerId = "";
      return;
    }
    const authorization = request.headers.authorization ?? "";
    const token = authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
    const authentication = token ? await tokens.authenticate(token) : undefined;
    if (!authentication) {
      throw new AppError(401, "UNAUTHORIZED", "访问令牌无效");
    }
    request.ownerId = authentication.id;
    request.tokenRole = authentication.role;
    socketOwners.set(request.raw, authentication.id);
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof AppError) {
      void reply.status(error.statusCode).send({
        error: { code: error.code, message: error.message, details: error.details },
      });
      return;
    }
    request.log.error({ err: error }, "request failed");
    const candidate = error as { statusCode?: unknown; message?: unknown };
    const status = typeof candidate.statusCode === "number" ? candidate.statusCode : 500;
    void reply.status(status).send({
      error: {
        code: status >= 500 ? "INTERNAL_ERROR" : "REQUEST_ERROR",
        message: status >= 500 ? "Gateway 请求失败" : String(candidate.message),
      },
    });
  });

  registerMetaRoutes(app, config, settings);
  registerAdminRoutes(app, builtServices, config, settings, runtime, revokeConnections);
  registerAgentRoute(app, builtServices, settings);
  registerServiceRoutes(app, builtServices);
  registerCodexChannelRoutes(app, builtServices);
  registerExtensionHostRoutes(app, builtServices, settings);
  registerFileRoutes(app, builtServices, settings);
  registerTerminalRoute(app, builtServices);
  registerEventRoutes(app, builtServices);

  app.addHook("onClose", async () => {
    clearInterval(tokenRefresh);
    await agents.close();
  });
  return { app, services: builtServices };
}

function registerMetaRoutes(app: FastifyInstance, config: ServerConfig, settings: GatewaySettingsStore): void {
  app.get("/healthz", async () => ({ status: "ok", version: APP_VERSION }));
  app.get("/readyz", async () => ({ status: "ready" }));
  app.get("/api/v1/meta", async (request) => ({
    data: {
      name: "Codex Remote Gateway",
      version: APP_VERSION,
      port: config.port,
      auth: { role: request.tokenRole, tokenId: request.ownerId },
      limits: {
        downloadBytes: settings.current.maxDownloadBytes,
        previewBytes: settings.current.maxDownloadBytes,
        uploadBytes: settings.current.maxUploadBytes,
      },
      capabilities: [
        "outbound-agent",
        "multi-token",
        "terminal",
        "files",
        "codex-channel-v2",
        "codex-runtime-management",
        "official-extension-host-v1",
        "gateway-admin-v1",
      ],
    },
  }));
}

function registerAgentRoute(app: FastifyInstance, services: GatewayServices, settings: GatewaySettingsStore): void {
  const querySchema = z.object({
    serviceId: z.string().uuid(),
    name: z.string().trim().min(1).max(120),
  });
  for (const path of ["/agent/v1/connect", "/agent/v2/connect"]) app.get(path, { websocket: true }, (socket, request) => {
    let query: z.infer<typeof querySchema>;
    try {
      query = parse(querySchema, request.query);
    } catch (error) {
      socket.close(1008, errorMessage(error));
      return;
    }
    const transport = path === "/agent/v2/connect"
      ? new ChunkedTransport(socket, { server: true, maxMessageBytes: settings.current.maxMessageBytes }) : undefined;
    void (transport?.ready ?? Promise.resolve()).then(() => {
      if (socket.readyState !== socket.OPEN) return;
      return services.agents.attach(request.ownerId, query.serviceId, query.name, socket, transport);
    }).catch((error: unknown) => socket.close(1011, errorMessage(error).slice(0, 120)));
  });
}

function registerServiceRoutes(app: FastifyInstance, services: GatewayServices): void {
  app.get("/api/v1/services", async (request) => ({
    data: await services.agents.list(request.ownerId),
  }));

  app.delete("/api/v1/services/:serviceId", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    if (services.agents.isOnline(request.ownerId, serviceId)) {
      throw new ConflictError("请先停止该服务器上的 Agent，再删除离线服务记录");
    }
    await services.services.delete(request.ownerId, serviceId);
    return reply.status(204).send();
  });

  app.post("/api/v1/services/:serviceId/test", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const description = await services.agents.request(
      request.ownerId,
      serviceId,
      "service.describe",
    );
    return { data: { description } };
  });
}

function registerFileRoutes(
  app: FastifyInstance,
  services: GatewayServices,
  settings: GatewaySettingsStore,
): void {
  const querySchema = z.object({ path: filePath });

  app.get("/api/v1/services/:serviceId/fs/home", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return {
      data: await services.agents.request(request.ownerId, serviceId, "fs.home"),
    };
  });

  app.get("/api/v1/services/:serviceId/fs/list", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.query);
    return {
      data: await services.agents.request(request.ownerId, serviceId, "fs.list", { path }),
    };
  });

  app.get("/api/v1/services/:serviceId/fs/download", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.query);
    const file = await services.agents.download(
      request.ownerId,
      serviceId,
      path,
      settings.current.maxDownloadBytes,
    );
    reply.header("content-type", "application/octet-stream");
    reply.header("content-length", String(file.size));
    reply.header(
      "content-disposition",
      `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`,
    );
    return reply.send(file.stream);
  });

  app.get("/api/v1/services/:serviceId/fs/preview", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.query);
    const file = await services.agents.download(
      request.ownerId,
      serviceId,
      path,
      settings.current.maxDownloadBytes,
    );
    reply.header("content-type", "text/plain; charset=utf-8");
    reply.header("content-length", String(file.size));
    return reply.send(file.stream);
  });

  app.post("/api/v1/services/:serviceId/fs/upload", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.query);
    const part = await request.file({ limits: { fileSize: settings.current.maxUploadBytes, files: 1 } });
    if (!part) throw new AppError(400, "FILE_REQUIRED", "需要选择上传文件");
    // A multipart limit event alone only truncates the stream; abort it so the
    // Agent never receives a successful finish for a partial upload.
    part.file.on("limit", () => part.file.destroy(new AppError(413, "UPLOAD_TOO_LARGE", "上传文件超过 Gateway 限制")));
    if (part.file.truncated) throw new AppError(413, "UPLOAD_TOO_LARGE", "上传文件超过 Gateway 限制");
    const result = await services.agents.upload(request.ownerId, serviceId, path, part.file);
    return reply.status(201).send({ data: result });
  });

  app.post("/api/v1/services/:serviceId/fs/directory", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.body);
    const result = await services.agents.request(
      request.ownerId,
      serviceId,
      "fs.mkdir",
      { path },
    );
    return reply.status(201).send({ data: result });
  });

  app.delete("/api/v1/services/:serviceId/fs", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const query = parse(
      querySchema.extend({ directory: z.stringbool().default(false) }),
      request.query,
    );
    await services.agents.request(request.ownerId, serviceId, "fs.delete", query);
    return reply.status(204).send();
  });
}

function registerTerminalRoute(app: FastifyInstance, services: GatewayServices): void {
  const querySchema = z.object({
    cols: z.coerce.number().int().min(20).max(500).default(100),
    rows: z.coerce.number().int().min(5).max(200).default(30),
    cwd: filePath.optional(),
  });
  app.get("/api/v1/services/:serviceId/terminal", { websocket: true }, (socket, request) => {
    let serviceId: string;
    let query: z.infer<typeof querySchema>;
    try {
      serviceId = parse(serviceParams, request.params).serviceId;
      query = parse(querySchema, request.query);
    } catch (error) {
      socket.close(1008, errorMessage(error));
      return;
    }

    let terminalId = "";
    const listener = (event: TerminalEvent) => {
      if (
        event.ownerId !== request.ownerId
        || event.serviceId !== serviceId
        || event.terminalId !== terminalId
      ) return;
      if (socket.readyState === socket.OPEN) {
        socket.send(
          JSON.stringify(
            event.event === "data"
              ? { type: "data", data: event.data ?? "" }
              : { type: "exit", exitCode: event.exitCode ?? 0 },
          ),
        );
        if (event.event === "exit") socket.close(1000, "Terminal exited");
      }
    };
    services.agents.on("terminal", listener);

    void services.agents.supportsTerminal(request.ownerId, serviceId).then((supported) => {
      if (!supported) throw new AppError(409, "TERMINAL_UNSUPPORTED", "该服务器不支持远程终端");
      return services.agents.request<{ terminalId: string }>(
        request.ownerId,
        serviceId,
        "terminal.open",
        query,
        15_000,
      );
    })
      .then((result) => {
        terminalId = result.terminalId;
        if (socket.readyState === socket.OPEN) {
          socket.send(JSON.stringify({ type: "ready", terminalId }));
        }
      })
      .catch((error: unknown) => socket.close(error instanceof AppError && error.code === "TERMINAL_UNSUPPORTED" ? 1008 : 1011,
        errorMessage(error).slice(0, 120)));

    socket.on("message", (raw, binary) => {
      if (binary || !terminalId) return;
      try {
        const message = JSON.parse(raw.toString()) as Record<string, unknown>;
        if (message.type === "input" && typeof message.data === "string") {
          if (message.data.length > 65_536) throw new Error("Terminal input is too large");
          void services.agents
            .notify(request.ownerId, serviceId, "terminal.write", {
              terminalId,
              data: message.data,
            })
            .catch((error: unknown) => {
              if (socket.readyState === socket.OPEN) {
                socket.send(JSON.stringify({ type: "error", message: errorMessage(error) }));
              }
            });
        } else if (
          message.type === "resize"
          && typeof message.cols === "number"
          && typeof message.rows === "number"
        ) {
          void services.agents
            .notify(request.ownerId, serviceId, "terminal.resize", {
              terminalId,
              cols: Math.max(20, Math.min(500, Math.trunc(message.cols))),
              rows: Math.max(5, Math.min(200, Math.trunc(message.rows))),
            })
            .catch((error: unknown) => {
              if (socket.readyState === socket.OPEN) {
                socket.send(JSON.stringify({ type: "error", message: errorMessage(error) }));
              }
            });
        }
      } catch (error) {
        socket.send(JSON.stringify({ type: "error", message: errorMessage(error) }));
      }
    });

    socket.on("close", () => {
      services.agents.off("terminal", listener);
      if (terminalId) {
        void services.agents
          .notify(request.ownerId, serviceId, "terminal.close", { terminalId })
          .catch(() => {});
      }
    });
  });
}

function registerEventRoutes(app: FastifyInstance, services: GatewayServices): void {
  const querySchema = z.object({
    after: z.coerce.number().int().min(0).default(0),
    serviceId: z.string().uuid().optional(),
  });

  app.get("/api/v1/events/stream", { websocket: true }, (socket, request) => {
    let query: z.infer<typeof querySchema>;
    try {
      query = parse(querySchema, request.query);
    } catch (error) {
      socket.close(1008, errorMessage(error));
      return;
    }
    socket.send(
      JSON.stringify({
        type: "gateway.replay",
        payload: services.events.since(query.after, request.ownerId, query.serviceId),
      }),
    );
    const listener = (event: GatewayEvent) => {
      if (
        event.ownerId !== request.ownerId
        || (query.serviceId && event.serviceId !== query.serviceId)
      ) return;
      const { ownerId: _ownerId, ...visible } = event;
      if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(visible));
    };
    services.events.on("event", listener);
    const ping = setInterval(() => {
      if (socket.readyState === socket.OPEN) socket.ping();
    }, 30_000);
    socket.on("close", () => {
      clearInterval(ping);
      services.events.off("event", listener);
    });
  });
}

function parse<T>(schema: ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new AppError(400, "VALIDATION_ERROR", "请求参数无效", result.error.flatten());
  }
  return result.data;
}
