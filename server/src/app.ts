import { resolve } from "node:path";
import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import multipart from "@fastify/multipart";
import websocket from "@fastify/websocket";
import { z, type ZodType } from "zod";
import type WebSocket from "ws";
import { AgentRegistry, type TerminalEvent } from "./agent-registry.js";
import type { ServerConfig } from "./config.js";
import type { GatewayEvent } from "./domain.js";
import { AppError, ConflictError, errorMessage } from "./errors.js";
import { EventJournal } from "./event-journal.js";
import { ServiceStore } from "./service-store.js";
import { TokenStore } from "./token-store.js";
import { APP_VERSION } from "./version.js";

declare module "fastify" {
  interface FastifyRequest {
    ownerId: string;
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
const threadParams = serviceParams.extend({ threadId: z.string().min(1).max(255) });
const filePath = z.string().min(1).max(4096).refine((value) => value.startsWith("/"), {
  message: "远端路径必须是绝对路径",
});
const promptContext = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("mention"),
    name: z.string().trim().min(1).max(255),
    path: filePath,
  }),
  z.object({ type: z.literal("localImage"), path: filePath }),
  z.object({
    type: z.literal("skill"),
    name: z.string().trim().min(1).max(255),
    path: filePath,
  }),
]);
const reviewTarget = z.discriminatedUnion("type", [
  z.object({ type: z.literal("uncommittedChanges") }),
  z.object({ type: z.literal("baseBranch"), branch: z.string().trim().min(1).max(255) }),
  z.object({
    type: z.literal("commit"),
    sha: z.string().trim().min(1).max(255),
    title: z.string().trim().max(255).nullable().default(null),
  }),
  z.object({
    type: z.literal("custom"),
    instructions: z.string().trim().min(1).max(16 * 1024),
  }),
]);
const approvalPolicy = z.enum(["untrusted", "on-request", "never"]);
const sandboxMode = z.enum(["read-only", "workspace-write", "danger-full-access"]);
const collaborationMode = z.object({
  mode: z.enum(["plan", "default"]),
  settings: z.object({
    model: z.string().trim().min(1).max(255),
    reasoning_effort: z.string().trim().min(1).max(32).nullable(),
    developer_instructions: z.string().max(64 * 1024).nullable(),
  }),
});
const goalStatus = z.enum([
  "active",
  "paused",
  "blocked",
  "usageLimited",
  "budgetLimited",
  "complete",
]);

export async function buildGateway(config: ServerConfig): Promise<BuiltGateway> {
  const events = await EventJournal.open(resolve(config.dataDirectory, "events.jsonl"));
  const services = new ServiceStore(resolve(config.dataDirectory, "services.json"));
  const tokens = await TokenStore.open(resolve(config.dataDirectory, "tokens.json"));
  await tokens.import(config.apiTokens);
  const agents = new AgentRegistry(services, events);
  const builtServices = { agents, events, services, tokens };

  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      redact: ["req.headers.authorization"],
    },
    ...(config.tls ? { https: config.tls } : {}),
  });
  await app.register(websocket, { options: { maxPayload: 2 * 1024 * 1024 } });
  await app.register(multipart, {
    limits: { fileSize: config.maxUploadBytes, files: 1 },
  });

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
    const ownerId = token ? await tokens.verify(token) : undefined;
    if (!ownerId) {
      throw new AppError(401, "UNAUTHORIZED", "访问令牌无效");
    }
    request.ownerId = ownerId;
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

  registerMetaRoutes(app, config);
  registerAgentRoute(app, builtServices);
  registerServiceRoutes(app, builtServices);
  registerCodexRoutes(app, builtServices);
  registerFileRoutes(app, builtServices, config);
  registerTerminalRoute(app, builtServices);
  registerEventRoutes(app, builtServices);

  app.addHook("onClose", async () => agents.close());
  return { app, services: builtServices };
}

function registerMetaRoutes(app: FastifyInstance, config: ServerConfig): void {
  app.get("/healthz", async () => ({ status: "ok", version: APP_VERSION }));
  app.get("/readyz", async () => ({ status: "ready" }));
  app.get("/api/v1/meta", async () => ({
    data: {
      name: "Codex Remote Gateway",
      version: APP_VERSION,
      port: config.port,
      limits: {
        downloadBytes: config.maxDownloadBytes,
        previewBytes: config.maxDownloadBytes,
        uploadBytes: config.maxUploadBytes,
      },
      capabilities: [
        "outbound-agent",
        "multi-token",
        "codex-daemon",
        "sessions",
        "approvals",
        "terminal",
        "files",
        "review",
        "prompt-context",
        "thread-settings",
        "permission-profiles",
        "thread-goals",
      ],
    },
  }));
}

