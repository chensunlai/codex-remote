import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { buildGateway, type BuiltGateway } from "./app.js";

const TOKEN = "extension-token-with-enough-entropy";
const OTHER_TOKEN = "other-extension-token-with-enough-entropy";
const SERVICE_ID = "8b173ede-4038-45c7-a5f4-60f0c1e95bd5";
const CLIENT_ID = "bf2c700a-079d-4c62-a5ed-927c6dc255a0";
const RENDERER_ID = "881e332b-92d4-40ac-b674-175711576d08";
const OTHER_RENDERER = "9b47b233-81aa-46b4-a21b-bc84fe65a090";
const WORKSPACE = "/home/fixture/project";
const EPOCH = "host-1";
type Json = Record<string, unknown>;
interface AgentCall { method: string; params: Json }

describe("Official extension host relay", () => {
  let directory: string;
  let gateway: BuiltGateway;
  let baseUrl: string;
  let agent: WebSocket;
  let calls: AgentCall[];
  let sockets: WebSocket[];
  let respond: (method: string, params: Json) => unknown | Promise<unknown>;

  beforeEach(async () => {
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "codex-extension-test-"));
    gateway = await buildGateway({
      host: "127.0.0.1", port: 6767, dataDirectory: directory,
      apiTokens: [TOKEN, OTHER_TOKEN], maxUploadBytes: 1024, maxDownloadBytes: 1024,
    });
    await gateway.app.listen({ host: "127.0.0.1", port: 0 });
    const address = gateway.app.server.address();
    if (!address || typeof address === "string") throw new Error("Missing address");
    baseUrl = `http://127.0.0.1:${address.port}`;
    calls = [];
    sockets = [];
    respond = (method, params) => method === "extension.view.attach" ? snapshot(params) : {};
    agent = await connectAgent();
  });

  afterEach(async () => {
    for (const socket of sockets) socket.terminate();
    await gateway.app.close();
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    delete process.env.LOG_LEVEL;
  });

  it("forwards opaque messages, host responses and visibility without journal entries", async () => {
    const { socket, messages } = await connectView();
    const ready = await messages.next("ready");
    const payload = [null, 3, { unknownFutureField: { data: [0, 255], encoding: "bytes" } }];
    socket.send(JSON.stringify({ type: "message", messageId: "send-1", epoch: EPOCH, payload }));
    expect(await messages.next("delivery")).toEqual({ type: "delivery", messageId: "send-1" });
    expect(calls.find((call) => call.method === "extension.view.send")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, epoch: EPOCH, messageId: "send-1", surfaceId: "main", payload,
    });
    emitFrame(frame(String(ready.viewId), 1, "message", payload));
    expect((await messages.next("message")).payload).toEqual(payload);
    emitFrame(frame(String(ready.viewId), 2, "host-request", { id: "pick-1", method: "showOpenDialog" }));
    expect((await messages.next("host-request")).payload).toEqual({ id: "pick-1", method: "showOpenDialog" });
    socket.send(JSON.stringify({ type: "host-response", id: "pick-1", result: ["file:///tmp/image.png"] }));
    socket.send(JSON.stringify({ type: "visibility", visible: false }));
    socket.send(JSON.stringify({ type: "ack", sequence: 2 }));
    await eventually(() => calls.some((call) => call.method === "extension.view.ack"));
    expect(calls.find((call) => call.method === "extension.view.respond")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, id: "pick-1", result: ["file:///tmp/image.png"],
    });
    expect(calls.find((call) => call.method === "extension.view.visibility")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, visible: false,
    });
    expect(calls.find((call) => call.method === "extension.view.ack")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, sequence: 2,
    });
    const ownerId = await gateway.services.tokens.verify(TOKEN);
    expect(gateway.services.events.since(0, ownerId!).events.some((event) => event.type.includes("extension.view"))).toBe(false);
  });

  it("keeps panel messages isolated by public surface ID and forwards panel closure", async () => {
    const { socket, messages } = await connectView();
    const ready = await messages.next("ready");
    const surfaceId = "panel-settings";
    const surface = { id: surfaceId, title: "Codex settings", kind: "panel" };
    emitFrame({ ...frame(String(ready.viewId), 1, "surface", { action: "open", surface }), surfaceId });
    expect(await messages.next("surface")).toEqual({
      ...frame(String(ready.viewId), 1, "surface", { action: "open", surface }), surfaceId,
    });
    socket.send(JSON.stringify({ type: "message", surfaceId, messageId: "panel-1", epoch: EPOCH, payload: ["object", []] }));
    await messages.next("delivery");
    expect(calls.find((call) => call.method === "extension.view.send")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, surfaceId, messageId: "panel-1", epoch: EPOCH, payload: ["object", []],
    });
    socket.send(JSON.stringify({ type: "close-surface", surfaceId }));
    await eventually(() => calls.some((call) => call.method === "extension.view.surface.close"));
    expect(calls.find((call) => call.method === "extension.view.surface.close")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID, surfaceId,
    });
  });

  it("replays the snapshot once and flushes ordered events arriving during attach", async () => {
    respond = (method, params) => {
      if (method !== "extension.view.attach") return {};
      const first = frame(String(params.viewId), 1, "message", "first");
      emitFrame(first);
      emitFrame(frame(String(params.viewId), 3, "status", { state: "running" }));
      emitFrame(frame(String(params.viewId), 2, "html", "<main>updated</main>"));
      return { ...snapshot(params), sequence: 1, frames: [first] };
    };
    const { messages } = await connectView();
    const ready = await messages.next("ready");
    expect(ready.frames).toEqual([frame(String(ready.viewId), 1, "message", "first")]);
    expect(await messages.next("html")).toEqual(frame(String(ready.viewId), 2, "html", "<main>updated</main>"));
    expect(await messages.next("status")).toEqual(frame(String(ready.viewId), 3, "status", { state: "running" }));
    emitFrame(frame(String(ready.viewId), 3, "message", "duplicate"));
    emitFrame(frame(String(ready.viewId), 4, "message", "current"));
    expect((await messages.next("message")).payload).toBe("current");
  });

  it("detaches without stopping the host and resumes the same workspace view", async () => {
    const first = await connectView();
    const ready = await first.messages.next("ready");
    first.socket.close();
    await eventually(() => calls.some((call) => call.method === "extension.view.detach"));
    expect(calls.find((call) => call.method === "extension.view.detach")?.params).toEqual({
      viewId: ready.viewId, rendererId: RENDERER_ID,
    });
    const second = await connectView({ rendererId: OTHER_RENDERER, after: "27", epoch: EPOCH });
    expect((await second.messages.next("ready")).viewId).toBe(ready.viewId);
    expect(calls.filter((call) => call.method === "extension.view.attach").at(-1)?.params).toEqual({
      viewId: ready.viewId, rendererId: OTHER_RENDERER, workspace: WORKSPACE, after: 27, epoch: EPOCH,
    });
    expect(calls.every((call) => call.method.startsWith("extension.view."))).toBe(true);
    const otherWorkspace = await connectView({ workspace: "/home/fixture/other" });
    expect((await otherWorkspace.messages.next("ready")).viewId).not.toBe(ready.viewId);
  });

  it("serializes replacement after an in-flight send and never detaches the new renderer", async () => {
    let finishSend: ((value: unknown) => void) | undefined;
    respond = (method, params) => {
      if (method === "extension.view.attach") return snapshot(params);
      if (method === "extension.view.send") return new Promise((resolve) => { finishSend = resolve; });
      return {};
    };
    const first = await connectView();
    await first.messages.next("ready");
    first.socket.send(JSON.stringify({ type: "message", messageId: "old", epoch: EPOCH, payload: null }));
    await eventually(() => finishSend !== undefined);
    const closed = onClose(first.socket);
    const second = await connectView({ rendererId: OTHER_RENDERER });
    expect(await closed).toBe(4001);
    expect(calls.filter((call) => call.method === "extension.view.attach")).toHaveLength(1);
    finishSend!({ accepted: true });
    const ready = await second.messages.next("ready");
    expect(calls.filter((call) => call.method === "extension.view.detach")).toEqual([]);
    emitFrame(frame(String(ready.viewId), 1, "message", "new renderer"));
    expect((await second.messages.next("message")).payload).toBe("new renderer");
  });

  it("coalesces cumulative ACKs without blocking ordered official actions behind a slow ACK", async () => {
    let finishAck: ((value: unknown) => void) | undefined;
    let finishFirstSend: ((value: unknown) => void) | undefined;
    respond = (method, params) => {
      if (method === "extension.view.attach") return snapshot(params);
      if (method === "extension.view.ack" && params.sequence === 1) return new Promise((resolve) => { finishAck = resolve; });
      if (method === "extension.view.send" && params.messageId === "first") return new Promise((resolve) => { finishFirstSend = resolve; });
      return {};
    };
    const { socket, messages } = await connectView();
    await messages.next("ready");
    socket.send(JSON.stringify({ type: "ack", sequence: 1 }));
    await eventually(() => finishAck !== undefined);
    for (let sequence = 2; sequence <= 100; sequence++) socket.send(JSON.stringify({ type: "ack", sequence }));
    socket.send(JSON.stringify({ type: "message", epoch: EPOCH, messageId: "first", payload: "first action" }));
    socket.send(JSON.stringify({ type: "message", epoch: EPOCH, messageId: "second", payload: "second action" }));
    await eventually(() => finishFirstSend !== undefined);
    expect(calls.filter((call) => call.method === "extension.view.ack")).toHaveLength(1);
    expect(calls.filter((call) => call.method === "extension.view.send")).toHaveLength(1);
    finishFirstSend!({ accepted: true });
    expect((await messages.next("delivery")).messageId).toBe("first");
    expect((await messages.next("delivery")).messageId).toBe("second");
    finishAck!({});
    await eventually(() => calls.filter((call) => call.method === "extension.view.ack").length === 2);
    expect(calls.filter((call) => call.method === "extension.view.ack").map((call) => call.params.sequence)).toEqual([1, 100]);
  });

  it("fences pending ACKs when another renderer replaces the connection", async () => {
    let finishAck: ((value: unknown) => void) | undefined;
    respond = (method, params) => {
      if (method === "extension.view.attach") return snapshot(params);
      if (method === "extension.view.ack") return new Promise((resolve) => { finishAck = resolve; });
      return {};
    };
    const first = await connectView();
    await first.messages.next("ready");
    first.socket.send(JSON.stringify({ type: "ack", sequence: 1 }));
    await eventually(() => finishAck !== undefined);
    first.socket.send(JSON.stringify({ type: "ack", sequence: 2 }));
    const closed = onClose(first.socket);
    const second = await connectView({ rendererId: OTHER_RENDERER });
    expect(await closed).toBe(4001);
    expect(calls.filter((call) => call.method === "extension.view.attach")).toHaveLength(1);
    finishAck!({});
    await second.messages.next("ready");
    expect(calls.filter((call) => call.method === "extension.view.ack")).toHaveLength(1);
    expect(calls.filter((call) => call.method === "extension.view.detach")).toHaveLength(0);
  });

  it("requires authentication and isolates owner and view events", async () => {
    expect((await fetch(`${baseUrl}/api/v3/services/${SERVICE_ID}/extension/status`)).status).toBe(401);
    const unauthorized = new WebSocket(viewUrl(), { headers: { Authorization: "Bearer invalid-token" } });
    sockets.push(unauthorized);
    expect(await new Promise<number>((resolve) => {
      unauthorized.on("error", () => {});
      unauthorized.once("unexpected-response", (_request, response) => { response.resume(); resolve(response.statusCode!); });
    })).toBe(401);
    const wrongOwner = await connectView({}, OTHER_TOKEN);
    expect(await wrongOwner.messages.next("error")).toEqual(expect.objectContaining({ code: "CONFLICT", retryable: false }));
    expect(calls.some((call) => call.method === "extension.view.attach")).toBe(false);
    const own = await connectView();
    const ready = await own.messages.next("ready");
    const otherAgent = await connectAgent(OTHER_TOKEN);
    emitFrame(frame(String(ready.viewId), 1, "message", "wrong owner"), otherAgent);
    emitFrame(frame("another-view", 1, "message", "wrong view"));
    emitFrame(frame(String(ready.viewId), 1, "message", "correct"));
    expect((await own.messages.next("message")).payload).toBe("correct");
  });

  it("closes disconnected Agent views and permits cursor recovery", async () => {
    const first = await connectView();
    const ready = await first.messages.next("ready");
    const closed = onClose(first.socket);
    agent.close();
    expect(await closed).toBe(1012);
    agent = await connectAgent();
    const second = await connectView({ after: "5", epoch: EPOCH });
    expect((await second.messages.next("ready")).viewId).toBe(ready.viewId);
  });

  it("requests resynchronization when the extension host generation changes", async () => {
    const { socket, messages } = await connectView();
    const ready = await messages.next("ready");
    const closed = onClose(socket);
    emitFrame({ ...frame(String(ready.viewId), 1, "status", {}), epoch: "host-2" });
    expect(await messages.next("resync-required")).toEqual({ type: "resync-required", reason: "host-changed" });
    expect(await closed).toBe(1012);
  });

  it("rejects invalid queries, malformed JSON and binary client messages", async () => {
    const invalidQuery = await connectView({ workspace: "relative/path" });
    expect(await onClose(invalidQuery.socket)).toBe(1008);
    const invalid = await connectView();
    await invalid.messages.next("ready");
    const invalidClosed = onClose(invalid.socket);
    invalid.socket.send("not json");
    expect(await invalid.messages.next("error")).toEqual(expect.objectContaining({ code: "INVALID_EXTENSION_MESSAGE", retryable: false }));
    expect(await invalidClosed).toBe(1008);
    const binary = await connectView();
    await binary.messages.next("ready");
    const binaryClosed = onClose(binary.socket);
    binary.socket.send(Buffer.from([0, 1, 255]));
    expect(await binaryClosed).toBe(1003);
    expect(calls.some((call) => call.method === "extension.view.send")).toBe(false);
  });

  it("routes status and explicit update/application without changing confirmation", async () => {
    respond = (method, params) => ({ method, ...params });
    const status = await api("/status", "GET");
    expect(status.status).toBe(200);
    expect(await status.json()).toEqual({ data: { method: "extension.status" } });
    const update = await api("/update", "POST");
    expect(update.status).toBe(202);
    expect(await update.json()).toEqual({ data: { method: "extension.update" } });
    const apply = await api("/apply", "POST", { confirmed: false });
    expect(apply.status).toBe(202);
    expect(await apply.json()).toEqual({ data: { method: "extension.apply", confirmed: false } });
    expect((await api("/apply", "POST", {})).status).toBe(400);
    expect((await api("/status", "GET", undefined, OTHER_TOKEN)).status).toBe(409);
    const meta = await fetch(`${baseUrl}/api/v1/meta`, { headers: { Authorization: `Bearer ${TOKEN}` } });
    expect((await meta.json() as { data: { capabilities: string[] } }).data.capabilities).toContain("official-extension-host-v1");
  });

  it("returns resource bytes and MIME unchanged with checksum validation and ownership", async () => {
    const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 0xff, 0xc3, 0x28]);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    respond = () => ({ content: bytes.toString("base64"), mime: "image/png", sha256 });
    const response = await api("/resource?version=1.2.3&path=webview%2Fimage.png", "GET");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("image/png");
    expect(response.headers.get("x-resource-sha256")).toBe(sha256);
    expect(Buffer.from(await response.arrayBuffer()).equals(bytes)).toBe(true);
    expect(calls.find((call) => call.method === "extension.resource")?.params).toEqual({
      version: "1.2.3", path: "webview/image.png", acceptEncoding: "gzip",
    });
    expect((await api("/resource?version=1.2.3&path=webview%2Fimage.png", "GET", undefined, OTHER_TOKEN)).status).toBe(409);
    respond = () => ({ content: bytes.toString("base64"), mime: "image/png", sha256: "0".repeat(64) });
    expect((await api("/resource?version=1.2.3&path=webview%2Fimage.png", "GET")).status).toBe(502);
    respond = () => ({ content: "not base64!", mime: "image/png" });
    expect((await api("/resource?version=1.2.3&path=webview%2Fimage.png", "GET")).status).toBe(502);
  });

  it("verifies compressed resources and forwards gzip only to clients accepting it", async () => {
    const bytes = Buffer.from("export const official = 'unchanged';\n".repeat(8192));
    const compressed = gzipSync(bytes);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    const packageSha256 = "a".repeat(64);
    respond = () => ({ content: compressed.toString("base64"), mime: "text/javascript", sha256, packageSha256, encoding: "gzip" });
    const url = `/api/v3/services/${SERVICE_ID}/extension/resource?version=1.2.3&path=webview%2Fapp.js&packageSha256=${packageSha256}`;
    for (const encoding of ["gzip", "br, gzip;q=0.5", "*;q=0.5", "gzip;q=0", "*;q=1, gzip;q=0", "identity"]) {
      const response = await gateway.app.inject({ method: "GET", url, headers: { authorization: `Bearer ${TOKEN}`, "accept-encoding": encoding } });
      expect(response.statusCode).toBe(200);
      const useGzip = ["gzip", "br, gzip;q=0.5", "*;q=0.5"].includes(encoding);
      expect(response.headers["content-encoding"]).toBe(useGzip ? "gzip" : undefined);
      expect(response.rawPayload).toEqual(useGzip ? compressed : bytes);
      expect(response.headers["x-resource-sha256"]).toBe(sha256);
      expect(response.headers["x-extension-package-sha256"]).toBe(packageSha256);
      expect(response.headers["cache-control"]).toBe("no-store");
    }
    expect(calls.find((call) => call.method === "extension.resource")?.params).toEqual({
      version: "1.2.3", path: "webview/app.js", packageSha256, acceptEncoding: "gzip",
    });
    expect((await gateway.app.inject({ method: "GET", url: url.replace(packageSha256, "b".repeat(64)), headers: { authorization: `Bearer ${TOKEN}` } })).statusCode).toBe(502);
    respond = () => ({ content: compressed.toString("base64"), mime: "text/javascript", sha256: "0".repeat(64), packageSha256, encoding: "gzip" });
    expect((await api("/resource?version=1.2.3&path=app.js", "GET")).status).toBe(502);
  });

  it("rejects corrupt gzip and decompression above the raw resource limit", async () => {
    respond = () => ({ content: Buffer.from("not gzip").toString("base64"), mime: "text/javascript", encoding: "gzip" });
    expect((await api("/resource?version=1.2.3&path=app.js", "GET")).status).toBe(502);
    const oversized = gzipSync(Buffer.alloc(12 * 1024 * 1024 - 4095));
    respond = () => ({ content: oversized.toString("base64"), mime: "text/javascript", encoding: "gzip" });
    expect((await api("/resource?version=1.2.3&path=app.js", "GET")).status).toBe(413);
  });

  it("allows a resource up to the base64 transport limit and rejects a larger one", async () => {
    const bytes = Buffer.alloc(12 * 1024 * 1024 - 4096, 0xff);
    respond = () => ({ content: bytes.toString("base64"), mime: "application/octet-stream" });
    const response = await api("/resource?version=1.2.3&path=large.bin", "GET");
    expect(response.status).toBe(200);
    expect(Buffer.from(await response.arrayBuffer()).equals(bytes)).toBe(true);
    respond = () => ({ content: Buffer.alloc(bytes.length + 1).toString("base64"), mime: "application/octet-stream" });
    expect((await api("/resource?version=1.2.3&path=large.bin", "GET")).status).toBe(413);
  });

  async function connectAgent(token = TOKEN): Promise<WebSocket> {
    const url = new URL("/agent/v1/connect", baseUrl.replace("http:", "ws:"));
    url.searchParams.set("serviceId", SERVICE_ID);
    url.searchParams.set("name", "extension-fixture");
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
    sockets.push(socket);
    socket.on("message", (raw) => {
      const request = JSON.parse(raw.toString()) as { id: string; method: string; params: Json };
      if (request.method === "service.describe") {
        socket.send(JSON.stringify({ type: "response", id: request.id, ok: true, result: {
          hostname: "fixture", platform: "linux", arch: "x64", home: "/tmp", agentVersion: "test", capabilities: [],
        } }));
        return;
      }
      calls.push({ method: request.method, params: request.params });
      void Promise.resolve(respond(request.method, request.params)).then((result) => {
        if (socket.readyState === socket.OPEN) socket.send(JSON.stringify({ type: "response", id: request.id, ok: true, result }));
      });
    });
    await onOpen(socket);
    return socket;
  }

  function viewUrl(overrides: Record<string, string> = {}): URL {
    const url = new URL(`/api/v3/services/${SERVICE_ID}/extension`, baseUrl.replace("http:", "ws:"));
    for (const [key, value] of Object.entries({ clientId: CLIENT_ID, rendererId: RENDERER_ID, workspace: WORKSPACE, ...overrides })) {
      url.searchParams.set(key, value);
    }
    return url;
  }

  async function connectView(overrides: Record<string, string> = {}, token = TOKEN) {
    const socket = new WebSocket(viewUrl(overrides), { headers: { Authorization: `Bearer ${token}` } });
    sockets.push(socket);
    const messages = new MessageQueue(socket);
    await onOpen(socket);
    return { socket, messages };
  }

  function emitFrame(value: unknown, source = agent): void {
    source.send(JSON.stringify({ type: "event", event: "extension.view", payload: value }));
  }

  function api(suffix: string, method: string, body?: unknown, token = TOKEN) {
    return fetch(`${baseUrl}/api/v3/services/${SERVICE_ID}/extension${suffix}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "content-type": "application/json" }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  }
});

function snapshot(params: Json): Json {
  return {
    viewId: params.viewId, epoch: EPOCH, sequence: 0, version: "1.2.3",
    origin: "https://extension.codex-remote.invalid", html: "<main>official</main>", frames: [], pendingHostRequests: [],
  };
}

function frame(viewId: string, sequence: number, type: string, payload: unknown): Json {
  return { viewId, epoch: EPOCH, sequence, type, payload };
}

function onOpen(socket: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => { socket.once("open", resolve); socket.once("error", reject); });
}

function onClose(socket: WebSocket): Promise<number> {
  return new Promise((resolve) => socket.once("close", resolve));
}

async function eventually(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error("Condition did not become true");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

class MessageQueue {
  private readonly queued: Json[] = [];
  private readonly waiters: Array<{ type: string; resolve: (value: Json) => void }> = [];

  constructor(socket: WebSocket) {
    socket.on("message", (raw) => {
      const value = JSON.parse(raw.toString()) as Json;
      const index = this.waiters.findIndex((waiter) => waiter.type === value.type);
      if (index < 0) this.queued.push(value);
      else this.waiters.splice(index, 1)[0]!.resolve(value);
    });
  }

  next(type: string): Promise<Json> {
    const index = this.queued.findIndex((value) => value.type === type);
    if (index >= 0) return Promise.resolve(this.queued.splice(index, 1)[0]!);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Missing ${type} message`)), 5_000);
      this.waiters.push({ type, resolve: (value) => { clearTimeout(timer); resolve(value); } });
    });
  }
}
