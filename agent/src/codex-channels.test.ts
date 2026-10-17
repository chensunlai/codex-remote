import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { CodexChannels, parseCodexVersion, type ChannelFrame, type RawRpc } from "./codex-channels.js";
import { CodexRuntimeControl } from "./codex-runtime-control.js";

interface Ready {
  channelId: string;
  epoch: string;
  sequence: number;
  initializeResult: unknown;
  frames: ChannelFrame[];
  pendingRequests: RawRpc[];
  runningVersion: string;
  resyncRequired: boolean;
}

const cleanup: Array<() => Promise<void>> = [];
afterEach(async () => {
  for (const close of cleanup.splice(0)) await close();
});

async function fixture(options: ConstructorParameters<typeof CodexChannels>[2] = {}, initializeVersion?: string, shutdownDelayMs = 0) {
  const directory = await mkdtemp(join(tmpdir(), "codex-raw-channel-"));
  const executable = join(directory, "codex");
  const versionPath = join(directory, "version");
  const log = join(directory, "rpc.jsonl");
  const updates = join(directory, "updates");
  await writeFile(versionPath, "1.0.0");
  await writeFile(executable, `#!/usr/bin/env node
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
const versionPath = ${JSON.stringify(versionPath)};
const log = ${JSON.stringify(log)};
const updates = ${JSON.stringify(updates)};
const initializeVersion = ${JSON.stringify(initializeVersion ?? null)};
const args = process.argv.slice(2);
const processHandles = new Set();
const send = (value) => process.stdout.write(JSON.stringify(value) + "\\n");
if (args[0] === "--version") { console.log("codex-cli " + readFileSync(versionPath, "utf8")); process.exit(0); }
if (args[0] === "--help") { console.log("Commands:\\n  update  Update CLI\\n  app-server  Server"); process.exit(0); }
if (args[0] === "update") {
  appendFileSync(updates, "update\\n");
  console.log("updating");
  setTimeout(() => { writeFileSync(versionPath, "1.1.0"); process.exit(0); }, 50);
} else if (args.join(" ") === "app-server --stdio") {
  if (${shutdownDelayMs} > 0) process.on("SIGTERM", () => setTimeout(() => process.exit(0), ${shutdownDelayMs}));
  createInterface({ input: process.stdin }).on("line", (line) => {
    appendFileSync(log, line + "\\n");
    const message = JSON.parse(line);
    if (message.method === "initialize") {
      if (initializeVersion !== null) writeFileSync(versionPath, initializeVersion);
      send({ id: message.id, result: { userAgent: "fake-codex", custom: true } });
    }
    else if (message.method === "initialized") {}
    else if (message.method === "test.echo") send({ id: message.id, result: message, extra: "preserved" });
    else if (message.method === "test.fail") send({ id: message.id, error: { code: -32010, message: "original", data: { nested: true } }, extra: 123 });
    else if (message.method === "test.never") {}
    else if (message.method === "process/spawn") {
      const handle = message.params.processHandle;
      if (processHandles.has(handle) || handle === "fail") send({ id: message.id, error: { code: -32001, message: "spawn rejected" } });
      else { processHandles.add(handle); send({ id: message.id, result: {} }); }
    }
    else if (message.method === "test.delay") setTimeout(() => send({ id: message.id, result: message.params.value }), message.params.ms);
    else if (message.method === "test.emit") { send({ id: message.id, result: {} }); send(message.params); }
    else if (message.method === "turn/start") {
      send({ id: message.id, result: { turn: { id: "turn-1", status: "inProgress" } } });
      send({ method: "turn/started", params: { turn: { id: "turn-1" } } });
    } else if (!message.method) send({ method: "test.response", params: message });
    else if (message.id !== undefined) send({ id: message.id, result: {} });
  });
} else process.exit(2);
`);
  await chmod(executable, 0o755);
  const events: ChannelFrame[] = [];
  const channels = new CodexChannels(executable, (frame) => events.push(frame), options);
  cleanup.push(async () => { channels.close(); await rm(directory, { recursive: true, force: true }); });
  const attach = async (sessionId = "session-1", extra: Partial<Ready> = {}) => channels.attach({
    channelId: "channel", sessionId,
    ...(extra.epoch !== undefined ? { epoch: extra.epoch } : {}),
    ...(extra.sequence !== undefined ? { afterSequence: extra.sequence } : {}),
  }) as Promise<Ready>;
  let nextMessage = 0;
  const send = (ready: Ready, message: RawRpc, sessionId = "session-1", messageId = `message-${++nextMessage}`) => channels.send({
    channelId: "channel", sessionId, epoch: ready.epoch, messageId, message,
  });
  const rpc = () => events.filter((frame) => frame.type === "rpc").map((frame) => frame.message as RawRpc);
  return { channels, events, attach, send, rpc, executable, versionPath, updates, log };
}

