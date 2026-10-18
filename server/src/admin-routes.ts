import type { FastifyInstance } from "fastify";
import { z, type ZodType } from "zod";
import type { GatewayServices } from "./app.js";
import type { ServerConfig } from "./config.js";
import { AppError, ConflictError } from "./errors.js";
import { gatewaySettingsSchema, type GatewaySettingsStore } from "./gateway-settings.js";
import type { GatewayRuntimeController } from "./gateway-runtime-control.js";
import { APP_VERSION } from "./version.js";

export function registerAdminRoutes(
  app: FastifyInstance,
  services: GatewayServices,
  config: ServerConfig,
  settings: GatewaySettingsStore,
  runtime: GatewayRuntimeController,
  revokeConnections: (ownerId: string) => void,
): void {
  app.register(async (admin) => {
    admin.addHook("preHandler", async (request) => {
      // Resolve the role again from the current store; metadata sent by a client
      // and the tag assigned to a token do not establish administrator rights.
      const record = (await services.tokens.list()).find((token) => token.id === request.ownerId);
      if (!record) throw new AppError(401, "UNAUTHORIZED", "访问令牌已撤销");
      if (record.role !== "admin") throw new AppError(403, "ADMIN_REQUIRED", "需要管理员令牌");
    });

    admin.get("/overview", async () => {
      const [tokens, allServices] = await Promise.all([services.tokens.list(), services.services.listAll()]);
      const limits = settings.current;
      return { data: {
        version: APP_VERSION,
        host: config.host,
        port: config.port,
        dataDirectory: config.dataDirectory,
        limits: { uploadBytes: limits.maxUploadBytes, downloadBytes: limits.maxDownloadBytes, messageBytes: limits.maxMessageBytes },
        tokenCount: tokens.length,
        serviceCount: allServices.length,
        onlineServiceCount: allServices.filter((service) => services.agents.isOnline(service.ownerId, service.id)).length,
      } };
    });

    admin.get("/services", async () => ({ data: (await services.services.listAll()).map((service) => ({
      ...service,
      online: services.agents.isOnline(service.ownerId, service.id),
    })) }));

    admin.delete("/services/:ownerId/:serviceId", async (request, reply) => {
      const { ownerId, serviceId } = parse(z.object({ ownerId: z.string().min(1).max(200), serviceId: z.string().uuid() }), request.params);
      if (services.agents.isOnline(ownerId, serviceId)) {
        throw new ConflictError("请先停止该服务器上的 Agent，再删除离线服务记录");
      }
      await services.services.delete(ownerId, serviceId);
      return reply.status(204).send();
    });

    admin.get("/tokens", async () => ({ data: await services.tokens.list() }));
    admin.post("/tokens", async (request, reply) => {
      const { label, role } = parse(z.object({
        label: z.string().trim().min(1).max(120),
        role: z.enum(["user", "admin"]).default("user"),
      }).strict(), request.body);
      return reply.status(201).send({ data: await services.tokens.create(label, role) });
    });
    admin.delete("/tokens/:id", async (request, reply) => {
      const { id } = parse(z.object({ id: z.string().min(1).max(200) }), request.params);
      await services.tokens.revoke(id, request.ownerId);
      revokeConnections(id);
      return reply.status(204).send();
    });

    admin.get("/settings", async () => ({ data: settings.current }));
    admin.put("/settings", async (request) => ({
      data: await settings.update({
        ...parse(gatewaySettingsSchema, request.body),
        ...((request.body as Record<string, unknown>)?.maxMessageBytes === undefined
          ? { maxMessageBytes: settings.current.maxMessageBytes } : {}),
      }),
    }));

    admin.get("/runtime", async (request) => {
      const { checkUpdates } = parse(z.object({ checkUpdates: z.enum(["true", "false"]).default("false") }), request.query);
      return { data: await runtime.status(checkUpdates === "true") };
    });
    for (const type of ["update", "restart"] as const) {
      admin.post(`/${type}`, async (request, reply) => {
        const { confirmed } = parse(z.object({ confirmed: z.boolean().default(false) }).strict(), request.body ?? {});
        const prepared = await runtime.prepare(type, confirmed);
        // The successful response must leave this process before the supervisor
        // may stop it. A confirmation-only request has a no-op activation.
        let activated = false;
        const activate = () => {
          if (activated) return;
          activated = true;
          void prepared.activate().catch((error: unknown) => request.log.error({ err: error }, `Gateway ${type} failed`));
        };
        reply.raw.once("finish", activate);
        reply.raw.once("close", activate);
        return { data: prepared.status };
      });
    }
  }, { prefix: "/api/v1/admin" });
}

function parse<T>(schema: ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) throw new AppError(400, "INVALID_REQUEST", "请求参数无效", result.error.flatten());
  return result.data;
}
