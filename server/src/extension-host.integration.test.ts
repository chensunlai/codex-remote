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

    const future = { futureField: [false, 0, null, { untouched: "value" }] };
    send(first.socket, ready.epoch, "opaque-once", future);
    const echoed = await first.messages.next(message => message.type === "message");
    expect(unwire(echoed.payload)).toEqual({ echo: future, bytes: Buffer.from([0, 137, 255]) });
    // Even a repeated accepted delivery ID must not execute the extension twice.
    send(first.socket, ready.epoch, "opaque-once", future);
    await first.messages.next(message => message.type === "delivery" && message.messageId === "opaque-once");
    first.socket.send(JSON.stringify({ type: "message", epoch: ready.epoch, messageId: "binary-input", payload: ["bytes", "AP+J"] }));
    const binaryEcho = await first.messages.next(message => message.type === "message");
    expect(unwire(binaryEcho.payload).echo).toEqual(Buffer.from([0, 255, 137]));

    send(first.socket, ready.epoch, "native-once", "clipboard");
    const native = await first.messages.next(message => message.type === "host-request");
    expect(native.payload.method).toBe("clipboard.read");
    send(first.socket, ready.epoch, "task-once", "task");
    const started = await first.messages.next(message => message.type === "message" && unwire(message.payload).event === "task-started");
    first.socket.send(JSON.stringify({ type: "ack", sequence: started.sequence }));
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
    second.socket.send(JSON.stringify({ type: "host-response", id: native.payload.id, result: "native clipboard" }));
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

  const authorization = { Authorization: `Bearer ${TOKEN}` };

  async function connect(overrides: Record<string, string> = {}) {
    const url = new URL(`/api/v3/services/${serviceId}/extension`, baseUrl.replace("http:", "ws:"));
    for (const [key, value] of Object.entries({ clientId: CLIENT, rendererId: RENDERER, workspace: directory, ...overrides })) url.searchParams.set(key, value);
    const socket = new WebSocket(url, { headers: authorization });
    sockets.push(socket);
    const messages = new Messages(socket);
    await new Promise<void>((done, reject) => { socket.once("open", done); socket.once("error", reject); });
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
  constructor(socket: WebSocket) {
    socket.on("message", data => {
      const value = JSON.parse(data.toString()) as Json;
      const index = this.waiters.findIndex(waiter => waiter.predicate(value));
      if (index >= 0) this.waiters.splice(index, 1)[0]!.done(value);
      else this.pending.push(value);
    });
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
      }, 7000);
      this.waiters.push(waiter);
    });
  }
}
function send(socket: WebSocket, epoch: string, messageId: string, value: unknown) {
  socket.send(JSON.stringify({ type: "message", epoch, messageId, payload: wire(value) }));
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
