import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OfficialExtensionService } from "./official-extension-service.js";
import { OfficialExtensionStore } from "./official-extension-store.js";

const roots: string[] = [];
const services: OfficialExtensionService[] = [];
const ownedPids = new Set<number>();
afterEach(async () => {
  for (const service of services.splice(0)) { await service.restart().catch(() => {}); service.close(); }
  for (const pid of ownedPids) { try { process.kill(pid, "SIGKILL"); } catch {} }
  ownedPids.clear();
  for (const directory of roots.splice(0)) await rm(directory, { recursive: true, force: true });
});

async function fixture(mode: "ignore-term" | "fast-exit") {
  const root = await mkdtemp(join(tmpdir(), "official-host-review-")); roots.push(root);
  const extension = join(root, "source"); await mkdir(extension);
  await writeFile(join(extension, "package.json"), JSON.stringify({ publisher: "openai", name: "chatgpt", version: "1.0.0", main: "extension.cjs" }));
  await writeFile(join(extension, "extension.cjs"), `const vscode=require('vscode');
exports.activate=context=>{context.subscriptions.push(vscode.window.registerWebviewViewProvider('chatgpt.sidebarView',{resolveWebviewView(view){
view.webview.html='<!doctype html><title>Lifecycle fixture</title>';
view.webview.onDidReceiveMessage(message=>{if(message==='start')require('node:child_process').spawn(vscode.workspace.getConfiguration('chatgpt').get('cliExecutable'),['app-server'],{stdio:'ignore'});});
}}));};`);
  const executable = join(root, "codex");
  const pidFile = join(root, "pid");
  await writeFile(executable, `#!/usr/bin/env node
const fs=require('node:fs');
if(process.argv.includes('--version')){setTimeout(()=>console.log('codex-cli 1.0.0'),350);return;}
if(${JSON.stringify(mode)}==='ignore-term'){process.on('SIGTERM',()=>{});setInterval(()=>{},1000);}
else setTimeout(()=>process.exit(0),20);
fs.writeFileSync(${JSON.stringify(pidFile)},String(process.pid));
`, { mode: 0o755 });
  const store = new OfficialExtensionStore(join(root, "store")); await store.importDirectory(extension); await store.activate();
  const service = new OfficialExtensionService(join(root, "state"), executable, () => {}, store); services.push(service);
  const identity = { viewId: "lifecycle-review", rendererId: "renderer", workspace: root };
  const snapshot = await service.request("extension.view.attach", identity) as { epoch: string };
  await service.request("extension.view.send", { ...identity, epoch: snapshot.epoch, messageId: "start", payload: ["string", "start"] });
  let pid = 0;
  await vi.waitFor(async () => { pid = Number(await readFile(pidFile, "utf8")); expect(pid).toBeGreaterThan(0); });
  ownedPids.add(pid);
  return { service, pid, root };
}

async function running(pid: number): Promise<boolean> {
  try {
    process.kill(pid, 0);
    const processStat = await readFile(`/proc/${pid}/stat`, "utf8");
    return processStat.slice(processStat.lastIndexOf(")") + 2).charAt(0) !== "Z";
  } catch { return false; }
}

describe("extension host process cleanup regressions", () => {
  it("serializes an in-flight attach before an explicit restart", async () => {
    const { service, root } = await fixture("fast-exit");
    const attaching = service.request("extension.view.attach", { viewId: "second-view", rendererId: "second-renderer", workspace: root });
    // Let attach enter its asynchronous filesystem checks before requesting restart.
    await Promise.resolve();
    const restarting = service.restart();
    await attaching;
    await restarting;
    expect((await service.status()).hosts).toBe(0);
  });

  it("kills an owned app-server that ignores SIGTERM when explicitly restarting", async () => {
    const { service, pid } = await fixture("ignore-term");
    expect(await running(pid)).toBe(true);
    await service.request("extension.view.restart", { viewId: "lifecycle-review" });
    await vi.waitFor(async () => expect(await running(pid)).toBe(false), { timeout: 2000 });
  });

  it("does not report a dead app-server when its version probe finishes after exit", async () => {
    const { service, pid } = await fixture("fast-exit");
    await vi.waitFor(async () => expect(await running(pid)).toBe(false));
    await new Promise(resolve => setTimeout(resolve, 700));
    expect(service.runtimeStatus()).toEqual([]);
  });
});
