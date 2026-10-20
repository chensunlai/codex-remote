import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OfficialExtensionStore } from "./official-extension-store.js";
import { OfficialExtensionService, type ExtensionViewFrame } from "./official-extension-service.js";
import { superviseWindowsHost, type HostSupervisor } from "./windows-host-supervisor.js";

const native = process.platform === "win32";
const supervised = native && !!process.env.CODEX_REMOTE_WINDOWS_SUPERVISOR;
const cleanups: Array<() => void | Promise<void>> = [];
afterEach(async () => { vi.unstubAllEnvs(); for (const cleanup of cleanups.splice(0).reverse()) await cleanup(); });
async function directory(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "codex-jobs 中文 "));
  cleanups.push(() => rm(root, { recursive: true, force: true })); return root;
}
function alive(pid: number): boolean { try { process.kill(pid, 0); return true; } catch { return false; } }
async function until(check: () => boolean | Promise<boolean>, timeout = 10_000): Promise<void> {
  const start = Date.now();
  while (!await check()) { if (Date.now() - start > timeout) throw new Error("Timed out"); await new Promise(resolve => setTimeout(resolve, 25)); }
}
async function idleHost(): Promise<ChildProcess> {
  const host = spawn(process.execPath, ["-e", "process.on('message', value => { if(value==='exit')process.exit(0); else {const child=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore',windowsHide:true}); child.once('spawn',()=>process.send({pid:child.pid}));} });"], { stdio: ["ignore", "pipe", "pipe", "ipc"], windowsHide: true });
  cleanups.push(() => { if (host.exitCode === null) host.kill("SIGKILL"); });
  await once(host, "spawn"); return host;
}

describe.runIf(supervised)("native Windows host Job cleanup", () => {
  it.each(["exit", "kill", "timeout"])("cleans descendants on %s without touching unrelated Codex/Node processes", async mode => {
    const unrelated = spawn(process.execPath, ["-e", "setInterval(()=>{},1000)"], { stdio: "ignore", windowsHide: true });
    cleanups.push(() => { unrelated.kill("SIGKILL"); }); await once(unrelated, "spawn");
    const host = await idleHost();
    const supervisor = await superviseWindowsHost(host) as HostSupervisor;
    cleanups.push(() => supervisor.close());
    const spawned = once(host, "message"); host.send("spawn");
    const pid = ((await spawned)[0] as { pid: number }).pid;
    expect(alive(pid)).toBe(true);
    const exited = once(host, "exit");
    if (mode === "exit") host.send("exit");
    else if (mode === "kill") host.kill("SIGKILL");
    else await supervisor.close();
    await exited; await supervisor.close();
    await until(() => !alive(pid));
    expect(alive(unrelated.pid!)).toBe(true);
  }, 20_000);

  it("does not activate the host if the Job helper fails to start", async () => {
    const host = await idleHost(), exited = once(host, "exit");
    vi.stubEnv("CODEX_REMOTE_WINDOWS_SUPERVISOR", process.execPath);
    await expect(superviseWindowsHost(host)).rejects.toThrow("监护");
    await exited; expect(alive(host.pid!)).toBe(false);
  });
});

describe.runIf(supervised)("native Windows official-host public lifecycle", () => {
  it("activates a complete extension through real Node IPC and waits for Job cleanup before replacement", async () => {
    const root = await directory(), source = join(root, "source");
    await mkdir(join(source, "webview"), { recursive: true });
    await writeFile(join(source, "webview", "index.html"), "<!doctype html><title>Windows</title>");
    await writeFile(join(source, "package.json"), JSON.stringify({ publisher: "OpenAI", name: "chatgpt", version: "1.0.0", main: "extension.cjs" }));
    await writeFile(join(source, "extension.cjs"), `const v=require('vscode');exports.activate=ctx=>{ctx.subscriptions.push(v.window.registerWebviewViewProvider('chatgpt.sidebarView',{resolveWebviewView(view){view.webview.html='<!doctype html><title>Windows 中文</title>';const child=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore',windowsHide:true});child.once('spawn',()=>view.webview.postMessage({pid:child.pid}));}}));};`);
    const store = new OfficialExtensionStore(join(root, "packages")); await store.importDirectory(source); await store.activate();
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", process.execPath);
    const frames: ExtensionViewFrame[] = [], service = new OfficialExtensionService(join(root, "state"), process.execPath, frame => frames.push(frame), store);
    cleanups.push(async () => { await service.restart(); service.close(); });
    const attach = { viewId: "windows-public-host", rendererId: "windows", workspace: root };
    const first = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
    expect(first.html).toContain("Windows 中文");
    await until(() => frames.some(frame => frame.type === "message"));
    const serialized = JSON.stringify(frames.filter(frame => frame.type === "message"));
    const pid = Number(/\["pid",\["number",(\d+)\]\]/.exec(serialized)?.[1]);
    expect(pid).toBeGreaterThan(0); expect(alive(pid)).toBe(true);
    await service.restart(); expect(alive(pid)).toBe(false);
    const second = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
    expect(second.epoch).not.toBe(first.epoch); expect(second.html).toBe(first.html);
  }, 30_000);
});

describe.runIf(supervised && !!process.env.CODEX_REMOTE_TEST_CODEX_EXE)("complete official Windows VSIX activation", () => {
  it("loads the unmodified official extension with the installed Windows CLI and can restart its host", async () => {
    const root = await directory(), source = process.env.CODEX_REMOTE_TEST_OFFICIAL_EXTENSION, cli = process.env.CODEX_REMOTE_TEST_CODEX_EXE!;
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", process.execPath);
    const store = new OfficialExtensionStore(join(root, "packages"));
    if (source) await store.importDirectory(source);
    else {
      await store.update();
      await until(async () => (await store.status()).operation?.state !== "running", 150_000);
      const status = await store.status();
      if (status.operation?.state === "failed") throw new Error(status.operation.error);
    }
    const installed = await store.activate();
    const digest = async (): Promise<string> => createHash("sha256").update(await readFile(join(installed.directory, installed.manifest.main))).digest("hex");
    const before = await digest();
    const frames: ExtensionViewFrame[] = [], service = new OfficialExtensionService(join(root, "state"), cli, frame => frames.push(frame), store);
    cleanups.push(async () => { await service.restart(); service.close(); });
    const attach = { viewId: "official-windows-smoke", rendererId: "windows", workspace: root };
    const first = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
    expect(first.html).toMatch(/<html|<!doctype/i);
    expect(frames.filter(frame => frame.type === "status" && (frame.payload as { state?: string }).state === "error")).toEqual([]);
    await service.restart();
    const second = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
    expect(second.epoch).not.toBe(first.epoch); expect(second.html).toMatch(/<html|<!doctype/i);
    expect(await digest()).toBe(before);
    // No model request or login is performed; active-conversation synchronization
    // remains a separate manual acceptance check on a signed-in Windows machine.
  }, 240_000);
});