function registerAgentRoute(app: FastifyInstance, services: GatewayServices): void {
  const querySchema = z.object({
    serviceId: z.string().uuid(),
    name: z.string().trim().min(1).max(120),
  });
  app.get("/agent/v1/connect", { websocket: true }, (socket, request) => {
    let query: z.infer<typeof querySchema>;
    try {
      query = parse(querySchema, request.query);
    } catch (error) {
      socket.close(1008, errorMessage(error));
      return;
    }
    void services.agents
      .attach(request.ownerId, query.serviceId, query.name, socket)
      .catch((error: unknown) => socket.close(1011, errorMessage(error).slice(0, 120)));
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
    const models = await codexRpc(services, request, serviceId, "model/list", {
      limit: 5,
      includeHidden: false,
    });
    return { data: { description, models } };
  });
}

function registerCodexRoutes(app: FastifyInstance, services: GatewayServices): void {
  app.get("/api/v1/services/:serviceId/models", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "model/list", {
        limit: 100,
        includeHidden: false,
      }),
    };
  });

  app.get("/api/v1/services/:serviceId/collaboration-modes", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "collaborationMode/list", {}),
    };
  });

  app.get("/api/v1/services/:serviceId/rate-limits", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "account/rateLimits/read", {}),
    };
  });

  app.get("/api/v1/services/:serviceId/file-search", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const query = parse(
      z.object({
        cwd: filePath,
        query: z.string().trim().max(255).default(""),
      }),
      request.query,
    );
    return {
      data: await codexRpc(services, request, serviceId, "fuzzyFileSearch", {
        query: query.query,
        roots: [query.cwd],
        cancellationToken: null,
      }),
    };
  });

  app.get("/api/v1/services/:serviceId/sessions", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const query = parse(
      z.object({
        cursor: z.string().optional(),
        limit: z.coerce.number().int().min(1).max(100).default(100),
        archived: z.stringbool().optional().default(false),
        searchTerm: z.string().trim().min(1).max(255).optional(),
      }),
      request.query,
    );
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "thread/list",
        compact({
          cursor: query.cursor,
          limit: query.limit,
          archived: query.archived,
          searchTerm: query.searchTerm,
          sortKey: "updated_at",
          sortDirection: "desc",
          sourceKinds: ["cli", "vscode", "exec", "appServer", "unknown"],
        }),
      ),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const body = parse(
      z.object({
        cwd: filePath,
        model: z.string().min(1).max(255).optional(),
        effort: z.string().trim().min(1).max(32).optional(),
        approvalPolicy: approvalPolicy.default("on-request"),
        sandbox: sandboxMode.default("workspace-write"),
        permissions: z.string().trim().min(1).max(255).optional(),
        networkAccess: z.boolean().default(true),
      }),
      request.body,
    );
    if (body.permissions && body.sandbox !== "workspace-write") {
      throw new AppError(
        400,
        "VALIDATION_ERROR",
        "permissions 不能与自定义 sandbox 同时设置",
      );
    }
    const result = await codexRpc(
      services,
      request,
      serviceId,
      "thread/start",
      compact({
        cwd: body.cwd,
        model: body.model,
        approvalPolicy: body.approvalPolicy,
        sandbox: body.permissions ? undefined : body.sandbox,
        permissions: body.permissions,
        serviceName: "codex_remote_android",
        config: compact({
          model_reasoning_effort: body.effort,
          sandbox_workspace_write: !body.permissions && body.sandbox === "workspace-write"
            ? { network_access: body.networkAccess }
            : undefined,
        }),
      }),
      90_000,
    );
    return reply.status(201).send({ data: result });
  });

  app.get("/api/v1/services/:serviceId/sessions/:threadId", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await readThreadWithTurns(services, request, serviceId, threadId),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/resume", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "thread/resume", { threadId }),
    };
  });

  app.get("/api/v1/services/:serviceId/permission-profiles", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const query = parse(
      z.object({
        cwd: filePath.optional(),
        cursor: z.string().min(1).max(2048).optional(),
        limit: z.coerce.number().int().min(1).max(100).optional(),
      }),
      request.query,
    );
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "permissionProfile/list",
        compact(query),
      ),
    };
  });

  app.put("/api/v1/services/:serviceId/sessions/:threadId/settings", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const body = parse(
      z.object({
        cwd: filePath.optional(),
        model: z.string().trim().min(1).max(255).optional(),
        effort: z.string().trim().min(1).max(32).optional(),
        approvalPolicy: approvalPolicy.optional(),
        permissions: z.string().trim().min(1).max(255).optional(),
        sandbox: sandboxMode.optional(),
        networkAccess: z.boolean().optional(),
        collaborationMode: collaborationMode.optional(),
      }).refine((value) => Object.values(value).some((item) => item !== undefined), {
        message: "至少提供一项会话设置",
      }),
      request.body,
    );
    if (body.permissions && body.sandbox) {
      throw new AppError(
        400,
        "VALIDATION_ERROR",
        "permissions 不能与 sandbox 同时设置",
      );
    }
    const sandboxPolicy = body.sandbox
      ? toSandboxPolicy(body.sandbox, body.networkAccess ?? true, body.cwd)
      : undefined;
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "thread/settings/update",
        compact({
          threadId,
          cwd: body.cwd,
          model: body.model,
          effort: body.effort,
          approvalPolicy: body.approvalPolicy,
          permissions: body.permissions,
          sandboxPolicy,
          collaborationMode: body.collaborationMode,
        }),
      ),
    };
  });

  app.get("/api/v1/services/:serviceId/sessions/:threadId/goal", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "thread/goal/get", { threadId }),
    };
  });

  app.put("/api/v1/services/:serviceId/sessions/:threadId/goal", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const body = parse(
      z.object({
        objective: z.string().trim().min(1).max(16 * 1024).optional(),
        status: goalStatus.optional(),
        tokenBudget: z.number().int().positive().nullable().optional(),
      }).refine((value) => Object.values(value).some((item) => item !== undefined), {
        message: "至少提供一项 Goal 设置",
      }),
      request.body,
    );
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "thread/goal/set",
        compact({ threadId, ...body }),
      ),
    };
  });

  app.delete("/api/v1/services/:serviceId/sessions/:threadId/goal", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "thread/goal/clear", { threadId }),
    };
  });

  app.put("/api/v1/services/:serviceId/sessions/:threadId/name", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const { name } = parse(z.object({ name: z.string().trim().min(1).max(255) }), request.body);
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "thread/name/set",
        { threadId, name },
      ),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/archive", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "thread/archive", { threadId }),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/unarchive", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(services, request, serviceId, "thread/unarchive", { threadId }),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/compact", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "thread/compact/start",
        { threadId },
        90_000,
      ),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/review", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const body = parse(
      z.object({
        target: reviewTarget.default({ type: "uncommittedChanges" }),
        delivery: z.enum(["inline", "detached"]).optional(),
      }).default({ target: { type: "uncommittedChanges" } }),
      request.body,
    );
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "review/start",
        compact({ threadId, target: body.target, delivery: body.delivery }),
        90_000,
      ),
    };
  });

  app.get("/api/v1/services/:serviceId/skills", async (request) => {
    const { serviceId } = parse(serviceParams, request.params);
    const query = parse(z.object({ cwd: filePath }), request.query);
    return {
      data: await codexRpc(services, request, serviceId, "skills/list", {
        cwds: [query.cwd],
        forceReload: false,
      }),
    };
  });

  app.delete("/api/v1/services/:serviceId/sessions/:threadId", async (request, reply) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    await codexRpc(services, request, serviceId, "thread/delete", { threadId });
    return reply.status(204).send();
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/turns", async (request, reply) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const body = parse(
      z.object({
        text: z.string().min(1).max(1024 * 1024),
        model: z.string().min(1).max(255).optional(),
        effort: z.string().trim().min(1).max(32).optional(),
        context: z.array(promptContext).max(24).default([]),
      }),
      request.body,
    );
    const result = await codexRpc(
      services,
      request,
      serviceId,
      "turn/start",
      compact({
        threadId,
        input: [
          { type: "text", text: body.text, text_elements: [] },
          ...body.context,
        ],
        model: body.model,
        effort: body.effort,
      }),
      90_000,
    );
    return reply.status(202).send({ data: result });
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/steer", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const body = parse(
      z.object({
        turnId: z.string().min(1).max(255),
        text: z.string().min(1).max(1024 * 1024),
        context: z.array(promptContext).max(24).default([]),
      }),
      request.body,
    );
    return {
      data: await codexRpc(services, request, serviceId, "turn/steer", {
        threadId,
        expectedTurnId: body.turnId,
        input: [
          { type: "text", text: body.text, text_elements: [] },
          ...body.context,
        ],
      }),
    };
  });

  app.post("/api/v1/services/:serviceId/sessions/:threadId/interrupt", async (request) => {
    const { serviceId, threadId } = parse(threadParams, request.params);
    const { turnId } = parse(z.object({ turnId: z.string().min(1).max(255) }), request.body);
    return {
      data: await codexRpc(
        services,
        request,
        serviceId,
        "turn/interrupt",
        { threadId, turnId },
      ),
    };
  });

  app.get("/api/v1/requests", async (request) => {
    const query = parse(z.object({ serviceId: z.string().uuid().optional() }), request.query);
    return { data: services.agents.listPending(request.ownerId, query.serviceId) };
  });

  app.post("/api/v1/requests/:requestId/respond", async (request, reply) => {
    const { requestId } = parse(z.object({ requestId: z.string().uuid() }), request.params);
    const { result } = parse(z.object({ result: z.unknown() }), request.body);
    await services.agents.respond(request.ownerId, requestId, result);
    return reply.status(204).send();
  });
}

