import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { buildGateway, type BuiltGateway } from "./app.js";

const TOKEN = "channel-token-with-enough-entropy";
const OTHER_TOKEN = "other-channel-token-with-enough-entropy";
const SERVICE_ID = "8b173ede-4038-45c7-a5f4-60f0c1e95bd5";
const CLIENT_ID = "bf2c700a-079d-4c62-a5ed-927c6dc255a0";
const SESSION_ID = "881e332b-92d4-40ac-b674-175711576d08";
const OTHER_SESSION = "9b47b233-81aa-46b4-a21b-bc84fe65a090";
const EPOCH = "runtime-1";
type Json = Record<string, unknown>;
interface AgentCall { method: string; params: Json }

describe("Codex channel relay", () => {
  let directory: string;
  let gateway: BuiltGateway;
  let baseUrl: string;
  let agent: WebSocket;
  let calls: AgentCall[];
  let sockets: WebSocket[];
  let respond: (method: string, params: Json) => unknown | Promise<unknown>;

  beforeEach(async () => {
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "codex-channel-test-"));
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
    respond = (method, params) => method === "codex.channel.attach" ? snapshot(params) : {};
    agent = await connectAgent();
  });

  afterEach(async () => {
    for (const socket of sockets) socket.terminate();
    agent.terminate();
    await gateway.app.close();
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    delete process.env.LOG_LEVEL;
  });

  it("preserves RPC requests, errors and unknown fields without the old event journal", async () => {
    const { socket, messages } = await connectChannel();
    const ready = await messages.next("ready");
    const message = {
      jsonrpc: "2.0", id: 41, method: "future/method", params: null,
      futureExtension: { nested: [1, "two"] },
    };
    socket.send(JSON.stringify({ type: "rpc", messageId: "send-1", epoch: EPOCH, message }));
    expect(await messages.next("delivery")).toEqual({ type: "delivery", messageId: "send-1" });
    expect(calls.find((call) => call.method === "codex.channel.send")?.params).toEqual({
      channelId: ready.channelId, sessionId: SESSION_ID, epoch: EPOCH, messageId: "send-1", message,
    });
    const response = {
      id: 41, error: { code: -32601, message: "Unknown method", data: { retry: false } },
      extra: true,
    };
    emitFrame(frame(String(ready.channelId), 1, response));
    expect((await messages.next("rpc")).message).toEqual(response);
    const approval = { id: "approve-1", method: "item/commandExecution/requestApproval", params: { x: 1 } };
    emitFrame(frame(String(ready.channelId), 2, approval));
    expect((await messages.next("rpc")).message).toEqual(approval);
    socket.send(JSON.stringify({ type: "ack", sequence: 2 }));
    await eventually(() => calls.some((call) => call.method === "codex.channel.ack"));
    expect(calls.find((call) => call.method === "codex.channel.ack")?.params).toEqual({
      channelId: ready.channelId, sessionId: SESSION_ID, sequence: 2,
    });
    const ownerId = await gateway.services.tokens.verify(TOKEN);
    expect(gateway.services.events.since(0, ownerId!).events.some((event) =>
      event.type.includes("codex.channel"))).toBe(false);
  });

  it("replays the attach snapshot once and flushes events arriving during attach", async () => {
    respond = (method, params) => {
      if (method !== "codex.channel.attach") return {};
      const first = frame(String(params.channelId), 1, { method: "first" });
      emitFrame(first);
      emitFrame(frame(String(params.channelId), 2, { method: "second" }));
      return { ...snapshot(params), sequence: 1, frames: [first] };
    };
    const { messages } = await connectChannel({ after: "0", epoch: EPOCH });
    const ready = await messages.next("ready");
    expect(ready.frames).toEqual([frame(String(ready.channelId), 1, { method: "first" })]);
    expect(await messages.next("rpc")).toEqual(frame(String(ready.channelId), 2, { method: "second" }));
    emitFrame(frame(String(ready.channelId), 2, { method: "duplicate" }));
    emitFrame(frame(String(ready.channelId), 3, { method: "third" }));
    expect(await messages.next("rpc")).toEqual(frame(String(ready.channelId), 3, { method: "third" }));
  });

  it("detaches on page exit and passes recovery cursors when reconnecting", async () => {
    const first = await connectChannel();
    const ready = await first.messages.next("ready");
    first.socket.close();
    await eventually(() => calls.some((call) => call.method === "codex.channel.detach"));
    expect(calls.find((call) => call.method === "codex.channel.detach")?.params).toEqual({
      channelId: ready.channelId, sessionId: SESSION_ID,
    });
    const second = await connectChannel({ sessionId: OTHER_SESSION, after: "27", epoch: EPOCH });
    expect((await second.messages.next("ready")).channelId).toBe(ready.channelId);
    expect(calls.filter((call) => call.method === "codex.channel.attach").at(-1)?.params).toEqual({
      channelId: ready.channelId, sessionId: OTHER_SESSION, afterSequence: 27, epoch: EPOCH,
    });
    expect(calls.some((call) => ["codex.release", "codex.takeover", "codex.rpc"].includes(call.method))).toBe(false);
  });

  it("replaces an existing socket without detaching the new socket's session", async () => {
    const first = await connectChannel();
    await first.messages.next("ready");
    const closed = onClose(first.socket);
    const second = await connectChannel();
    const ready = await second.messages.next("ready");
    expect(await closed).toBe(4001);
    expect(calls.filter((call) => call.method === "codex.channel.detach")).toEqual([]);
    emitFrame(frame(String(ready.channelId), 1, { method: "current" }));
    expect((await second.messages.next("rpc")).message).toEqual({ method: "current" });
  });

  it("serializes replacement against an unfinished attach", async () => {
    let finishFirst: ((value: unknown) => void) | undefined;
    respond = (method, params) => {
      if (method !== "codex.channel.attach") return {};
      if (params.sessionId === SESSION_ID) return new Promise((resolve) => { finishFirst = resolve; });
      return snapshot(params);
    };
    const first = await connectChannel();
    await eventually(() => finishFirst !== undefined);
    const second = await connectChannel({ sessionId: OTHER_SESSION });
    const oldParams = calls.find((call) => call.method === "codex.channel.attach")!.params;
    finishFirst!(snapshot(oldParams));
    await second.messages.next("ready");
    expect(calls.filter((call) => call.method === "codex.channel.attach").map((call) => call.params.sessionId))
      .toEqual([SESSION_ID, OTHER_SESSION]);
    expect(calls.filter((call) => call.method === "codex.channel.detach")).toEqual([]);
    first.socket.terminate();
  });

  it("closes the app connection when Agent disconnects and permits recovery after reconnect", async () => {
    const first = await connectChannel();
    const ready = await first.messages.next("ready");
    const closed = onClose(first.socket);
    agent.close();
    expect(await closed).toBe(1012);
    agent = await connectAgent();
    const second = await connectChannel({ after: "5", epoch: EPOCH });
    expect((await second.messages.next("ready")).channelId).toBe(ready.channelId);
  });

  it("isolates owners and channels and requires bearer authentication", async () => {
    const unauthorized = await fetch(`${baseUrl}/api/v2/services/${SERVICE_ID}/codex/runtime`);
    expect(unauthorized.status).toBe(401);
    const wrongOwner = await connectChannel({}, OTHER_TOKEN);
    expect((await wrongOwner.messages.next("error")).error).toEqual(expect.objectContaining({ code: "CONFLICT" }));
    expect(calls.some((call) => call.method === "codex.channel.attach")).toBe(false);
    const own = await connectChannel();
    const ready = await own.messages.next("ready");
    emitFrame(frame("another-channel", 1, { method: "wrong" }));
    emitFrame(frame(String(ready.channelId), 1, { method: "correct" }));
    expect((await own.messages.next("rpc")).message).toEqual({ method: "correct" });
  });

  it("accepts a raw RPC larger than the previous 2 MiB limit", async () => {
    const { socket, messages } = await connectChannel();
    await messages.next("ready");
    const message = { id: "large", method: "large/request", params: { text: "a".repeat(2 * 1024 * 1024) } };
    socket.send(JSON.stringify({ type: "rpc", messageId: "large", epoch: EPOCH, message }));
    await messages.next("delivery");
    expect(calls.find((call) => call.method === "codex.channel.send")?.params.message).toEqual(message);
  });

  it("rejects malformed messages and requests recovery on runtime generation changes", async () => {
    const invalid = await connectChannel();
    await invalid.messages.next("ready");
    const invalidClosed = onClose(invalid.socket);
    invalid.socket.send("not json");
    invalid.socket.send(JSON.stringify({
      type: "rpc", messageId: "after-invalid-frame", epoch: EPOCH,
      message: { id: 1, method: "turn/start", params: {} },
    }));
    expect((await invalid.messages.next("error")).error).toEqual(expect.objectContaining({ code: "INVALID_CHANNEL_MESSAGE" }));
    expect(await invalidClosed).toBe(1008);
    expect(calls.some((call) => call.method === "codex.channel.send")).toBe(false);
    const valid = await connectChannel();
    const ready = await valid.messages.next("ready");
    const validClosed = onClose(valid.socket);
    emitFrame({ ...frame(String(ready.channelId), 1, {}), epoch: "runtime-2" });
    expect(await valid.messages.next("resync-required")).toEqual({ type: "resync-required", reason: "runtime-changed" });
    expect(await validClosed).toBe(1012);
  });

  it("routes runtime management with ownership and restart confirmation intact", async () => {
    respond = (method, params) => ({ method, ...params });
    const status = await api("", "GET");
    expect(status.status).toBe(200);
    expect(await status.json()).toEqual({ data: { method: "codex.runtime.status" } });
    const update = await api("/update", "POST");
    expect(update.status).toBe(202);
    expect(await update.json()).toEqual({ data: { method: "codex.runtime.update" } });
    const restart = await api("/restart", "POST", { confirmed: false });
    expect(restart.status).toBe(202);
    expect(await restart.json()).toEqual({ data: { method: "codex.runtime.restart", confirmed: false } });
    expect((await api("/restart", "POST", {})).status).toBe(400);
    expect((await api("", "GET", undefined, OTHER_TOKEN)).status).toBe(409);
  });

  async function connectAgent(): Promise<WebSocket> {
    const url = new URL("/agent/v1/connect", baseUrl.replace("http:", "ws:"));
    url.searchParams.set("serviceId", SERVICE_ID);
    url.searchParams.set("name", "channel-fixture");
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${TOKEN}` } });
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

  async function connectChannel(overrides: Record<string, string> = {}, token = TOKEN) {
    const url = new URL(`/api/v2/services/${SERVICE_ID}/codex`, baseUrl.replace("http:", "ws:"));
    for (const [key, value] of Object.entries({ clientId: CLIENT_ID, sessionId: SESSION_ID, ...overrides })) {
      url.searchParams.set(key, value);
    }
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
    sockets.push(socket);
    const messages = new MessageQueue(socket);
    await onOpen(socket);
    return { socket, messages };
  }

  function emitFrame(value: unknown): void {
    agent.send(JSON.stringify({ type: "event", event: "codex.channel", payload: value }));
  }

  function api(suffix: string, method: string, body?: unknown, token = TOKEN) {
    return fetch(`${baseUrl}/api/v2/services/${SERVICE_ID}/codex${suffix || "/runtime"}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "content-type": "application/json" }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  }
});

function snapshot(params: Json): Json {
  return {
    channelId: params.channelId, epoch: EPOCH, sequence: 0, initializeResult: { userAgent: "test" },
    frames: [], pendingRequests: [], runningVersion: "0.162.0", resyncRequired: false,
  };
}

function frame(channelId: string, sequence: number, message: unknown): Json {
  return { channelId, epoch: EPOCH, sequence, type: "rpc", message };
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
