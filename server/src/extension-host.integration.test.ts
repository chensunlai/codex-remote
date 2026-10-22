import { ChunkedTransport } from "../../transport/websocket.cjs";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { OfficialExtensionStore } from "../../agent/src/official-extension-store.js";
import { buildGateway, type BuiltGateway } from "./app.js";

const TOKEN = "official-extension-process-integration-token";
const CLIENT = "bf2c700a-079d-4c62-a5ed-927c6dc255a0";
const RENDERER = "881e332b-92d4-40ac-b674-175711576d08";
const NEW_RENDERER = "9b47b233-81aa-46b4-a21b-bc84fe65a090";
const PICTURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 255, 137, 0]);
const SCRIPT = Buffer.from("export const originalResource = 'unchanged';\n".repeat(4096));
type Json = Record<string, any>;

describe("Gateway and real Agent official extension host integration", () => {
  let directory: string;
  let gateway: BuiltGateway;
  let agent: ChildProcessWithoutNullStreams;
  let baseUrl: string;
  let serviceId: string;
  let ownerId: string;
  let stderr = "";
  let previousLogLevel: string | undefined;
  const sockets: WebSocket[] = [];

  beforeEach(async () => {
    previousLogLevel = process.env.LOG_LEVEL;
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "official-extension-integration-"));
    stderr = "";
    gateway = await buildGateway({ host: "127.0.0.1", port: 6767, dataDirectory: join(directory, "gateway"), apiTokens: [TOKEN], maxUploadBytes: 1024, maxDownloadBytes: 1024 });
    await gateway.app.listen({ host: "127.0.0.1", port: 0 });
    const address = gateway.app.server.address();
    if (!address || typeof address === "string") throw new Error("Gateway did not listen");
    baseUrl = `http://127.0.0.1:${address.port}`;
    ownerId = (await gateway.services.tokens.verify(TOKEN))!;

    const extension = join(directory, "source");
    await mkdir(join(extension, "webview"), { recursive: true });
    await writeFile(join(extension, "package.json"), JSON.stringify({ publisher: "openai", name: "chatgpt", version: "1.0.0", main: "extension.cjs" }));
    await writeFile(join(extension, "extension.cjs"), fixtureExtension);
    await writeFile(join(extension, "webview", "picture.png"), PICTURE);
    await writeFile(join(extension, "webview", "app.js"), SCRIPT);
    const store = new OfficialExtensionStore(join(directory, "agent-data", "official-extension", "packages"));
    await store.importDirectory(extension);
    await store.activate();
    const cli = join(directory, "codex.mjs");
    await writeFile(cli, fixtureCli);
    await chmod(cli, 0o755);

    const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
    const binary = process.env.CODEX_REMOTE_TEST_AGENT_BINARY;
    const command = binary ? resolve(root, binary) : process.execPath;
    const args = [
      ...(!binary ? ["--import", "tsx", join(root, "agent/src/index.ts")] : []),
      "--gateway", baseUrl, "--token", TOKEN, "--name", "extension-integration",
      "--codex", cli, "--config", join(directory, "agent.json"),
    ];
    agent = spawn(command, args, { cwd: root, stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, CODEX_HOME: directory } });
    agent.stdout.resume();
    agent.stderr.on("data", chunk => { stderr = (stderr + String(chunk)).slice(-16_384); });
    await eventually(async () => {
      const services = await gateway.services.agents.list(ownerId);
      serviceId = services[0]?.id ?? "";
      return services[0]?.runtime.state === "connected";
    });
  });

  afterEach(async () => {
    for (const socket of sockets.splice(0)) socket.terminate();
    if (agent && agent.exitCode === null && agent.signalCode === null) {
      await new Promise<void>((done) => {
        const timer = setTimeout(() => agent.kill("SIGKILL"), 7000);
        agent.once("exit", () => { clearTimeout(timer); done(); });
        agent.kill("SIGTERM");
      });
    }
    if (gateway) await gateway.app.close();
    if (directory) await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    if (previousLogLevel === undefined) delete process.env.LOG_LEVEL;
    else process.env.LOG_LEVEL = previousLogLevel;
  });

  it("preserves public WebView values, resources, native operations and offline tasks across renderer and Agent reconnects", async () => {
    const first = await connect();
    const ready = await first.messages.next(message => message.type === "ready");
    expect(ready.version).toBe("1.0.0");
    expect(ready.packageSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(ready.html).toContain("public-host-fixture");
    expect(ready.html).toContain(`/extension/1.0.0/webview/picture.png`);

    const resourceUrl = new URL(`/api/v3/services/${serviceId}/extension/resource`, baseUrl);
    resourceUrl.searchParams.set("version", "1.0.0");
    resourceUrl.searchParams.set("path", "webview/picture.png");
    const resource = await fetch(resourceUrl, { headers: authorization });
    expect(resource.status).toBe(200);
    expect(resource.headers.get("content-type")).toBe("image/png");
    expect(resource.headers.get("x-resource-sha256")).toBe(createHash("sha256").update(PICTURE).digest("hex"));
    expect(Buffer.from(await resource.arrayBuffer())).toEqual(PICTURE);
    resourceUrl.searchParams.set("path", "webview/app.js");
    resourceUrl.searchParams.set("packageSha256", ready.packageSha256);
    const script = await fetch(resourceUrl, { headers: authorization });
    expect(script.status).toBe(200);
    expect(script.headers.get("content-encoding")).toBe("gzip");
    expect(Number(script.headers.get("content-length"))).toBeLessThan(SCRIPT.length / 10);
    expect(script.headers.get("x-extension-package-sha256")).toBe(ready.packageSha256);
    expect(Buffer.from(await script.arrayBuffer())).toEqual(SCRIPT);

    const future = { futureField: [false, 0, null, { untouched: "value" }] };
    send(first.socket, ready.epoch, "opaque-once", future);
    const echoed = await first.messages.next(message => message.type === "message");
    expect(unwire(echoed.payload)).toEqual({ echo: future, bytes: Buffer.from([0, 137, 255]) });
    // Even a repeated accepted delivery ID must not execute the extension twice.
    send(first.socket, ready.epoch, "opaque-once", future);
    await first.messages.next(message => message.type === "delivery" && message.messageId === "opaque-once");
    packet(first.socket, { type: "message", epoch: ready.epoch, messageId: "binary-input", payload: ["bytes", "AP+J"] });
    const binaryEcho = await first.messages.next(message => message.type === "message");
    expect(unwire(binaryEcho.payload).echo).toEqual(Buffer.from([0, 255, 137]));

    send(first.socket, ready.epoch, "native-once", "clipboard");
    const native = await first.messages.next(message => message.type === "host-request");
    expect(native.payload.method).toBe("clipboard.read");
    send(first.socket, ready.epoch, "task-once", "task");
    const started = await first.messages.next(message => message.type === "message" && unwire(message.payload).event === "task-started");
    packet(first.socket, { type: "ack", sequence: started.sequence });
    const firstClosed = closed(first.socket);
    first.socket.close();
    await firstClosed;
    await eventually(async () => (await logs()).includes("task-finished"));

    const second = await connect({ epoch: ready.epoch, after: String(started.sequence) });
    const resumed = await second.messages.next(message => message.type === "ready");
    expect(resumed.epoch).toBe(ready.epoch);
    expect(resumed.resyncRequired).toBe(false);
    expect(resumed.pendingHostRequests).toEqual([native.payload]);
    expect(resumed.frames.some((frame: Json) => frame.type === "message" && unwire(frame.payload).event === "task-finished")).toBe(true);
    packet(second.socket, { type: "host-response", id: native.payload.id, result: "native clipboard" });
    const nativeResult = await second.messages.next(message => message.type === "message" && unwire(message.payload).event === "clipboard-result");
    expect(unwire(nativeResult.payload)).toEqual({ event: "clipboard-result", value: "native clipboard" });

    const lostTransport = closed(second.socket);
    gateway.services.agents.disconnect(ownerId, serviceId);
    expect(await lostTransport).toBe(1012);
    await eventually(() => gateway.services.agents.isOnline(ownerId, serviceId));
    const third = await connect({ epoch: ready.epoch, after: String(nativeResult.sequence) });
    const restored = await third.messages.next(message => message.type === "ready");
    expect(restored.epoch).toBe(ready.epoch);
    expect(restored.pendingHostRequests).toEqual([]);
    expect((await logs()).split("app-server-start").length - 1).toBe(1);
    expect((await logs()).split("task-accepted").length - 1).toBe(1);

    const superseded = closed(third.socket);
    const fourth = await connect({ rendererId: NEW_RENDERER });
    expect(await superseded).toBe(4001);
    const fresh = await fourth.messages.next(message => message.type === "ready");
    expect(fresh.epoch).toBe(ready.epoch);
    expect(fresh.frames).toEqual([]);
    await expect(gateway.services.agents.request(ownerId, serviceId, "extension.view.send", {
      viewId: fresh.viewId, rendererId: RENDERER, epoch: fresh.epoch, messageId: "stale-renderer", payload: wire("must not execute"),
    })).rejects.toThrow("接管");
    send(fourth.socket, fresh.epoch, "new-renderer", "fresh");
    const freshEcho = await fourth.messages.next(message => message.type === "message");
    expect(unwire(freshEcho.payload).echo).toBe("fresh");
    expect((await logs()).split("app-server-start").length - 1).toBe(1);

    const confirmation = await restart(false);
    expect(confirmation.confirmationRequired).toBe(true);
    expect(confirmation.affected).toEqual([expect.objectContaining({ active: true, version: "0.163.0" })]);
    const restarted = await restart(true);
    expect(restarted.confirmationRequired).toBe(false);
    await fourth.messages.next(message => message.type === "status" && message.payload.state === "reload");
    await eventually(async () => gateway.services.events.since(0, ownerId).events.some(event => {
      const operation = (event.payload as Json)?.operation;
      return event.type === "agent.codex.runtime" && operation?.id === restarted.operation.id && operation.state === "succeeded";
    }));
    await eventually(async () => (await logs()).includes("app-server-exit"));
    const fifth = await connect({ rendererId: "47d6cfd1-8933-4e5c-979a-77866113494c", epoch: ready.epoch });
    const restartedView = await fifth.messages.next(message => message.type === "ready");
    expect(restartedView.epoch).not.toBe(ready.epoch);
    send(fifth.socket, restartedView.epoch, "after-restart", "still works");
    expect(unwire((await fifth.messages.next(message => message.type === "message")).payload).echo).toBe("still works");
    expect((await logs()).split("task-accepted").length - 1).toBe(1);
  }, 30_000);

  it("delivers and replays a conversation snapshot larger than 16 MiB without replacing its host", async () => {
    const first = await connect();
    const ready = await first.messages.next(message => message.type === "ready");
    send(first.socket, ready.epoch, "large-snapshot", "large-snapshot");
    const frame = await first.messages.next(message => message.type === "message");
    const snapshot = unwire(frame.payload);
    expect(snapshot.body.length).toBe(20 * 1024 * 1024);
    expect(createHash("sha256").update(snapshot.body).digest("hex")).toBe(createHash("sha256").update("x".repeat(20 * 1024 * 1024)).digest("hex"));
    // Disconnect before acknowledging the snapshot, as can happen on mobile.
    const finished = closed(first.socket);
    first.socket.close();
    await finished;
    const second = await connect({ epoch: ready.epoch, after: String(ready.sequence) });
    const resumed = await second.messages.next(message => message.type === "ready");
    expect(resumed.epoch).toBe(ready.epoch);
    expect(resumed.resyncRequired).toBe(false);
    const replay = resumed.frames.find((item: Json) => item.sequence === frame.sequence);
    expect(unwire(replay.payload)).toEqual(snapshot);
    packet(second.socket, { type: "ack", sequence: resumed.sequence });
    send(second.socket, ready.epoch, "after-snapshot", "still connected");
    expect(unwire((await second.messages.next(message => message.type === "message")).payload).echo).toBe("still connected");
    expect((await gateway.services.agents.list(ownerId))[0]?.runtime.state).toBe("connected");
  }, 30_000);

  it("routes Claude and Codex independently through the real Agent and restores native diff decisions", async () => {
    const extension = join(directory, "claude-source");
    await mkdir(join(extension, "webview"), { recursive: true });
    await writeFile(join(extension, "package.json"), JSON.stringify({
      publisher: "Anthropic", name: "claude-code", version: "1.0.0", main: "extension.cjs",
      contributes: { menus: { "editor/title": [
        { command: "claude-vscode.acceptProposedDiff", when: "fixture.reviewing" },
        { command: "claude-vscode.rejectProposedDiff", when: "fixture.reviewing" },
      ] } },
    }));
    await writeFile(join(extension, "extension.cjs"), fixtureClaudeExtension);
    await writeFile(join(extension, "before.txt"), "before 中文🙂\n");
    await writeFile(join(extension, "after.txt"), "after 中文🙂\n");
    const claudeScript = Buffer.from("export const tool = 'claude';\n".repeat(4096));
    await writeFile(join(extension, "webview", "app.js"), claudeScript);
    const store = new OfficialExtensionStore(join(directory, "agent-data", "claude-extension", "packages"), { toolId: "claude" });
    await store.importDirectory(extension);
    await store.activate();

    const status = async (toolId?: string): Promise<Json> => {
      const url = new URL(`/api/v3/services/${serviceId}/extension/status`, baseUrl);
      if (toolId) url.searchParams.set("toolId", toolId);
      const response = await fetch(url, { headers: authorization });
      expect(response.status).toBe(200);
      return (await response.json() as Json).data;
    };
    expect(await status()).toMatchObject({ toolId: "codex", version: "1.0.0", hosts: 0 });
    expect(await status("claude")).toMatchObject({ toolId: "claude", version: "1.0.0", hosts: 0 });
    const codex = await connect();
    const codexReady = await codex.messages.next(message => message.type === "ready");
    // Reuse every client identity component: only the tool selects another host.
    const claude = await connect({ toolId: "claude" });
    const claudeReady = await claude.messages.next(message => message.type === "ready");
    expect(codexReady.toolId).toBe("codex");
    expect(claudeReady.toolId).toBe("claude");
    expect(claudeReady.html).toContain("claude-public-host-fixture");
    expect(claudeReady.viewId).not.toBe(codexReady.viewId);
    expect(claudeReady.epoch).not.toBe(codexReady.epoch);
    expect(claudeReady.packageSha256).not.toBe(codexReady.packageSha256);
    expect(await status()).toMatchObject({ hosts: 1 });
    expect(await status("claude")).toMatchObject({ hosts: 1 });

    // Equal versions and resource paths must still use the selected package.
    const resourceUrl = new URL(`/api/v3/services/${serviceId}/extension/resource`, baseUrl);
    resourceUrl.searchParams.set("version", "1.0.0");
    resourceUrl.searchParams.set("path", "webview/app.js");
    resourceUrl.searchParams.set("toolId", "claude");
    resourceUrl.searchParams.set("packageSha256", claudeReady.packageSha256);
    const claudeResource = await fetch(resourceUrl, { headers: authorization });
    expect(claudeResource.status).toBe(200);
    expect(claudeResource.headers.get("x-extension-package-sha256")).toBe(claudeReady.packageSha256);
    expect(Buffer.from(await claudeResource.arrayBuffer())).toEqual(claudeScript);
    resourceUrl.searchParams.delete("toolId");
    resourceUrl.searchParams.set("packageSha256", codexReady.packageSha256);
    const codexResource = await fetch(resourceUrl, { headers: authorization });
    expect(codexResource.status).toBe(200);
    expect(Buffer.from(await codexResource.arrayBuffer())).toEqual(SCRIPT);

    send(claude.socket, claudeReady.epoch, "claude-echo", { untouched: "中文🙂" });
    expect(unwire((await claude.messages.next(message => message.type === "message")).payload))
      .toEqual({ tool: "claude", echo: { untouched: "中文🙂" } });
    send(claude.socket, claudeReady.epoch, "claude-diff", "diff");
    const opened = await claude.messages.next(message => message.type === "surface" && message.payload.action === "open");
    expect(opened.payload).toMatchObject({ kind: "diff", pending: false, actions: ["accept", "reject"],
      diff: { originalText: "before 中文🙂\n", modifiedText: "after 中文🙂\n" } });
    packet(claude.socket, { type: "ack", sequence: opened.sequence });
    const disconnected = closed(claude.socket);
    claude.socket.close();
    await disconnected;
    const resumed = await connect({ toolId: "claude", epoch: claudeReady.epoch, after: String(opened.sequence) });
    const restored = await resumed.messages.next(message => message.type === "ready");
    expect(restored).toMatchObject({ toolId: "claude", viewId: claudeReady.viewId, epoch: claudeReady.epoch, resyncRequired: false });
    expect(restored.surfaces).toEqual([expect.objectContaining({ id: opened.payload.id, kind: "diff", pending: false,
      actions: ["accept", "reject"], diff: opened.payload.diff })]);
    expect(restored.frames.some((frame: Json) => frame.type === "message" && unwire(frame.payload).event === "decision")).toBe(false);
    packet(resumed.socket, { type: "surface-action", epoch: restored.epoch, surfaceId: opened.payload.id, action: "accept" });
    const accepted = await resumed.messages.next(message => message.type === "message" && unwire(message.payload).event === "decision");
    expect(unwire(accepted.payload)).toEqual({ event: "decision", action: "accept", tabId: opened.payload.id });
    await resumed.messages.next(message => message.type === "surface" && message.payload.action === "close" && message.payload.id === opened.payload.id);

    send(resumed.socket, restored.epoch, "claude-another-diff", "diff");
    const nextDiff = await resumed.messages.next(message => message.type === "surface" && message.payload.action === "open");
    packet(resumed.socket, { type: "close-surface", surfaceId: nextDiff.payload.id });
    const rejected = await resumed.messages.next(message => message.type === "message" && unwire(message.payload).event === "decision");
    expect(unwire(rejected.payload)).toEqual({ event: "decision", action: "reject", tabId: nextDiff.payload.id });
    await resumed.messages.next(message => message.type === "surface" && message.payload.action === "close" && message.payload.id === nextDiff.payload.id);
    expect(await readFile(join(extension, "before.txt"), "utf8")).toBe("before 中文🙂\n");

    const stopped = closed(resumed.socket);
    const response = await fetch(`${baseUrl}/api/v3/services/${serviceId}/extension/view/restart`, {
      method: "POST", headers: { ...authorization, "content-type": "application/json" },
      body: JSON.stringify({ clientId: CLIENT, workspace: directory, epoch: restored.epoch, toolId: "claude" }),
    });
    expect(response.status).toBe(200);
    expect(await stopped).toBe(4002);
    expect(await status("claude")).toMatchObject({ toolId: "claude", hosts: 0 });
    expect(await status()).toMatchObject({ toolId: "codex", hosts: 1 });
    send(codex.socket, codexReady.epoch, "codex-unaffected", "original host survives");
    const echo = await codex.messages.next(message => message.type === "message");
    expect(echo.epoch).toBe(codexReady.epoch);
    expect(unwire(echo.payload).echo).toBe("original host survives");
    const restarted = await connect({ toolId: "claude", rendererId: NEW_RENDERER });
    const restartedReady = await restarted.messages.next(message => message.type === "ready");
    expect(restartedReady.toolId).toBe("claude");
    expect(restartedReady.epoch).not.toBe(restored.epoch);
  }, 30_000);

  it("closes and restarts independent windows through HTTP without disturbing the other host", async () => {
    const otherClient = "55efc088-e146-4711-9d11-96412ea50cc9";
    const first = await connect();
    const firstReady = await first.messages.next(message => message.type === "ready");
    const second = await connect({ clientId: otherClient });
    const secondReady = await second.messages.next(message => message.type === "ready");
    expect(secondReady.viewId).not.toBe(firstReady.viewId);
    send(first.socket, firstReady.epoch, "first-task", "task");
    send(second.socket, secondReady.epoch, "second-task", "task");
    await first.messages.next(message => message.type === "message" && unwire(message.payload).event === "task-started");
    await second.messages.next(message => message.type === "message" && unwire(message.payload).event === "task-started");

    const control = async (action: string, clientId: string, epoch?: string) => {
      const response = await fetch(`${baseUrl}/api/v3/services/${serviceId}/extension/view/${action}`, {
        method: "POST", headers: { ...authorization, "content-type": "application/json" },
        body: JSON.stringify({ clientId, workspace: directory, ...(epoch ? { epoch } : {}) }),
      });
      expect(response.status).toBe(200);
      expect(await response.json()).toMatchObject({ data: { stopped: true } });
    };
    const firstClosed = closed(first.socket);
    await control("close", CLIENT, firstReady.epoch);
    expect(await firstClosed).toBe(4002);
    const status = await gateway.services.agents.request<{ hosts: number }>(ownerId, serviceId, "extension.status", {});
    expect(status.hosts).toBe(1);
    send(second.socket, secondReady.epoch, "other-survives", "still works");
    expect(unwire((await second.messages.next(message => message.type === "message" && unwire(message.payload).echo === "still works")).payload).echo).toBe("still works");
    // An evicted/disconnected window is managed by identity without a renderer.
    const secondClosed = closed(second.socket); second.socket.close(); await secondClosed;
    await control("restart", otherClient, secondReady.epoch);
    await control("close", CLIENT);
    const restored = await connect({ clientId: otherClient, rendererId: NEW_RENDERER });
    const ready = await restored.messages.next(message => message.type === "ready");
    expect(ready.epoch).not.toBe(secondReady.epoch);
    send(restored.socket, ready.epoch, "after-target-restart", "restored");
    expect(unwire((await restored.messages.next(message => message.type === "message")).payload).echo).toBe("restored");
  }, 30_000);

  it("transfers an opaque message larger than 64 MiB in both directions and keeps later updates ordered", async () => {
    const channel = await connect();
    const ready = await channel.messages.next(message => message.type === "ready");
    const value = "你🙂".repeat(10 * 1024 * 1024);
    send(channel.socket, ready.epoch, "above-64", value);
    const response = unwire((await channel.messages.next(message => message.type === "message")).payload);
    expect(createHash("sha256").update(response.echo).digest("hex")).toBe(createHash("sha256").update(value).digest("hex"));
    send(channel.socket, ready.epoch, "after-large", "next");
    expect(unwire((await channel.messages.next(message => message.type === "message")).payload).echo).toBe("next");
  }, 60_000);

  it("captures limits at handshake and chooses the lower Agent/Gateway limit", async () => {
    const first = await connect();
    await first.messages.next(message => message.type === "ready");
    expect(transports.get(first.socket)!.maxMessageBytes).toBe(128 * 1024 * 1024);
    async function settings(size: number) {
      const reply = await fetch(`${baseUrl}/api/v1/admin/settings`, { method: "PUT", headers: { ...authorization, "content-type": "application/json" },
        body: JSON.stringify({ maxUploadBytes: 1024, maxDownloadBytes: 1024, maxMessageBytes: size * 1024 * 1024 }) });
      expect(reply.status).toBe(200);
    }
    await settings(64);
    expect(transports.get(first.socket)!.maxMessageBytes).toBe(128 * 1024 * 1024);
    const second = await connect({ rendererId: NEW_RENDERER });
    await second.messages.next(message => message.type === "ready");
    expect(transports.get(second.socket)!.maxMessageBytes).toBe(64 * 1024 * 1024);
    await settings(512);
    const third = await connect({ rendererId: "47d6cfd1-8933-4e5c-979a-77866113494c" });
    await third.messages.next(message => message.type === "ready");
    expect(transports.get(third.socket)!.maxMessageBytes).toBe(128 * 1024 * 1024);
  });

  const authorization = { Authorization: `Bearer ${TOKEN}` };

  async function connect(overrides: Record<string, string> = {}) {
    const url = new URL(`/api/v4/services/${serviceId}/extension`, baseUrl.replace("http:", "ws:"));
    for (const [key, value] of Object.entries({ clientId: CLIENT, rendererId: RENDERER, workspace: directory, ...overrides })) url.searchParams.set(key, value);
    const socket = new WebSocket(url, { headers: authorization });
    sockets.push(socket);
    const transport = new ChunkedTransport(socket);
    transports.set(socket, transport);
    const messages = new Messages(socket, transport);
    await new Promise<void>((done, reject) => { socket.once("open", done); socket.once("error", reject); });
    await transport.ready;
    return { socket, messages };
  }
  async function logs(): Promise<string> { return readFile(join(directory, "task.log"), "utf8").catch(() => ""); }
  async function restart(confirmed: boolean): Promise<Json> {
    const response = await fetch(`${baseUrl}/api/v2/services/${serviceId}/codex/restart`, {
      method: "POST", headers: { ...authorization, "content-type": "application/json" }, body: JSON.stringify({ confirmed }),
    });
    expect(response.status).toBe(202);
    return (await response.json() as Json).data;
  }
  async function eventually(predicate: () => boolean | Promise<boolean>) {
    const deadline = Date.now() + 10_000;
    while (!await predicate()) {
      if (Date.now() > deadline) throw new Error(`Condition timed out; Agent stderr: ${stderr}`);
      await new Promise(done => setTimeout(done, 20));
    }
  }
});