describe("Agent-owned raw Codex channels", () => {
  it("keeps process/spawn handles alive offline and requires restart confirmation until process/exited", async () => {
    const f = await fixture({ idleMs: 25 });
    const ready = await f.attach();
    await f.send(ready, { id: 1, method: "process/spawn", params: { processHandle: "background", command: ["sleep", "120"], cwd: "/" } });
    await eventually(() => f.rpc().some((message) => message.id === 1));
    await f.send(ready, { id: 2, method: "process/spawn", params: { processHandle: "background", command: ["duplicate"], cwd: "/" } });
    await eventually(() => f.rpc().some((message) => message.id === 2 && message.error !== undefined));
    f.channels.disconnected();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(f.channels.status()).toEqual([{ channelId: "channel", version: "1.0.0", active: true, pendingRequests: 0 }]);
    const control = new CodexRuntimeControl(f.executable, f.channels);
    expect(control.restart(false)).toMatchObject({ confirmationRequired: true });
    const restored = await f.attach("session-1", { epoch: ready.epoch, sequence: 0 });
    await f.send(restored, { id: 3, method: "test.emit", params: { method: "process/exited", params: { processHandle: "background", exitCode: 0 } } });
    await eventually(() => f.channels.status()[0]?.active === false);
    await f.send(restored, { id: 4, method: "process/spawn", params: { processHandle: "fail", command: ["missing"], cwd: "/" } });
    await eventually(() => f.rpc().some((message) => message.id === 4 && message.error !== undefined));
    expect(f.channels.status()[0]?.active).toBe(false);
    f.channels.disconnected();
    await eventually(() => f.channels.status().length === 0);
  });

  it("never respawns an app-server when the Agent closes during a restart", async () => {
    const f = await fixture({}, undefined, 50);
    await f.attach();
    const restarting = f.channels.restart();
    f.channels.close();
    await restarting;
    expect(f.channels.status()).toEqual([]);
    const requests = (await readFile(f.log, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
    expect(requests.filter((message) => message.method === "initialize")).toHaveLength(1);
    await expect(f.attach()).rejects.toThrow("管理器已关闭");
  });

  it("reports an unknown running version when the executable changes during initialization", async () => {
    const f = await fixture({}, "2.0.0");
    const ready = await f.attach();
    expect(ready.runningVersion).toBeNull();
    expect(f.channels.status()).toEqual([{ channelId: "channel", version: null, active: false, pendingRequests: 0 }]);
    await f.channels.restart();
    const restarted = await f.attach("session-1", { epoch: ready.epoch, sequence: 0 });
    expect(restarted.runningVersion).toBe("2.0.0");
  });

  it("preserves RPC errors and extension fields, initializes only once, and deduplicates writes", async () => {
    const f = await fixture();
    const ready = await f.attach();
    expect(ready.runningVersion).toBe("1.0.0");
    expect(ready.initializeResult).toEqual({ userAgent: "fake-codex", custom: true });
    const request = { id: 42, method: "test.echo", params: { unknown: [1, 2] }, future: true };
    await f.send(ready, request, "session-1", "same");
    await expect(f.send(ready, request, "session-1", "same")).resolves.toMatchObject({ duplicate: true });
    await f.send(ready, { id: "failure", method: "test.fail" });
    await f.send(ready, { id: "init", method: "initialize", params: { clientInfo: {} } });
    await eventually(() => f.rpc().some((message) => message.id === "failure"));
    expect(f.rpc().find((message) => message.id === 42)).toMatchObject({
      id: 42, result: { params: { unknown: [1, 2] }, future: true }, extra: "preserved",
    });
    expect(f.rpc().find((message) => message.id === "failure")).toEqual({
      id: "failure", error: { code: -32010, message: "original", data: { nested: true } }, extra: 123,
    });
    expect(f.rpc().find((message) => message.id === "init")).toEqual({ id: "init", result: ready.initializeResult });
    const lines = (await readFile(f.log, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
    expect(lines.filter((message) => message.method === "initialize")).toHaveLength(1);
    expect(lines.filter((message) => message.method === "test.echo")).toHaveLength(1);
    await expect(f.send(ready, { ...request, future: false }, "session-1", "same")).rejects.toThrow("不同消息");
  });

  it("keeps active work and approvals while detached and restores events without replaying submissions", async () => {
    const f = await fixture({ idleMs: 40 });
    const ready = await f.attach();
    await f.send(ready, { id: 1, method: "turn/start", params: {} });
    const approval = { id: "approve-1", method: "item/commandExecution/requestApproval", params: { command: "echo hi" }, extra: true };
    await f.send(ready, { id: 2, method: "test.emit", params: approval });
    await eventually(() => f.rpc().some((message) => message.id === "approve-1"));
    const lastSequence = f.events.at(-1)!.sequence;
    f.channels.ack({ channelId: "channel", sessionId: "session-1", sequence: lastSequence });
    f.channels.detach({ channelId: "channel", sessionId: "session-1" });
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(f.channels.status()).toEqual([{ channelId: "channel", version: "1.0.0", active: true, pendingRequests: 1 }]);
    const restored = await f.attach("session-1", { epoch: ready.epoch, sequence: lastSequence });
    expect(restored.resyncRequired).toBe(false);
    expect(restored.pendingRequests).toEqual([approval]);
    expect(restored.frames).toEqual([]);
    await f.send(restored, { id: "approve-1", result: { decision: "accept" } });
    await f.send(restored, { id: 3, method: "test.emit", params: { method: "turn/completed", params: { turn: { id: "turn-1", status: "completed" } } } });
    await eventually(() => !f.channels.status()[0]?.active);
    f.channels.detach({ channelId: "channel", sessionId: "session-1" });
    await eventually(() => f.channels.status().length === 0);
    const restarted = await f.attach("session-1", { epoch: ready.epoch, sequence: lastSequence });
    expect(restarted.epoch).not.toBe(ready.epoch);
    expect(restarted.resyncRequired).toBe(true);
  });

  it("fences old sessions even when both frontends reuse the same JSON-RPC id", async () => {
    const f = await fixture();
    const first = await f.attach();
    await f.send(first, { id: 1, method: "test.delay", params: { ms: 70, value: "old" } });
    const second = await f.attach("session-2");
    expect(second.resyncRequired).toBe(true);
    await f.send(second, { id: 1, method: "test.delay", params: { ms: 5, value: "new" } }, "session-2");
    await eventually(() => f.rpc().some((message) => message.result === "new"));
    await new Promise((resolve) => setTimeout(resolve, 90));
    expect(f.rpc().filter((message) => message.id === 1)).toEqual([{ id: 1, result: "new" }]);
    await expect(f.send(first, { id: 2, method: "test.echo" })).rejects.toThrow("失效");
    const restored = await f.attach("session-2", { epoch: second.epoch, sequence: 0 });
    expect(restored.frames.some((frame) => (frame.message as RawRpc).result === "old")).toBe(false);
  });

  it("reports a replay gap while preserving pending server requests outside the event ring", async () => {
    const f = await fixture({ maxFrames: 2 });
    const ready = await f.attach();
    const approval = { id: 8, method: "tool/requestUserInput", params: { questions: [] } };
    await f.send(ready, { id: 1, method: "test.emit", params: approval });
    for (let id = 2; id < 7; id++) await f.send(ready, { id, method: "test.echo" });
    await eventually(() => f.rpc().some((message) => message.id === 6));
    const restored = await f.attach("session-1", { epoch: ready.epoch, sequence: 0 });
    expect(restored.resyncRequired).toBe(true);
    expect(restored.frames).toEqual([]);
    expect(restored.pendingRequests).toEqual([approval]);
  });

  it("answers currentTime/read locally and never treats retryable errors as completion", async () => {
    const f = await fixture({ idleMs: 30 });
    const ready = await f.attach();
    await f.send(ready, { id: 1, method: "turn/start" });
    await f.send(ready, { id: 2, method: "test.emit", params: { id: "time", method: "currentTime/read", params: {} } });
    await f.send(ready, { id: 3, method: "test.emit", params: { method: "error", params: { willRetry: true } } });
    await eventually(() => f.rpc().some((message) => message.method === "test.response"));
    expect(f.rpc().some((message) => message.method === "currentTime/read")).toBe(false);
    const response = f.rpc().find((message) => message.method === "test.response")!;
    expect(response.params).toEqual({ id: "time", result: { currentTimeAt: expect.any(Number) } });
    f.channels.disconnected();
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(f.channels.status()[0]).toMatchObject({ active: true, pendingRequests: 0 });
  });

  it("refuses new writes when its deduplication window fills instead of forgetting old submissions", async () => {
    const f = await fixture({ maxDeliveries: 1 });
    const ready = await f.attach();
    const request = { id: 1, method: "test.echo" };
    await f.send(ready, request, "session-1", "first");
    await expect(f.send(ready, { id: 2, method: "test.echo" })).rejects.toThrow("去重窗口已满");
    await expect(f.send(ready, request, "session-1", "first")).resolves.toMatchObject({ duplicate: true });
    expect(f.events.some((frame) => frame.type === "resync-required")).toBe(true);
  });

  it("cancels an old initialization when a restart arrives before the process is spawned", async () => {
    const f = await fixture();
    const opening = f.attach();
    const cancelled = expect(opening).rejects.toThrow("启动已取消");
    await f.channels.restart();
    await cancelled;
    const ready = await f.attach();
    expect(ready.runningVersion).toBe("1.0.0");
    expect(f.channels.status()).toHaveLength(1);
    const requests = (await readFile(f.log, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
    expect(requests.filter((message) => message.method === "initialize")).toHaveLength(1);
  });
});

describe("Codex CLI runtime controls", () => {
  it("updates only the configured CLI, keeps running tasks, and confirms a separate restart", async () => {
    const f = await fixture();
    const ready = await f.attach();
    await f.send(ready, { id: 1, method: "turn/start" });
    await eventually(() => f.channels.status()[0]?.active === true);
    const control = new CodexRuntimeControl(f.executable, f.channels, () => {}, { latestVersion: async () => "1.1.0" });
    expect(await control.status()).toMatchObject({ installedVersion: "1.0.0", latestVersion: "1.1.0", updateSupported: true, updateAvailable: true });
    const operation = control.update();
    expect(control.update().id).toBe(operation.id);
    await eventually(() => operation.state !== "running");
    expect(operation.state).toBe("succeeded");
    expect(await readFile(f.updates, "utf8")).toBe("update\n");
    const afterUpdate = await f.attach("session-1", { epoch: ready.epoch, sequence: ready.sequence });
    expect(afterUpdate.epoch).toBe(ready.epoch);
    expect((await readFile(f.log, "utf8")).trim().split("\n").map((line) => JSON.parse(line))
      .filter((message) => message.method === "initialize")).toHaveLength(1);
    expect(await control.status()).toMatchObject({ installedVersion: "1.1.0", restartRequired: true,
      runningVersions: [{ channelId: "channel", version: "1.0.0", active: true, pendingRequests: 0 }] });
    expect(control.restart(false)).toMatchObject({ confirmationRequired: true });
    expect(f.channels.status()).toHaveLength(1);
    expect(control.restart(true)).toMatchObject({ confirmationRequired: false });
    await eventually(() => (control.snapshot() as { operation: { state: string } }).operation.state === "succeeded");
    expect(f.channels.status()).toEqual([{ channelId: "channel", version: "1.1.0", active: false, pendingRequests: 0 }]);
    const after = await f.attach("session-1", { epoch: ready.epoch, sequence: 0 });
    expect(after.runningVersion).toBe("1.1.0");
    expect(after.epoch).not.toBe(ready.epoch);
  });

  it("detects an externally updated executable without changing running instances", async () => {
    const f = await fixture();
    const ready = await f.attach();
    await writeFile(f.versionPath, "1.2.0");
    const control = new CodexRuntimeControl(f.executable, f.channels, () => {}, { latestVersion: async () => "1.2.0" });
    expect(await control.status()).toMatchObject({
      installedVersion: "1.2.0", updateAvailable: false, restartRequired: true,
      runningVersions: [{ channelId: "channel", version: "1.0.0", active: false, pendingRequests: 0 }],
    });
    expect(control.restart(false)).toMatchObject({ confirmationRequired: false });
    await eventually(() => (control.snapshot() as { operation: { state: string } }).operation.state === "succeeded");
    expect(control.snapshot()).toMatchObject({ restartRequired: false,
      runningVersions: [{ channelId: "channel", version: "1.2.0", active: false, pendingRequests: 0 }] });
    const reopened = await f.attach("session-1", { epoch: ready.epoch, sequence: 0 });
    expect(reopened.epoch).not.toBe(ready.epoch);
    expect(reopened.resyncRequired).toBe(true);
  });

  it("requires confirmation across all channels for unanswered calls and unknown server requests", async () => {
    const f = await fixture({ idleMs: 25 });
    const first = await f.attach();
    const second = await f.channels.attach({ channelId: "other", sessionId: "other-session" }) as Ready;
    await f.send(first, { id: 1, method: "test.never" });
    const futureRequest = { id: "future-request", method: "future/needsInput", params: { unknown: true }, extension: 123 };
    await f.channels.send({ channelId: "other", sessionId: "other-session", epoch: second.epoch,
      messageId: "future-trigger", message: { id: 2, method: "test.emit", params: futureRequest } });
    await eventually(() => f.channels.status().some((item) => item.channelId === "other" && item.pendingRequests === 1));
    const control = new CodexRuntimeControl(f.executable, f.channels, () => {}, { latestVersion: async () => null });
    expect(control.restart(false)).toEqual({ confirmationRequired: true, affected: [
      { channelId: "channel", version: "1.0.0", active: true, pendingRequests: 0 },
      { channelId: "other", version: "1.0.0", active: true, pendingRequests: 1 },
    ] });
    await expect(f.channels.send({ channelId: "other", sessionId: "other-session", epoch: second.epoch,
      messageId: "invalid-response", message: { id: "future-request" } })).rejects.toThrow("result 或 error");
    f.channels.disconnected();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(f.channels.status()).toHaveLength(2);
    const restored = await f.channels.attach({ channelId: "other", sessionId: "other-session", epoch: second.epoch, afterSequence: 0 }) as Ready;
    expect(restored.pendingRequests).toEqual([futureRequest]);
    expect(control.restart(true)).toMatchObject({ confirmationRequired: false });
    await eventually(() => (control.snapshot() as { operation: { state: string } }).operation.state === "succeeded");
    expect(f.channels.status()).toEqual([
      { channelId: "channel", version: "1.0.0", active: false, pendingRequests: 0 },
      { channelId: "other", version: "1.0.0", active: false, pendingRequests: 0 },
    ]);
    await expect(f.channels.send({ channelId: "other", sessionId: "other-session", epoch: second.epoch,
      messageId: "stale-response", message: { id: "future-request", result: {} } })).rejects.toThrow();
    const reopened = await f.channels.attach({ channelId: "other", sessionId: "other-session", epoch: second.epoch, afterSequence: 0 }) as Ready;
    expect(reopened.epoch).not.toBe(second.epoch);
    expect(reopened.pendingRequests).toEqual([]);
    expect(reopened.resyncRequired).toBe(true);
  });

  it("keeps unknown versions null and does not invent update availability", async () => {
    const channels = new CodexChannels("/nonexistent/codex", () => {});
    const control = new CodexRuntimeControl("/nonexistent/codex", channels, () => {}, { latestVersion: async () => "1.2.0-beta.1" });
    expect(await control.status()).toMatchObject({ installedVersion: null, latestVersion: null, updateAvailable: null, updateSupported: false });
    const operation = control.update();
    await eventually(() => operation.state !== "running");
    expect(operation).toMatchObject({ state: "failed", error: expect.stringContaining("未提供 update") });
    expect(parseCodexVersion("codex-cli 0.162.0-alpha.17.2")).toBe("0.162.0-alpha.17.2");
    expect(parseCodexVersion("unknown")).toBeNull();
  });
});

async function eventually(predicate: () => boolean, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("condition was not met");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
