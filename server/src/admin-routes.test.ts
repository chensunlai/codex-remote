import { mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import WebSocket from "ws";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildGateway, type BuiltGateway } from "./app.js";
import type { ServerConfig } from "./config.js";
import type { GatewayRuntimeController, GatewayRuntimeStatus } from "./gateway-runtime-control.js";
import { AppError } from "./errors.js";

const ADMIN = "fixture-bootstrap-admin";
const SERVICE = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
const status: GatewayRuntimeStatus = {
  currentVersion: "2.0.1", latestVersion: "2.0.2", updateAvailable: true,
  updateSupported: true, restartSupported: true, operation: null,
};

describe("Gateway administration", () => {
  let directory: string;
  let config: ServerConfig;
  let gateway: BuiltGateway;
  let user: { id: string; token: string };
  let runtime: GatewayRuntimeController;
  let activate: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "codex-remote-admin-api-"));
    config = { host: "127.0.0.1", port: 6767, dataDirectory: directory, apiTokens: [ADMIN], maxUploadBytes: 10 * 1024 * 1024, maxDownloadBytes: 10 * 1024 * 1024 };
    activate = vi.fn(async () => {});
    runtime = {
      status: vi.fn(async () => status),
      prepare: vi.fn(async (_type, confirmed) => ({ status: { ...status, ...(!confirmed ? { confirmationRequired: true } : {}) }, activate })),
    };
    gateway = await buildGateway(config, { runtime });
    user = await gateway.services.tokens.create("admin-looking-but-user");
  });

  afterEach(async () => {
    await gateway.app.close();
    await rm(directory, { recursive: true, force: true });
    vi.restoreAllMocks();
    delete process.env.LOG_LEVEL;
  });

  const headers = (token = ADMIN) => ({ authorization: `Bearer ${token}` });

  it("reports trusted roles and rejects user access to every admin route", async () => {
    const admin = (await gateway.app.inject({ url: "/api/v1/meta", headers: headers() })).json().data;
    expect(admin.auth).toEqual({ role: "admin", tokenId: await gateway.services.tokens.verify(ADMIN) });
    expect(admin.capabilities).toContain("gateway-admin-v1");
    const normal = (await gateway.app.inject({ url: "/api/v1/meta", headers: headers(user.token) })).json().data;
    expect(normal.auth).toEqual({ role: "user", tokenId: user.id });
    for (const [method, path, payload] of [
      ["GET", "overview"], ["GET", "services"], ["DELETE", `services/${user.id}/${SERVICE}`],
      ["GET", "tokens"], ["POST", "tokens", { label: "escalate", role: "admin" }], ["DELETE", `tokens/${user.id}`],
      ["GET", "settings"], ["PUT", "settings", { maxUploadBytes: 999, maxDownloadBytes: 999 }],
      ["GET", "runtime?checkUpdates=true"], ["POST", "update", { confirmed: true }], ["POST", "restart", { confirmed: true }],
    ] as const) {
      const response = await gateway.app.inject({ method, url: `/api/v1/admin/${path}`, headers: headers(user.token), ...(payload ? { payload } : {}) });
      expect(response.statusCode, path).toBe(403);
    }
    expect(runtime.prepare).not.toHaveBeenCalled();
    expect(runtime.status).not.toHaveBeenCalled();
    expect((await gateway.app.inject({ url: "/api/v1/admin/tokens" })).statusCode).toBe(401);
  });

  it("creates explicit admin keys, hides secrets from lists, and applies revocation immediately", async () => {
    const created = await gateway.app.inject({ method: "POST", url: "/api/v1/admin/tokens", headers: headers(), payload: { label: "second-admin", role: "admin" } });
    expect(created.statusCode).toBe(201);
    const token = created.json().data;
    expect(token).toMatchObject({ label: "second-admin", role: "admin" });
    expect(token.token).toBeTypeOf("string");
    const listing = await gateway.app.inject({ url: "/api/v1/admin/tokens", headers: headers(token.token) });
    expect(listing.statusCode).toBe(200);
    expect(listing.body).not.toContain(token.token);
    expect(listing.body).not.toContain("tokenHash");
    expect((await gateway.app.inject({ method: "DELETE", url: `/api/v1/admin/tokens/${token.id}`, headers: headers(token.token) })).statusCode).toBe(409);
    expect((await gateway.app.inject({ method: "DELETE", url: `/api/v1/admin/tokens/${token.id}`, headers: headers() })).statusCode).toBe(204);
    expect((await gateway.app.inject({ url: "/api/v1/admin/tokens", headers: headers(token.token) })).statusCode).toBe(401);
  });

  it("rechecks roles after local management changes without restarting", async () => {
    const file = join(directory, "tokens.json");
    const record = JSON.parse(await readFile(file, "utf8"));
    record.tokens.find((token: { role: string }) => token.role === "admin").role = "user";
    const { writeFile } = await import("node:fs/promises");
    await writeFile(file, JSON.stringify(record));
    expect((await gateway.app.inject({ url: "/api/v1/admin/settings", headers: headers() })).statusCode).toBe(403);
    expect((await gateway.app.inject({ url: "/api/v1/meta", headers: headers() })).json().data.auth.role).toBe("user");
  });

  it("updates both enforced transfer limits immediately and persists across restart", async () => {
    const upload = vi.spyOn(gateway.services.agents, "upload").mockImplementation(async (_owner, _service, _path, stream) => {
      let size = 0;
      for await (const chunk of stream) size += (chunk as Buffer).length;
      return { size };
    });
    const download = vi.spyOn(gateway.services.agents, "download").mockImplementation(async (_owner, _service, _path, limit) => {
      if (limit < 5) throw new AppError(413, "DOWNLOAD_TOO_LARGE", "too large");
      return { size: 5, name: "file.txt", stream: Readable.from([Buffer.from("hello")]) } as Awaited<ReturnType<typeof gateway.services.agents.download>>;
    });
    const update = (maxUploadBytes: number, maxDownloadBytes: number) => gateway.app.inject({ method: "PUT", url: "/api/v1/admin/settings", headers: headers(), payload: { maxUploadBytes, maxDownloadBytes } });
    const uploadFile = (bytes: number) => gateway.app.inject({
      method: "POST", url: `/api/v1/services/${SERVICE}/fs/upload?path=%2Ffile.txt`,
      headers: { ...headers(user.token), "content-type": "multipart/form-data; boundary=fixture" },
      payload: Buffer.concat([Buffer.from('--fixture\r\nContent-Disposition: form-data; name="file"; filename="file.txt"\r\nContent-Type: application/octet-stream\r\n\r\n'), Buffer.alloc(bytes, 65), Buffer.from("\r\n--fixture--\r\n")]),
    });
    expect((await update(4, 4)).statusCode).toBe(200);
    expect((await uploadFile(3)).statusCode).toBe(201);
    expect((await uploadFile(4)).statusCode).toBe(201);
    expect((await uploadFile(5)).statusCode).toBe(413);
    expect((await gateway.app.inject({ url: `/api/v1/services/${SERVICE}/fs/download?path=%2Ffile.txt`, headers: headers(user.token) })).statusCode).toBe(413);
    expect((await update(8, 8)).statusCode).toBe(200);
    expect((await uploadFile(5)).statusCode).toBe(201);
    expect((await gateway.app.inject({ url: `/api/v1/services/${SERVICE}/fs/preview?path=%2Ffile.txt`, headers: headers(user.token) })).body).toBe("hello");
    const meta = (await gateway.app.inject({ url: "/api/v1/meta", headers: headers() })).json().data;
    expect(meta.limits).toEqual({ uploadBytes: 8, downloadBytes: 8, previewBytes: 8 });
    // Exercise truncation after the multipart high-water mark, while the remote
    // consumer is already receiving chunks (not just a buffered tiny fixture).
    expect((await update(20_000, 8)).statusCode).toBe(200);
    expect((await uploadFile(40_000)).statusCode).toBe(413);
    expect((await update(8, 8)).statusCode).toBe(200);
    expect(upload).toHaveBeenCalled();
    expect(download).toHaveBeenCalled();
    await gateway.app.close();
    gateway = await buildGateway(config, { runtime });
    expect((await gateway.app.inject({ url: "/api/v1/admin/settings", headers: headers() })).json().data).toEqual({ maxUploadBytes: 8, maxDownloadBytes: 8, maxMessageBytes: 128 * 1024 * 1024 });
  });

  it("persists all message limit choices and preserves the limit for older settings clients", async () => {
    for (const size of [64, 128, 256, 512]) {
      const response = await gateway.app.inject({ method: "PUT", url: "/api/v1/admin/settings", headers: headers(),
        payload: { maxUploadBytes: 100, maxDownloadBytes: 200, maxMessageBytes: size * 1024 * 1024 } });
      expect(response.statusCode).toBe(200);
      expect(response.json().data.maxMessageBytes).toBe(size * 1024 * 1024);
    }
    const legacy = await gateway.app.inject({ method: "PUT", url: "/api/v1/admin/settings", headers: headers(), payload: { maxUploadBytes: 300, maxDownloadBytes: 400 } });
    expect(legacy.json().data.maxMessageBytes).toBe(512 * 1024 * 1024);
    for (const value of [0, 16 * 1024 * 1024, 129 * 1024 * 1024, 1024 * 1024 * 1024]) {
      expect((await gateway.app.inject({ method: "PUT", url: "/api/v1/admin/settings", headers: headers(),
        payload: { maxUploadBytes: 100, maxDownloadBytes: 200, maxMessageBytes: value } })).statusCode).toBe(400);
    }
    await gateway.app.close();
    gateway = await buildGateway(config, { runtime });
    expect((await gateway.app.inject({ url: "/api/v1/admin/settings", headers: headers() })).json().data.maxMessageBytes).toBe(512 * 1024 * 1024);
  });

  it("rejects invalid limits without changing saved settings", async () => {
    for (const invalid of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, "10485760", null]) {
      const response = await gateway.app.inject({ method: "PUT", url: "/api/v1/admin/settings", headers: headers(), payload: { maxUploadBytes: invalid, maxDownloadBytes: 100 } });
      expect(response.statusCode).toBe(400);
    }
    expect((await gateway.app.inject({ url: "/api/v1/admin/settings", headers: headers() })).json().data.maxUploadBytes).toBe(10 * 1024 * 1024);
  });

  it("lists all service owners for administrators while keeping normal services isolated", async () => {
    const adminId = (await gateway.services.tokens.verify(ADMIN))!;
    await gateway.services.services.register(user.id, SERVICE, "user-server");
    await gateway.services.services.register(adminId, SERVICE, "admin-server");
    const isOnline = vi.spyOn(gateway.services.agents, "isOnline").mockImplementation((ownerId) => ownerId === user.id);
    const all = (await gateway.app.inject({ url: "/api/v1/admin/services", headers: headers() })).json().data;
    expect(all).toHaveLength(2);
    expect(all.find((entry: { ownerId: string }) => entry.ownerId === user.id)).toMatchObject({ id: SERVICE, online: true, name: "user-server" });
    const overview = (await gateway.app.inject({ url: "/api/v1/admin/overview", headers: headers() })).json().data;
    expect(overview).toMatchObject({ tokenCount: 2, serviceCount: 2, onlineServiceCount: 1, dataDirectory: directory });
    const normal = (await gateway.app.inject({ url: "/api/v1/services", headers: headers(user.token) })).json().data;
    expect(normal).toHaveLength(1);
    expect(normal[0].name).toBe("user-server");
    expect((await gateway.app.inject({ method: "DELETE", url: `/api/v1/admin/services/${user.id}/${SERVICE}`, headers: headers() })).statusCode).toBe(409);
    isOnline.mockReturnValue(false);
    expect((await gateway.app.inject({ method: "DELETE", url: `/api/v1/admin/services/${user.id}/${SERVICE}`, headers: headers() })).statusCode).toBe(204);
    expect((await gateway.services.services.listAll()).map((service) => service.ownerId)).toEqual([adminId]);
  });

  it("disconnects active event and Agent sockets when their token is revoked", async () => {
    await gateway.app.listen({ host: "127.0.0.1", port: 0 });
    const address = gateway.app.server.address();
    if (!address || typeof address === "string") throw new Error("Missing address");
    const base = `ws://127.0.0.1:${address.port}`;
    const events = new WebSocket(`${base}/api/v1/events/stream`, { headers: headers(user.token) });
    const agent = new WebSocket(`${base}/agent/v1/connect?serviceId=${SERVICE}&name=fixture`, { headers: headers(user.token) });
    agent.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.type === "request") agent.send(JSON.stringify({ type: "response", id: message.id, ok: true, result: { hostname: "fixture", platform: "linux", arch: "x64", agentVersion: "fixture", home: "/tmp", capabilities: [] } }));
    });
    await Promise.all([events, agent].map((socket) => new Promise<void>((resolve, reject) => { socket.once("open", resolve); socket.once("error", reject); })));
    const closed = Promise.all([events, agent].map((socket) => new Promise<void>((resolve) => socket.once("close", () => resolve()))));
    expect((await gateway.app.inject({ method: "DELETE", url: `/api/v1/admin/tokens/${user.id}`, headers: headers() })).statusCode).toBe(204);
    await closed;
    expect(events.readyState).toBe(WebSocket.CLOSED);
    expect(agent.readyState).toBe(WebSocket.CLOSED);
  });

  it("checks release only on request and activates confirmed maintenance after the reply", async () => {
    expect((await gateway.app.inject({ url: "/api/v1/admin/runtime?checkUpdates=true", headers: headers() })).json().data).toEqual(status);
    expect(runtime.status).toHaveBeenCalledWith(true);
    const response = await gateway.app.inject({ method: "POST", url: "/api/v1/admin/restart", headers: headers(), payload: { confirmed: true } });
    expect(response.statusCode).toBe(200);
    expect(runtime.prepare).toHaveBeenCalledWith("restart", true);
    await vi.waitFor(() => expect(activate).toHaveBeenCalledTimes(1));
  });
});
