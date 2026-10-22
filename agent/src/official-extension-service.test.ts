import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { afterEach, describe, expect, it } from "vitest";
import { OfficialExtensionService, type ExtensionViewFrame, type ExtensionSurface } from "./official-extension-service.js";
import { OfficialExtensionStore } from "./official-extension-store.js";

const roots: string[] = [];
const services: OfficialExtensionService[] = [];
afterEach(async () => { for (const service of services.splice(0)) service.close(); for (const root of roots.splice(0)) await rm(root, { recursive: true, force: true }); });
const encode = (value: string) => ["string", value];

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "official-host-test-")); roots.push(root);
  const extension = join(root, "source"); await mkdir(join(extension, "webview"), { recursive: true });
  await writeFile(join(extension, "package.json"), JSON.stringify({ publisher: "OpenAI", name: "chatgpt", version: "1.0.0", main: "extension.cjs" }));
  await writeFile(join(extension, "webview", "index.html"), "<!doctype html><title>Fixture</title>");
  await writeFile(join(extension, "extension.cjs"), `const v=require('vscode');const {spawn}=require('node:child_process');
exports.activate=ctx=>{ctx.subscriptions.push(v.window.registerWebviewViewProvider('chatgpt.sidebarView',{resolveWebviewView(view){
view.webview.html='<!doctype html><title>Fixture</title>';
const panels=[];
view.webview.onDidReceiveMessage(async message=>{
if(message==='watcher-error'||message==='watcher-dispose'){
const fs=require('node:fs'),{EventEmitter}=require('node:events');const original=fs.watch;
fs.watch=()=>{const watcher=new EventEmitter();watcher.close=()=>fs.appendFileSync(require('node:path').join(v.workspace.workspaceFolders[0].uri.fsPath,'watcher-closed'),'closed\\n');
if(message==='watcher-error')setImmediate(()=>watcher.emit('error',Object.assign(new Error('permission denied'),{code:'EACCES'})));return watcher;};
try{const watcher=v.workspace.createFileSystemWatcher('**/*');if(message==='watcher-dispose'){watcher.dispose();watcher.dispose();v.workspace.createFileSystemWatcher('**/*');}}
finally{fs.watch=original;}setTimeout(()=>view.webview.postMessage('watcher-alive'),30);return;
}
if(message==='recoverable-error'){Promise.reject(new Error('nonfatal fixture'));return;}
if(message==='exit-host'){process.exit(1);}
if(message==='clipboard'){view.webview.postMessage(await v.env.clipboard.readText());return;}
if(message==='close-settings-later'){setTimeout(()=>{panels[0].dispose();require('node:fs').writeFileSync(require('node:path').join(v.workspace.workspaceFolders[0].uri.fsPath,'panel-closed'),'closed');},50);return;}
if(message==='panels'){
for(const name of ['Settings','Other']){
const panel=v.window.createWebviewPanel('codex.fixture',name,v.ViewColumn.Active,{enableScripts:true});panels.push(panel);
panel.title=name+' panel';
panel.webview.html='<!doctype html><title>'+name+'</title>';
panel.webview.onDidReceiveMessage(async value=>{
if(value==='clipboard'){panel.webview.postMessage(name+':native:'+await v.env.clipboard.readText());return;}
panel.webview.postMessage(name+':'+value);
});
panel.onDidDispose(()=>view.webview.postMessage('closed:'+name));
}return;
}
if(message==='preview'){const doc=await v.workspace.openTextDocument(v.Uri.joinPath(ctx.extensionUri,'webview','index.html'));await v.window.showTextDocument(doc,{selection:new v.Range(99,0,99,0)});view.webview.postMessage(doc.getText());return;}
if(message==='task'){const child=spawn(v.workspace.getConfiguration('chatgpt').get('cliExecutable'),['app-server']);child.stdout.resume();child.stderr.resume();ctx.subscriptions.push({dispose(){child.kill()}});return;}
await ctx.globalState.update('draft',message);view.webview.postMessage({echo:ctx.globalState.get('draft'),bytes:new Uint8Array([0,137,255])});
});}}));};`);
  const cli = join(root, "codex");
  await writeFile(cli, `#!/usr/bin/env node
const fs=require('node:fs');if(process.argv.includes('--version')){console.log('codex-cli 1.0.0');process.exit(0)}
fs.appendFileSync(${JSON.stringify(join(root, "starts"))},'start\\n');setTimeout(()=>fs.writeFileSync(${JSON.stringify(join(root, "finished"))},'done'),300);setInterval(()=>{},1000);
`, { mode: 0o755 });
  const store = new OfficialExtensionStore(join(root, "packages")); await store.importDirectory(extension); await store.activate();
  const frames: ExtensionViewFrame[] = [];
  const service = new OfficialExtensionService(join(root, "state"), cli, frame => frames.push(frame), store); services.push(service);
  const attach = { viewId: "test-view", rendererId: "renderer-one", workspace: root, after: 0 };
  const snapshot = await service.request("extension.view.attach", attach) as { epoch: string; html: string; sequence: number; frames: ExtensionViewFrame[] };
  const send = (messageId: string, payload: unknown) => service.request("extension.view.send", { ...attach, epoch: snapshot.epoch, messageId, payload });
  return { root, extension, store, service, frames, attach, snapshot, send };
}