function closed(socket: WebSocket): Promise<number> {
  if (socket.readyState === socket.CLOSED) return Promise.resolve(1000);
  return new Promise(done => socket.once("close", done));
}

class Messages {
  readonly pending: Json[] = [];
  private readonly waiters: Array<{ predicate: (message: Json) => boolean; done: (message: Json) => void }> = [];
  constructor(socket: WebSocket, transport: ChunkedTransport) {
    transport.onMessage = data => {
      const value = JSON.parse(data.toString()) as Json;
      const index = this.waiters.findIndex(waiter => waiter.predicate(value));
      if (index >= 0) this.waiters.splice(index, 1)[0]!.done(value);
      else this.pending.push(value);
    };
  }
  next(predicate: (message: Json) => boolean): Promise<Json> {
    const index = this.pending.findIndex(predicate);
    if (index >= 0) return Promise.resolve(this.pending.splice(index, 1)[0]!);
    return new Promise((done, reject) => {
      const waiter = { predicate, done: (value: Json) => { clearTimeout(timer); done(value); } };
      const timer = setTimeout(() => {
        const index = this.waiters.indexOf(waiter);
        if (index >= 0) this.waiters.splice(index, 1);
        reject(new Error(`Expected extension message; queued ${JSON.stringify(this.pending)}`));
      }, 30000);
      this.waiters.push(waiter);
    });
  }
}
const transports = new WeakMap<WebSocket, ChunkedTransport>();
function packet(socket: WebSocket, value: unknown) { void transports.get(socket)!.send(value); }
function send(socket: WebSocket, epoch: string, messageId: string, value: unknown) {
  packet(socket, { type: "message", epoch, messageId, payload: wire(value) });
}
function wire(value: unknown): unknown {
  if (value == null) return ["null", null];
  if (typeof value === "string") return ["string", value];
  if (typeof value === "number") return ["number", value];
  if (typeof value === "boolean") return ["bool", value];
  if (Array.isArray(value)) return ["array", value.map(wire)];
  return ["object", Object.entries(value as Json).map(([key, item]) => [key, wire(item)])];
}
function unwire(value: any): any {
  const [tag, item] = value;
  if (tag === "bytes") return Buffer.from(item, "base64");
  if (tag === "array") return item.map(unwire);
  if (tag === "object") return Object.fromEntries(item.map(([key, entry]: [string, unknown]) => [key, unwire(entry)]));
  return item;
}

