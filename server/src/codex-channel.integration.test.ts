import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { buildGateway, type BuiltGateway } from "./app.js";

const TOKEN = "real-agent-integration-token";
const CLIENT_ID = "bf2c700a-079d-4c62-a5ed-927c6dc255a0";
const SESSION_ID = "881e332b-92d4-40ac-b674-175711576d08";
type Json = Record<string, any>;

describe("Gateway and Agent process channel integration", () => {
  let directory: string;
  let gateway: BuiltGateway;
  let baseUrl: string;
  let serviceId: string;
  let agent: ChildProcessWithoutNullStreams;
  let sockets: WebSocket[];
  let stderr: string;

  beforeEach(async () => {
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "codex-channel-integration-"));
    sockets = [];
    stderr = "";
    gateway = await buildGateway({
      host: "127.0.0.1", port: 6767, dataDirectory: join(directory, "gateway"),
      apiTokens: [TOKEN], maxUploadBytes: 1024, maxDownloadBytes: 1024,
    });
    await gateway.app.listen({ host: "127.0.0.1", port: 0 });
    const address = gateway.app.server.address();
    if (!address || typeof address === "string") throw new Error("Missing gateway address");
    baseUrl = `http://127.0.0.1:${address.port}`;
    const executable = join(directory, "fake-codex.mjs");
    await writeFile(executable, fakeCli);
    await chmod(executable, 0o755);
    const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
    agent = spawn(process.execPath, [
      "--import", "tsx", join(root, "agent/src/index.ts"),
      "--gateway", baseUrl, "--token", TOKEN, "--name", "integration",
      "--codex", executable, "--config", join(directory, "agent.json"),
    ], { cwd: root, stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, CODEX_HOME: directory } });
    agent.stdout.resume();
    agent.stderr.on("data", (chunk) => { stderr += String(chunk); });
    await eventually(async () => {
      const services = await gateway.services.agents.list((await gateway.services.tokens.verify(TOKEN))!);
      serviceId = services[0]?.id ?? "";
      return services[0]?.runtime.state === "connected";
    });
  });

  afterEach(async () => {
    for (const socket of sockets) socket.terminate();
    if (agent && agent.exitCode === null && agent.signalCode === null) {
      const exited = new Promise<void>((resolve) => agent.once("exit", () => resolve()));
      agent.kill("SIGTERM");
      await exited;
    }
    if (gateway) await gateway.app.close();
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    delete process.env.LOG_LEVEL;
  });

  it("keeps an offline task and approval through app and Agent transport reconnects without resubmission", async () => {
    const first = await connect();
    const firstReady = await first.messages.next((message) => message.type === "ready");
    expect(firstReady.runningVersion).toBe("0.162.0");
    const start = { type: "rpc", messageId: "start-once", epoch: firstReady.epoch,
      message: { id: 1, method: "turn/start", params: { threadId: "thread-1", input: [] } } };
    first.socket.send(JSON.stringify(start));
    await first.messages.next((message) => message.type === "delivery");
    const started = await first.messages.next((message) => message.message?.method === "turn/started");
    first.socket.close();
    await eventually(async () => (await logs()).includes("approval-sent"));

    const second = await connect({ after: String(started.sequence), epoch: firstReady.epoch });
    const secondReady = await second.messages.next((message) => message.type === "ready");
    expect(secondReady.epoch).toBe(firstReady.epoch);
    expect(secondReady.resyncRequired).toBe(false);
    expect(secondReady.frames.some((frame: Json) => frame.message?.method === "item/agentMessage/delta")).toBe(true);
    expect(secondReady.pendingRequests).toEqual([{
      id: "approval-1", method: "item/commandExecution/requestApproval", params: { threadId: "thread-1" },
    }]);
    second.socket.send(JSON.stringify(start));
    expect(await second.messages.next((message) => message.type === "delivery")).toEqual(expect.objectContaining({
      messageId: "start-once", duplicate: true, status: "accepted",
    }));
    expect((await logs()).split("turn/start").length - 1).toBe(1);

    const ownerId = (await gateway.services.tokens.verify(TOKEN))!;
    const disconnected = new Promise<number>((resolve) => second.socket.once("close", resolve));
    gateway.services.agents.disconnect(ownerId, serviceId);
    expect(await disconnected).toBe(1012);
    await eventually(async () => gateway.services.agents.isOnline(ownerId, serviceId));
    const third = await connect({ after: String(secondReady.sequence), epoch: firstReady.epoch });
    const thirdReady = await third.messages.next((message) => message.type === "ready");
    expect(thirdReady.epoch).toBe(firstReady.epoch);
    expect(thirdReady.pendingRequests).toEqual(secondReady.pendingRequests);

    const closedForRestart = new Promise<number>((resolve) => third.socket.once("close", resolve));
    await gateway.app.close();
    await closedForRestart;
    gateway = await buildGateway({
      host: "127.0.0.1", port: Number(new URL(baseUrl).port), dataDirectory: join(directory, "gateway"),
      apiTokens: [TOKEN], maxUploadBytes: 1024, maxDownloadBytes: 1024,
    });
    await gateway.app.listen({ host: "127.0.0.1", port: Number(new URL(baseUrl).port) });
    await eventually(async () => gateway.services.agents.isOnline(ownerId, serviceId));
    const fourth = await connect({ after: String(thirdReady.sequence), epoch: firstReady.epoch });
    const fourthReady = await fourth.messages.next((message) => message.type === "ready");
    expect(fourthReady.epoch).toBe(firstReady.epoch);
    expect(fourthReady.pendingRequests).toEqual(secondReady.pendingRequests);
    fourth.socket.send(JSON.stringify({ type: "rpc", messageId: "approval-once", epoch: fourthReady.epoch,
      message: { id: "approval-1", result: { decision: "accept" } } }));
    await fourth.messages.next((message) => message.type === "delivery");
    const completed = await fourth.messages.next((message) => message.message?.method === "turn/completed");
    expect(completed.message.params.turn.status).toBe("completed");
    expect((await logs()).split("app-server-start").length - 1).toBe(1);
  }, 15_000);

  it("isolates late responses when a new page reuses an RPC id", async () => {
    const first = await connect();
    const ready = await first.messages.next((message) => message.type === "ready");
    first.socket.send(JSON.stringify({ type: "rpc", messageId: "old-call", epoch: ready.epoch,
      message: { id: 7, method: "fixture/delayed", params: {} } }));
    await first.messages.next((message) => message.type === "delivery");
    first.socket.close();
    const second = await connect({ sessionId: "9b47b233-81aa-46b4-a21b-bc84fe65a090", epoch: ready.epoch });
    const recovered = await second.messages.next((message) => message.type === "ready");
    expect(recovered.resyncRequired).toBe(true);
    second.socket.send(JSON.stringify({ type: "rpc", messageId: "new-call", epoch: recovered.epoch,
      message: { id: 7, method: "fixture/current", params: {} } }));
    const response = await second.messages.next((message) => message.type === "rpc" && message.message?.id === 7);
    expect(response.message.result).toEqual({ source: "current" });
    await eventually(async () => (await logs()).includes("late-response-sent"));
    second.socket.send(JSON.stringify({ type: "rpc", messageId: "barrier", epoch: recovered.epoch,
      message: { id: 8, method: "fixture/current", params: {} } }));
    await second.messages.next((message) => message.message?.id === 8);
    expect(second.messages.pending.some((message) => message.message?.result?.source === "old")).toBe(false);
  });

  it("bridges fragmented official IPC live, fences old pages and never replays coordination", async () => {
    await mkdir(join(directory, "ipc"), { mode: 0o700 });
    const peers: Socket[] = [];
    const received: Buffer[] = [];
    const router = createServer(peer => { peers.push(peer); peer.on("data", data => received.push(data)); });
    await new Promise<void>(resolve => router.listen(join(directory, "ipc/ipc.sock"), resolve));
    try {
      const first = await connect();
      const ready = await first.messages.next(message => message.type === "ready");
      expect(ready.clientCoordination).toBe(true);
      const control = { type: "coordination", epoch: ready.epoch, message: { type: "connect" } };
      first.socket.send(JSON.stringify(control));
      await first.messages.next(message => message.type === "coordination" && message.message?.type === "connected");
      const request = { type: "request", requestId: "initialize", sourceClientId: "initializing-client",
        version: 0, method: "initialize", params: { clientType: "vscode" } };
      first.socket.send(JSON.stringify({ ...control, message: { type: "message", message: request } }));
      await eventually(() => Buffer.concat(received).length >= 4 + Buffer.byteLength(JSON.stringify(request)));
      const bytes = Buffer.concat(received);
      expect(bytes.readUInt32LE()).toBe(bytes.length - 4);
      expect(JSON.parse(bytes.subarray(4).toString())).toEqual(request);
      const response = { type: "response", requestId: "initialize", resultType: "success", method: "initialize", result: { clientId: "fixture-phone" } };
      const payload = Buffer.from(JSON.stringify(response));
      const frame = Buffer.alloc(payload.length + 4);
      frame.writeUInt32LE(payload.length); payload.copy(frame, 4);
      peers[0]!.write(frame.subarray(0, 2));
      peers[0]!.write(frame.subarray(2, 7));
      peers[0]!.write(frame.subarray(7));
      const event = await first.messages.next(message => message.type === "coordination" && message.message?.type === "message");
      expect(event.message.message).toEqual(response);
      first.socket.close();
      await eventually(() => peers[0]!.destroyed);
      const second = await connect({ epoch: ready.epoch });
      const restored = await second.messages.next(message => message.type === "ready");
      expect(restored.epoch).toBe(ready.epoch);
      expect(restored.frames.some((frame: Json) => frame.type === "coordination")).toBe(false);
      second.socket.send(JSON.stringify(control));
      await second.messages.next(message => message.type === "coordination" && message.message?.type === "connected");
      expect(peers).toHaveLength(2);
      expect(Buffer.concat(received)).toEqual(bytes);
      second.socket.close();
      await eventually(() => peers[1]!.destroyed);
      // A socket in a writable directory must not be trusted as the local official router.
      await chmod(join(directory, "ipc"), 0o777);
      const third = await connect({ epoch: ready.epoch });
      await third.messages.next(message => message.type === "ready");
      third.socket.send(JSON.stringify(control));
      await third.messages.next(message => message.type === "coordination" && message.message?.type === "unavailable");
      expect(peers).toHaveLength(2);
    } finally { for (const peer of peers) peer.destroy(); router.close(); }
  });

  it("requires confirmation for an active runtime and reattaches with a fresh epoch after restart", async () => {
    const first = await connect();
    const ready = await first.messages.next((message) => message.type === "ready");
    first.socket.send(JSON.stringify({ type: "rpc", messageId: "running-turn", epoch: ready.epoch,
      message: { id: 1, method: "turn/start", params: { threadId: "thread-1", input: [] } } }));
    await first.messages.next((message) => message.message?.method === "turn/started");
    const requested = await restart(false);
    expect(requested.confirmationRequired).toBe(true);
    expect(requested.affected).toEqual([expect.objectContaining({ active: true })]);
    expect((await logs()).split("app-server-start").length - 1).toBe(1);
    const confirmed = await restart(true);
    expect(confirmed.confirmationRequired).toBe(false);
    const closed = await first.messages.next((message) => message.type === "closed");
    expect(closed.epoch).toBe(ready.epoch);
    first.socket.close();
    const ownerId = (await gateway.services.tokens.verify(TOKEN))!;
    await eventually(async () => gateway.services.events.since(0, ownerId).events.some((event) => {
      const operation = (event.payload as Json)?.operation;
      return event.type === "agent.codex.runtime" && operation?.id === confirmed.operation.id
        && operation.state === "succeeded";
    }));
    expect((await logs()).split("app-server-start").length - 1).toBe(2);
    const second = await connect({ after: String(closed.sequence), epoch: ready.epoch });
    const recovered = await second.messages.next((message) => message.type === "ready");
    expect(recovered.epoch).not.toBe(ready.epoch);
    expect(recovered.resyncRequired).toBe(true);
    expect(recovered.pendingRequests).toEqual([]);
    second.socket.send(JSON.stringify({ type: "rpc", messageId: "new-runtime", epoch: recovered.epoch,
      message: { id: 2, method: "fixture/current", params: {} } }));
    expect((await second.messages.next((message) => message.message?.id === 2)).message.result).toEqual({ source: "current" });
  });

  async function connect(overrides: Record<string, string> = {}) {
    const url = new URL(`/api/v2/services/${serviceId}/codex`, baseUrl.replace("http:", "ws:"));
    for (const [key, value] of Object.entries({ clientId: CLIENT_ID, sessionId: SESSION_ID, ...overrides })) {
      url.searchParams.set(key, value);
    }
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${TOKEN}` } });
    sockets.push(socket);
    const messages = new Messages(socket);
    await new Promise<void>((resolve, reject) => { socket.once("open", resolve); socket.once("error", reject); });
    return { socket, messages };
  }

  async function logs(): Promise<string> {
    return readFile(join(directory, "fake.log"), "utf8").catch(() => "");
  }

  async function restart(confirmed: boolean): Promise<Json> {
    const response = await fetch(`${baseUrl}/api/v2/services/${serviceId}/codex/restart`, {
      method: "POST", headers: { Authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ confirmed }),
    });
    expect(response.status).toBe(202);
    return (await response.json() as Json).data;
  }

  async function eventually(predicate: () => Promise<boolean>): Promise<void> {
    const deadline = Date.now() + 8_000;
    while (!await predicate()) {
      if (Date.now() > deadline) throw new Error(`Condition timed out; Agent stderr: ${stderr}`);
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
  }
});

class Messages {
  readonly pending: Json[] = [];
  private readonly waiters: Array<{ predicate: (value: Json) => boolean; resolve: (value: Json) => void }> = [];

  constructor(socket: WebSocket) {
    socket.on("message", (raw) => {
      const value = JSON.parse(raw.toString()) as Json;
      const index = this.waiters.findIndex(({ predicate }) => predicate(value));
      if (index >= 0) this.waiters.splice(index, 1)[0]!.resolve(value);
      else this.pending.push(value);
    });
  }

  next(predicate: (value: Json) => boolean): Promise<Json> {
    const index = this.pending.findIndex(predicate);
    if (index >= 0) return Promise.resolve(this.pending.splice(index, 1)[0]!);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Expected message; queued ${JSON.stringify(this.pending)}`)), 5_000);
      this.waiters.push({ predicate, resolve: (value) => { clearTimeout(timer); resolve(value); } });
    });
  }
}

