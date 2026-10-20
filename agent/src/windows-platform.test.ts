import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { PassThrough } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AgentService } from "./agent-service.js";
import { loadAgentConfig } from "./config.js";
import { DesktopControl, redactToken } from "./desktop-control.js";
import { resolveExtensionHostDirectory, resolveNodeExecutable } from "./node-runtime.js";
import { officialExtensionTarget } from "./official-extension-store.js";
import { FileService } from "./file-service.js";
import { isNativeAbsolutePath } from "./native-path.js";

const cleanups: Array<() => void | Promise<void>> = [];
afterEach(async () => { vi.unstubAllEnvs(); for (const cleanup of cleanups.splice(0).reverse()) await cleanup(); });
async function directory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), "codex-windows-中文 "));
  cleanups.push(() => rm(path, { recursive: true, force: true }));
  return path;
}

describe("Windows packaging and desktop control", () => {
  it("rejects paths that rely on the Windows current drive", () => {
    for (const path of ["C:\\", "D:/项目 a", "\\\\server\\share", "//server/share/path"]) expect(isNativeAbsolutePath(path, "win32")).toBe(true);
    for (const path of ["C:relative", "\\relative", "/relative", "\\\\server", "C:\\bad\0name"]) expect(isNativeAbsolutePath(path, "win32")).toBe(false);
    expect(isNativeAbsolutePath("/home/项目 a", "linux")).toBe(true);
    expect(isNativeAbsolutePath("relative", "linux")).toBe(false);
  });
  it("selects native Marketplace packages and never falls back to a Linux package", () => {
    expect(officialExtensionTarget("linux", "x64")).toBe("linux-x64");
    expect(officialExtensionTarget("linux", "arm64")).toBe("linux-arm64");
    expect(officialExtensionTarget("win32", "x64")).toBe("win32-x64");
    expect(() => officialExtensionTarget("darwin", "x64")).toThrow("darwin-x64");
    expect(() => officialExtensionTarget("win32", "ia32")).toThrow("win32-ia32");
  });

  it("waits for its owner to assign the Job, emits only JSONL, and stops once", async () => {
    const input = new PassThrough(), output = new PassThrough();
    const stop = vi.fn();
    const control = new DesktopControl(stop, input, output);
    let text = "", started = false;
    output.on("data", data => { text += String(data); });
    void control.started.then(value => { started = value; });
    input.write('garbage\n{"type":"ignored"}\n');
    await Promise.resolve();
    expect(started).toBe(false);
    input.write('{"type":"start"}\n');
    expect(await control.started).toBe(true);
    control.status("connecting"); control.status("connected", "已连接");
    expect(text.trim().split("\n").map(line => JSON.parse(line))).toEqual([
      { type: "status", state: "connecting" }, { type: "status", state: "connected", message: "已连接" },
    ]);
    input.write('{"type":"stop"}\n'); input.end();
    await new Promise(resolve => setImmediate(resolve));
    expect(stop).toHaveBeenCalledTimes(1);
    control.close();
  });

  it("does not start when the owner disappears before the start command", async () => {
    const input = new PassThrough(), stop = vi.fn();
    const control = new DesktopControl(stop, input, new PassThrough());
    input.end();
    expect(await control.started).toBe(false);
    expect(stop).toHaveBeenCalledTimes(1);
    control.close();
  });

  it("redacts literal tokens rather than interpreting them as regular expressions", () => {
    expect(redactToken("error secret.$* secret.$*", "secret.$*")).toBe("error [redacted] [redacted]");
  });

  it("stops a real desktop Agent promptly during reconnect backoff without leaking credentials", async () => {
    const root = await directory();
    const server = createServer((_request, response) => { response.writeHead(401); response.end(); });
    server.listen(0, "127.0.0.1"); await once(server, "listening");
    cleanups.push(() => new Promise<void>(resolve => server.close(() => resolve())));
    const token = "desktop-credential-do-not-log";
    const configPath = join(root, "agent.json");
    await writeFile(configPath, JSON.stringify({ version: 1, gatewayUrl: `http://127.0.0.1:${(server.address() as { port: number }).port}`, token, name: "Windows 测试", serviceId: "desktop-test", codexExecutable: process.execPath }));
    const child = spawn(process.execPath, ["--import", "tsx", resolve(import.meta.dirname, "index.ts"), "--desktop-control", "--config", configPath], { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    cleanups.push(() => { if (child.exitCode === null) child.kill("SIGKILL"); });
    const exited = once(child, "exit");
    let stdout = "", stderr = "", attempts = 0;
    child.stdout.on("data", chunk => { stdout += String(chunk); });
    child.stderr.on("data", chunk => { stderr += String(chunk); });
    const ready = new Promise<void>(resolve => server.on("request", () => { if (++attempts === 3) resolve(); }));
    await new Promise(resolve => setTimeout(resolve, 100));
    expect(attempts).toBe(0);
    child.stdin.write('{"type":"start"}\n');
    await Promise.race([ready, exited.then(([code]) => { throw new Error(`Desktop Agent exited ${String(code)}: ${stderr}`); })]);
    await new Promise(resolve => setTimeout(resolve, 100));
    const before = Date.now();
    child.stdin.write('{"type":"stop"}\n');
    const [code] = await exited;
    expect(code).toBe(0);
    expect(Date.now() - before).toBeLessThan(2_000);
    expect(stdout + stderr).not.toContain(token);
    const statuses = stdout.trim().split("\n").map(line => JSON.parse(line));
    expect(statuses.every(status => status.type === "status")).toBe(true);
    expect(statuses.at(-1)).toEqual({ type: "status", state: "stopped" });
  }, 15_000);
});

describe.runIf(process.platform === "win32")("native Windows Agent", () => {
  it("accepts desktop configuration without changing the stable service identity", async () => {
    const root = await directory(), configPath = join(root, "agent.json");
    const argv = ["--desktop-control", "--gateway", "https://gateway.example", "--token", "test", "--name", "PC", "--codex", process.execPath, "--config", configPath];
    const first = await loadAgentConfig(argv, {}), second = await loadAgentConfig(argv, {});
    expect(second.serviceId).toBe(first.serviceId);
    expect(JSON.parse(await readFile(configPath, "utf8")).codexExecutable).toBe(process.execPath);
    await expect(loadAgentConfig(argv.map(value => value === process.execPath ? "C:\\Program Files\\Codex\\codex.cmd" : value), {})).rejects.toThrow(".exe");
  });

  it("uses real Node IPC from a path containing spaces and Chinese characters", async () => {
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", process.execPath);
    expect(await resolveNodeExecutable()).toBe(process.execPath);
    expect(await resolveExtensionHostDirectory()).toContain("extension-host");
    const root = await directory(), script = join(root, "IPC 测试.cjs");
    await writeFile(script, "process.on('message', value => { process.send(value); process.disconnect(); });");
    const child = spawn(await resolveNodeExecutable(), [script], { stdio: ["ignore", "pipe", "pipe", "ipc"], windowsHide: true });
    const payload = { text: "中文🙂", path: root };
    const response = once(child, "message"); child.send(payload);
    expect((await response)[0]).toEqual(payload);
    await once(child, "exit");
  });

  it("declares no terminal and rejects all terminal requests and notifications", async () => {
    const service = new AgentService(process.execPath, await directory());
    try {
      const metadata = await service.request("service.describe", {}) as { platform: string; capabilities: string[] };
      expect(metadata.platform).toBe("win32"); expect(metadata.capabilities).not.toContain("terminal");
      await expect(service.request("terminal.open", {})).rejects.toThrow("暂不支持终端");
      await expect(service.notification("terminal.write", {})).rejects.toThrow("暂不支持终端");
      await expect(service.request("codex.runtime.update", {})).rejects.toThrow("Windows");
      const status = await service.request("codex.runtime.status", {}) as { updateSupported: boolean; latestVersion: string | null };
      expect(status.updateSupported).toBe(false); expect(status.latestVersion).toBeNull();
    } finally { service.close(); }
  });

  it("keeps native paths unchanged through file listing, upload and download", async () => {
    const root = await directory(), target = join(root, "nested 文件", "image 文件.txt"), files = new FileService();
    await files.mkdir(dirname(target));
    const bytes = Buffer.from("中文🙂\n");
    files.openUpload(target, "fixture"); files.writeUpload("fixture", bytes); files.endUpload("fixture");
    expect(await files.finishUpload("fixture")).toEqual({ path: target, size: bytes.length });
    expect(await files.downloadStat(target)).toEqual({ name: "image 文件.txt", size: bytes.length });
    const contents: Buffer[] = []; for await (const chunk of files.openDownload(target)) contents.push(chunk as Buffer);
    expect(Buffer.concat(contents)).toEqual(bytes);
    expect(await files.list(dirname(target))).toEqual([expect.objectContaining({ name: "image 文件.txt", path: target })]);
    await expect(files.list("C:relative")).rejects.toThrow("绝对路径");
    await expect(files.list("C:\\bad\0path")).rejects.toThrow("绝对路径");
  });
});