function registerFileRoutes(
  app: FastifyInstance,
  services: GatewayServices,
  config: ServerConfig,
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
      config.maxDownloadBytes,
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
      config.maxDownloadBytes,
    );
    reply.header("content-type", "text/plain; charset=utf-8");
    reply.header("content-length", String(file.size));
    return reply.send(file.stream);
  });

  app.post("/api/v1/services/:serviceId/fs/upload", async (request, reply) => {
    const { serviceId } = parse(serviceParams, request.params);
    const { path } = parse(querySchema, request.query);
    const part = await request.file();
    if (!part) throw new AppError(400, "FILE_REQUIRED", "需要选择上传文件");
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

    void services.agents
      .request<{ terminalId: string }>(
        request.ownerId,
        serviceId,
        "terminal.open",
        query,
        15_000,
      )
      .then((result) => {
        terminalId = result.terminalId;
        if (socket.readyState === socket.OPEN) {
          socket.send(JSON.stringify({ type: "ready", terminalId }));
        }
      })
      .catch((error: unknown) => socket.close(1011, errorMessage(error).slice(0, 120)));

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

function codexRpc(
  services: GatewayServices,
  request: FastifyRequest,
  serviceId: string,
  method: string,
  params: unknown,
  timeoutMs?: number,
): Promise<unknown> {
  return services.agents.request(
    request.ownerId,
    serviceId,
    "codex.rpc",
    { method, params },
    timeoutMs,
  );
}

function toSandboxPolicy(
  sandbox: z.infer<typeof sandboxMode>,
  networkAccess: boolean,
  cwd?: string,
): Record<string, unknown> {
  switch (sandbox) {
    case "danger-full-access":
      return { type: "dangerFullAccess" };
    case "read-only":
      return { type: "readOnly", networkAccess };
    case "workspace-write":
      return {
        type: "workspaceWrite",
        writableRoots: cwd ? [cwd] : [],
        networkAccess,
        excludeTmpdirEnvVar: false,
        excludeSlashTmp: false,
      };
  }
}

async function readThreadWithTurns(
  services: GatewayServices,
  request: FastifyRequest,
  serviceId: string,
  threadId: string,
): Promise<unknown> {
  try {
    return await codexRpc(services, request, serviceId, "thread/read", {
      threadId,
      includeTurns: true,
    });
  } catch (error) {
    if (codexRpcCode(error) !== -32600) throw error;
    const summary = await codexRpc(services, request, serviceId, "thread/read", { threadId });
    return withEmptyTurns(summary);
  }
}

function codexRpcCode(error: unknown): number | undefined {
  if (error instanceof AppError && error.details && typeof error.details === "object") {
    const rpcCode = (error.details as { rpcCode?: unknown }).rpcCode;
    if (typeof rpcCode === "number") return rpcCode;
  }
  const match = errorMessage(error).match(/Codex RPC (-?\d+):/);
  return match?.[1] ? Number(match[1]) : undefined;
}

function withEmptyTurns(result: unknown): unknown {
  if (!result || typeof result !== "object" || Array.isArray(result)) return result;
  const root = result as Record<string, unknown>;
  if (!root.thread || typeof root.thread !== "object" || Array.isArray(root.thread)) return result;
  return {
    ...root,
    thread: { ...(root.thread as Record<string, unknown>), turns: [] },
  };
}

function parse<T>(schema: ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new AppError(400, "VALIDATION_ERROR", "请求参数无效", result.error.flatten());
  }
  return result.data;
}

function compact(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined));
}