async function until(check: () => boolean | Promise<boolean>) {
  const start = Date.now();
  while (!await check()) { if (Date.now() - start > 5000) throw new Error("Timed out"); await new Promise(resolve => setTimeout(resolve, 20)); }
}

describe("official extension process lifecycle", () => {
  it("can restart the extension and launch Codex again after its app-server was killed", async () => {
    const f = await fixture();
    await f.send("task", encode("task"));
    await until(() => f.service.runtimeStatus().length > 0);
    const oldPid = Number(f.service.runtimeStatus()[0]!.channelId.split(":").at(-1));
    await until(async () => readFile(join(f.root, "starts"), "utf8").then(value => value === "start\n", () => false));
    process.kill(oldPid, "SIGKILL");
    await until(() => f.service.runtimeStatus().length === 0);
    const other = { ...f.attach, viewId: "other-independent-window" };
    const otherSnapshot = await f.service.request("extension.view.attach", other) as { epoch: string };
    await f.service.request("extension.view.restart", { viewId: f.attach.viewId });
    expect((await f.service.request("extension.view.attach", other) as { epoch: string }).epoch).toBe(otherSnapshot.epoch);
    expect((await f.service.status()).hosts).toBe(1);
    const attach = { ...f.attach, rendererId: "renderer-after-restart" };
    const restored = await f.service.request("extension.view.attach", attach) as { epoch: string };
    expect(restored.epoch).not.toBe(f.snapshot.epoch);
    await f.service.request("extension.view.send", { ...attach, epoch: restored.epoch, messageId: "new-task", payload: encode("task") });
    await until(() => f.service.runtimeStatus().length > 0);
    expect(Number(f.service.runtimeStatus()[0]!.channelId.split(":").at(-1))).not.toBe(oldPid);
    await until(async () => readFile(join(f.root, "starts"), "utf8").then(value => value === "start\nstart\n", () => false));
    await f.service.restart();
  });

  it("serializes targeted close after attach, closes absent windows idempotently and protects newer epochs", async () => {
    const f = await fixture();
    const second = { ...f.attach, viewId: "second-window", rendererId: "second-renderer" };
    const attaching = f.service.request("extension.view.attach", second);
    const closing = f.service.request("extension.view.close", { viewId: second.viewId });
    await attaching;
    expect(await closing).toMatchObject({ stopped: true });
    expect((await f.service.status()).hosts).toBe(1);
    expect(await f.service.request("extension.view.close", { viewId: second.viewId })).toEqual({ stopped: true });
    expect(await f.service.request("extension.view.close", { viewId: f.attach.viewId, epoch: "stale" })).toEqual({ stopped: false, epoch: f.snapshot.epoch });
    expect((await f.service.status()).hosts).toBe(1);
    // Detached or evicted renderers still have the same manageable Agent host.
    await f.service.request("extension.view.detach", f.attach);
    expect(await f.service.request("extension.view.close", { viewId: f.attach.viewId, epoch: f.snapshot.epoch })).toEqual({ stopped: true, epoch: f.snapshot.epoch });
    expect((await f.service.status()).hosts).toBe(0);
    const restored = await f.service.request("extension.view.attach", f.attach) as { epoch: string };
    expect(restored.epoch).not.toBe(f.snapshot.epoch);
    await f.service.restart();
  });

  it("keeps the child host alive after an asynchronous file watcher permission error", async () => {
    const f = await fixture();
    await f.send("watcher", encode("watcher-error"));
    await until(() => f.frames.some(frame => JSON.stringify(frame.payload) === JSON.stringify(encode("watcher-alive"))));
    expect(await readFile(join(f.root, "watcher-closed"), "utf8")).toBe("closed\n");
    expect(f.frames.some(frame => frame.type === "status")).toBe(false);
    await f.send("after-error", encode("normal reply"));
    await until(() => f.frames.some(frame => JSON.stringify(frame.payload).includes("normal reply")));
    const resumed = await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch }) as { epoch: string };
    expect(resumed.epoch).toBe(f.snapshot.epoch);
  });

  it("disposes watchers once and closes remaining watchers when the host stops", async () => {
    const f = await fixture();
    await f.send("watchers", encode("watcher-dispose"));
    await until(() => f.frames.some(frame => JSON.stringify(frame.payload) === JSON.stringify(encode("watcher-alive"))));
    expect(await readFile(join(f.root, "watcher-closed"), "utf8")).toBe("closed\n");
    await f.service.restart();
    expect(await readFile(join(f.root, "watcher-closed"), "utf8")).toBe("closed\nclosed\n");
  });

  it("reports a nonfatal host error without requesting a renderer reload and continues accepting actions", async () => {
    const f = await fixture();
    await f.send("failure", encode("recoverable-error"));
    await until(() => f.frames.some(frame => frame.type === "status"));
    expect(f.frames.find(frame => frame.type === "status")?.payload).toMatchObject({ state: "error", retryable: false, reloadRequired: false });
    await f.send("continued", encode("still running"));
    await until(() => f.frames.some(frame => JSON.stringify(frame.payload).includes("still running")));
    expect((await f.service.status()).hosts).toBe(1);
  });

  it("requests a renderer reload when the host actually exits", async () => {
    const f = await fixture();
    await f.send("exit", encode("exit-host"));
    await until(() => f.frames.some(frame => frame.type === "status"));
    expect(f.frames.find(frame => frame.type === "status")?.payload).toMatchObject({ state: "error", retryable: true, reloadRequired: true });
    expect((await f.service.status()).hosts).toBe(0);
  });

  it("forwards opaque values and byte arrays and deduplicates an accepted message", async () => {
    const f = await fixture(); expect(f.snapshot.html).toContain("Fixture");
    await f.send("first", encode("draft")); await f.send("first", encode("draft"));
    await until(() => f.frames.some(frame => frame.type === "message"));
    const replies = f.frames.filter(frame => frame.type === "message"); expect(replies).toHaveLength(1);
    expect(JSON.stringify(replies[0]?.payload)).toContain('"bytes","AIn/"');
    await expect(f.send("first", encode("different"))).rejects.toThrow("内容不同");
  });

  it("retains the process and unacknowledged output across a Gateway disconnect", async () => {
    const f = await fixture(); await f.send("task", encode("task"));
    await until(() => f.service.runtimeStatus().length > 0);
    const pid = f.service.runtimeStatus()[0]?.channelId;
    f.service.disconnected();
    await until(async () => readFile(join(f.root, "finished"), "utf8").then(value => value === "done", () => false));
    const snapshot = await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch, after: f.snapshot.sequence }) as { epoch: string };
    expect(snapshot.epoch).toBe(f.snapshot.epoch);
    expect(f.service.runtimeStatus()[0]?.channelId).toBe(pid);
    expect(await readFile(join(f.root, "starts"), "utf8")).toBe("start\n");
  });

  it("replays live-renderer messages, but isolates a newly created renderer", async () => {
    const f = await fixture(); f.service.disconnected();
    // Reattach the same page before asking for a reply, then disconnect before delivery.
    await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch, after: f.snapshot.sequence });
    await f.send("draft", encode("kept")); f.service.disconnected();
    await until(() => f.service.status().then(status => status.hosts === 1));
    await new Promise(resolve => setTimeout(resolve, 50));
    const replay = await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch, after: f.snapshot.sequence }) as { frames: ExtensionViewFrame[] };
    expect(replay.frames.some(frame => frame.type === "message")).toBe(true);
    const cold = await f.service.request("extension.view.attach", { ...f.attach, rendererId: "renderer-two" }) as { frames: ExtensionViewFrame[] };
    expect(cold.frames).toEqual([]);
    await expect(f.send("stale", encode("bad"))).rejects.toThrow("接管");
  });

  it("routes a native public host request and response without interpreting plugin messages", async () => {
    const f = await fixture(); await f.send("clip", encode("clipboard"));
    await until(() => f.frames.some(frame => frame.type === "host-request"));
    const request = f.frames.find(frame => frame.type === "host-request")?.payload as { id: string; method: string };
    expect(request.method).toBe("clipboard.read");
    await f.service.request("extension.view.respond", { ...f.attach, id: request.id, result: "native text" });
    await until(() => f.frames.some(frame => JSON.stringify(frame.payload) === JSON.stringify(encode("native text"))));
  });

  it("isolates panel messages and HTML while retaining the main chat and other native requests", async () => {
    const f = await fixture();
    const snapshot = () => f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch }) as Promise<{
      epoch: string; html: string; surfaces: ExtensionSurface[]; frames: ExtensionViewFrame[]; pendingHostRequests: Array<{ id: string; method: string; surfaceId: string }>;
    }>;
    await f.send("open-panels", encode("panels"));
    await until(() => f.frames.filter(frame => frame.type === "html" && frame.surfaceId !== "main").length === 2);
    const opened = await snapshot();
    expect(opened.html).toBe(f.snapshot.html);
    expect(opened.surfaces).toEqual([
      expect.objectContaining({ title: "Settings panel", kind: "panel", html: "<!doctype html><title>Settings</title>" }),
      expect.objectContaining({ title: "Other panel", kind: "panel", html: "<!doctype html><title>Other</title>" }),
    ]);
    const settings = opened.surfaces[0]!;
    const other = opened.surfaces[1]!;
    expect(settings.id).not.toBe(other.id);
    const panelSend = (messageId: string, value: string, surfaceId = settings.id) => f.service.request("extension.view.send", {
      ...f.attach, epoch: f.snapshot.epoch, messageId, surfaceId, payload: encode(value),
    });
    await panelSend("panel-only", "hello");
    await until(() => f.frames.some(frame => frame.type === "message" && frame.surfaceId === settings.id && JSON.stringify(frame.payload) === JSON.stringify(encode("Settings:hello"))));
    expect(f.frames.some(frame => frame.type === "message" && frame.surfaceId === other.id)).toBe(false);
    await f.send("main-only", encode("main hello"));
    await until(() => f.frames.some(frame => frame.type === "message" && frame.surfaceId === "main" && JSON.stringify(frame.payload).includes("main hello")));
    await expect(panelSend("main-only", "main hello")).rejects.toThrow("内容不同");

    await f.send("main-clipboard", encode("clipboard"));
    await panelSend("panel-clipboard", "clipboard");
    await until(() => f.frames.filter(frame => frame.type === "host-request").length === 2);
    const withPending = await snapshot();
    const mainRequest = withPending.pendingHostRequests.find(request => request.surfaceId === "main")!;
    const panelRequest = withPending.pendingHostRequests.find(request => request.surfaceId === settings.id)!;
    expect(mainRequest.method).toBe("clipboard.read");
    expect(panelRequest.method).toBe("clipboard.read");
    expect(f.frames.find(frame => frame.type === "host-request" && (frame.payload as { id: string }).id === panelRequest.id)?.surfaceId).toBe(settings.id);

    await f.service.request("extension.view.surface.close", { ...f.attach, surfaceId: settings.id });
    await until(() => f.frames.some(frame => frame.type === "surface" && frame.surfaceId === settings.id && (frame.payload as { action: string }).action === "close"));
    const closed = await snapshot();
    expect(closed.epoch).toBe(f.snapshot.epoch);
    expect(closed.html).toBe(f.snapshot.html);
    expect(closed.surfaces.map(surface => surface.id)).toEqual([other.id]);
    expect(closed.pendingHostRequests).toEqual([mainRequest]);
    expect(closed.frames.some(frame => frame.type === "host-request" && frame.surfaceId === settings.id)).toBe(false);
    expect(closed.frames.some(frame => frame.type === "surface" && frame.surfaceId === settings.id && (frame.payload as { action: string }).action === "close")).toBe(true);
    await f.service.request("extension.view.respond", { ...f.attach, id: mainRequest.id, result: "main clipboard survives" });
    await until(() => f.frames.some(frame => frame.type === "message" && frame.surfaceId === "main" && JSON.stringify(frame.payload) === JSON.stringify(encode("main clipboard survives"))));
    expect((await snapshot()).frames.some(frame => frame.type === "host-request" && (frame.payload as { id: string }).id === mainRequest.id)).toBe(false);
    await expect(panelSend("closed-panel", "bad")).rejects.toThrow("已关闭");
    await expect(f.service.request("extension.view.surface.close", { ...f.attach, surfaceId: "main" })).rejects.toThrow("主聊天");
    await panelSend("other-survives", "hello", other.id);
    await until(() => f.frames.some(frame => frame.type === "message" && frame.surfaceId === other.id && JSON.stringify(frame.payload) === JSON.stringify(encode("Other:hello"))));
  });

  it("does not reopen a panel or its native dialog when the panel closes while offline", async () => {
    const f = await fixture();
    await f.send("open-panels", encode("panels"));
    await until(() => f.frames.filter(frame => frame.type === "html" && frame.surfaceId !== "main").length === 2);
    const before = await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch }) as { surfaces: ExtensionSurface[] };
    const panelId = before.surfaces[0]!.id;
    await f.service.request("extension.view.send", { ...f.attach, epoch: f.snapshot.epoch, surfaceId: panelId, messageId: "offline-dialog", payload: encode("clipboard") });
    await until(() => f.frames.some(frame => frame.type === "host-request" && frame.surfaceId === panelId));
    await f.send("schedule-close", encode("close-settings-later"));
    f.service.disconnected();
    await until(() => readFile(join(f.root, "panel-closed"), "utf8").then(value => value === "closed", () => false));
    const resumed = await f.service.request("extension.view.attach", { ...f.attach, epoch: f.snapshot.epoch }) as {
      html: string; surfaces: ExtensionSurface[]; frames: ExtensionViewFrame[]; pendingHostRequests: unknown[];
    };
    expect(resumed.html).toBe(f.snapshot.html);
    expect(resumed.surfaces.map(surface => surface.title)).toEqual(["Other panel"]);
    expect(resumed.pendingHostRequests).toEqual([]);
    expect(resumed.frames.some(frame => frame.type === "host-request")).toBe(false);
    expect(resumed.frames.filter(frame => frame.type === "surface")).toEqual([
      expect.objectContaining({ surfaceId: panelId, payload: expect.objectContaining({ action: "close" }) }),
    ]);
  });

  it("reads a text attachment and opens the native read-only preview through public APIs", async () => {
    const f = await fixture(); await f.send("preview", encode("preview"));
    await until(() => f.frames.some(frame => frame.type === "host-request"));
    const request = f.frames.find(frame => frame.type === "host-request")?.payload as { id: string; method: string; params: { path: string } };
    expect(request.method).toBe("openFile");
    expect(request.params.path).toContain("webview/index.html");
    expect(request.params).not.toHaveProperty("selection");
    await f.service.request("extension.view.respond", { ...f.attach, id: request.id, result: true });
    await until(() => f.frames.some(frame => frame.type === "message" && JSON.stringify(frame.payload).includes("<!doctype html>")));
  });

  it("keeps the current package and host if the candidate cannot activate", async () => {
    const f = await fixture();
    await writeFile(join(f.extension, "package.json"), JSON.stringify({ publisher: "OpenAI", name: "chatgpt", version: "2.0.0", main: "extension.cjs" }));
    await writeFile(join(f.extension, "extension.cjs"), "exports.activate=()=>{throw Error('Unsupported public API')}");
    await f.store.importDirectory(f.extension);
    expect(await f.service.request("extension.apply", {})).toMatchObject({ confirmationRequired: true });
    await expect(f.service.request("extension.apply", { confirmed: true })).rejects.toThrow("Unsupported public API");
    expect(await f.service.status()).toMatchObject({ version: "1.0.0", stagedVersion: "2.0.0", hosts: 1 });
    await f.send("still-alive", encode("yes"));
    await until(() => f.frames.some(frame => frame.type === "message"));
  });
});