const fakeCli = `#!/usr/bin/env node
import { appendFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
const args = process.argv.slice(2);
if (args[0] === '--version') { console.log('codex-cli 0.162.0'); process.exit(0); }
if (args[0] === '--help') { console.log('Commands:\\n  update  Update CLI'); process.exit(0); }
if (args[0] !== 'app-server') process.exit(1);
const log = text => appendFileSync(join(dirname(process.argv[1]), 'fake.log'), text + '\\n');
const send = message => process.stdout.write(JSON.stringify(message) + '\\n');
log('app-server-start');
createInterface({ input: process.stdin }).on('line', line => {
  const message = JSON.parse(line);
  if (message.method) log(message.method);
  if (message.method === 'initialize') send({ id: message.id, result: { userAgent: 'fake-codex' } });
  else if (message.method === 'turn/start') {
    send({ id: message.id, result: { turn: { id: 'turn-1', status: 'inProgress' } } });
    send({ method: 'turn/started', params: { threadId: 'thread-1', turn: { id: 'turn-1' } } });
    setTimeout(() => {
      send({ method: 'item/agentMessage/delta', params: { threadId: 'thread-1', delta: 'offline output' } });
      send({ id: 'approval-1', method: 'item/commandExecution/requestApproval', params: { threadId: 'thread-1' } });
      log('approval-sent');
    }, 200);
  } else if (message.id === 'approval-1' && !message.method) {
    send({ method: 'serverRequest/resolved', params: { requestId: 'approval-1' } });
    send({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
  } else if (message.method === 'fixture/delayed') {
    setTimeout(() => { send({ id: message.id, result: { source: 'old' } }); log('late-response-sent'); }, 200);
  } else if (message.method === 'fixture/current') send({ id: message.id, result: { source: 'current' } });
}).on('close', () => process.exit(0));
`;