const fixtureExtension = `const vscode=require('vscode');
const {spawn}=require('node:child_process');
const {createInterface}=require('node:readline');
exports.activate=context=>context.subscriptions.push(vscode.window.registerWebviewViewProvider('chatgpt.sidebarView',{resolveWebviewView(view){
  const image=view.webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri,'webview','picture.png'));
  view.webview.html='<!doctype html><title>public-host-fixture</title><img src="'+image.toString()+'">';
  view.webview.onDidReceiveMessage(async message=>{
    if(message==='large-snapshot'){view.webview.postMessage({body:'x'.repeat(20*1024*1024)});return;}
    if(message==='clipboard'){view.webview.postMessage({event:'clipboard-result',value:await vscode.env.clipboard.readText()});return;}
    if(message==='task'){
      const child=spawn(vscode.workspace.getConfiguration('chatgpt').get('cliExecutable'),['app-server'],{stdio:['pipe','pipe','pipe']});
      child.stderr.resume();
      createInterface({input:child.stdout}).on('line',line=>view.webview.postMessage(JSON.parse(line)));
      child.stdin.write('RUN\\n');
      return;
    }
    view.webview.postMessage({echo:message,bytes:new Uint8Array([0,137,255])});
  });
}}));`;

const fixtureClaudeExtension = `const vscode=require('vscode');
exports.activate=context=>{
  let main;
  context.subscriptions.push(vscode.window.onDidChangeVisibleTextEditors(editors=>
    vscode.commands.executeCommand('setContext','fixture.reviewing',editors.length>0)));
  for(const action of ['accept','reject'])context.subscriptions.push(vscode.commands.registerCommand('claude-vscode.'+action+'ProposedDiff',async()=>{
    const tab=vscode.window.tabGroups.activeTabGroup.activeTab;
    await main.webview.postMessage({event:'decision',action,tabId:tab.id});
    await vscode.window.tabGroups.close(tab);
  }));
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('claudeVSCodeSidebar',{resolveWebviewView(view){
    main=view;view.webview.html='<!doctype html><title>claude-public-host-fixture</title>';
    view.webview.onDidReceiveMessage(async message=>{
      if(message==='diff'){
        await vscode.commands.executeCommand('vscode.diff',vscode.Uri.joinPath(context.extensionUri,'before.txt'),
          vscode.Uri.joinPath(context.extensionUri,'after.txt'),'Claude proposed edit');
      }else view.webview.postMessage({tool:'claude',echo:message});
    });
  }}));
};`;

// This fixture has a tiny application-specific stdin/stdout protocol, not Codex internals.
const fixtureCli = `#!/usr/bin/env node
import {appendFileSync} from 'node:fs';
import {dirname,join} from 'node:path';
import {createInterface} from 'node:readline';
if(process.argv.includes('--version')){console.log('codex-cli 0.163.0');process.exit(0);}
if(process.argv.includes('--help')){console.log('Commands:\\n  update  Update CLI');process.exit(0);}
if(!process.argv.includes('app-server'))process.exit(2);
const log=text=>appendFileSync(join(dirname(process.argv[1]),'task.log'),text+'\\n');
log('app-server-start');
process.on('SIGTERM',()=>{log('app-server-exit');process.exit(0);});
createInterface({input:process.stdin}).on('line',line=>{
  if(line!=='RUN')return;
  log('task-accepted');
  console.log(JSON.stringify({event:'task-started'}));
  setTimeout(()=>{log('task-finished');console.log(JSON.stringify({event:'task-finished',result:'offline result'}));},500);
});
`;
