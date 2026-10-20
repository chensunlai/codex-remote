import { createHash, randomUUID } from "node:crypto";
import { execFile, spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { APP_VERSION } from "./version.js";
import { ClientCoordination, type CoordinationMessage } from "./client-coordination.js";

export type RawRpc = Record<string, unknown>;
type RpcId = string | number;

export interface ChannelFrame {
  channelId: string;
  epoch: string;
  sequence: number;
  type: "rpc" | "closed" | "stderr" | "resync-required" | "coordination";
  message?: RawRpc | string;
  sessionId?: string;
}

interface SavedFrame { frame: ChannelFrame; bytes: number }
interface ClientCall { id: RpcId; sessionId: string; method: string; processHandle?: string }

export interface ChannelAttach {
  channelId: string;
  sessionId: string;
  afterSequence?: number;
  epoch?: string;
  initialize?: unknown;
}

export interface ChannelOptions {
  idleMs?: number;
  maxBufferBytes?: number;
  maxFrames?: number;
  maxDeliveries?: number;
  initializationTimeoutMs?: number;
}

const MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
const MAX_ATTACH_BYTES = 15 * 1024 * 1024;

/** Owns app-server processes independently of the mobile/Gateway connection. */
export class CodexChannels {
  private readonly channels = new Map<string, CodexChannel>();
  private restarting = false;
  private closed = false;

  constructor(
    private readonly executable: string,
    private readonly emit: (frame: ChannelFrame) => void,
    private readonly options: ChannelOptions = {},
  ) {}

  async attach(input: ChannelAttach): Promise<unknown> {
    if (this.closed) throw new Error("Codex 通道管理器已关闭");
    if (this.restarting) throw new Error("Codex 正在重启，请重新连接");
    requireString(input.channelId, "channelId");
    requireString(input.sessionId, "sessionId");
    if (input.afterSequence !== undefined
      && (!Number.isSafeInteger(input.afterSequence) || input.afterSequence < 0)) {
      throw new Error("afterSequence 必须是非负整数");
    }
    let channel = this.channels.get(input.channelId);
    if (!channel) {
      channel = new CodexChannel(input.channelId, this.executable, this.emit, this.options);
      this.channels.set(input.channelId, channel);
    }
    return channel.attach(input);
  }

  async send(input: {
    channelId: string; sessionId: string; epoch: string; messageId: string; message: unknown;
  }): Promise<unknown> {
    if (this.restarting) throw new Error("Codex 正在重启，请重新连接");
    return this.channel(input.channelId).send(input);
  }

  detach(input: { channelId: string; sessionId: string }): unknown {
    this.channels.get(input.channelId)?.detach(input.sessionId);
    return {};
  }

  coordinate(input: { channelId: string; sessionId: string; epoch: string; message: CoordinationMessage }): unknown {
    return this.channel(input.channelId).coordinate(input);
  }

  ack(input: { channelId: string; sessionId: string; sequence: number }): unknown {
    this.channel(input.channelId).ack(input.sessionId, input.sequence);
    return {};
  }

  disconnected(): void {
    for (const channel of this.channels.values()) channel.detach();
  }

  status(): Array<{ channelId: string; version: string | null; active: boolean; pendingRequests: number }> {
    return [...this.channels.values()].filter((channel) => channel.running).map((channel) => ({
      channelId: channel.channelId,
      version: channel.version,
      active: channel.active,
      pendingRequests: channel.pendingRequestCount,
    }));
  }

  async restart(): Promise<void> {
    if (this.restarting) throw new Error("Codex 正在重启");
    this.restarting = true;
    try {
      const results = await Promise.allSettled([...this.channels.values()].filter((channel) => channel.running)
        .map((channel) => channel.restart(true)));
      const failed = results.find((result): result is PromiseRejectedResult => result.status === "rejected");
      if (failed) throw failed.reason;
    } finally {
      this.restarting = false;
    }
  }

  close(): void {
    this.closed = true;
    for (const channel of this.channels.values()) channel.close();
    this.channels.clear();
  }

  private channel(id: string): CodexChannel {
    const channel = this.channels.get(id);
    if (!channel) throw new Error("Codex 通道不存在，请重新连接");
    return channel;
  }
}

class CodexChannel {
  private closed = false;
  private child: ChildProcessWithoutNullStreams | undefined;
  private opening: Promise<void> | undefined;
  private stopping: Promise<void> | undefined;
  private epoch = randomUUID();
  private sequence = 0;
  private lastDroppedSequence = 0;
  private sessionId: string | undefined;
  private attached = false;
  private coordination: ClientCoordination | undefined;
  private initializeResult: unknown;
  private initializeParams: unknown;
  private frames: SavedFrame[] = [];
  private bufferBytes = 0;
  private readonly calls = new Map<RpcId, ClientCall>();
  private readonly pending = new Map<RpcId, RawRpc>();
  private readonly deliveries = new Map<string, { digest: string; status: "accepted" | "unknown" }>();
  private readonly activeTurns = new Set<string>();
  private readonly activeThreads = new Set<string>();
  private readonly backgroundProcesses = new Map<string, RpcId>();
  private nextCall = 0;
  private idleTimer: NodeJS.Timeout | undefined;
  version: string | null = null;

  constructor(
    readonly channelId: string,
    private readonly executable: string,
    private readonly emit: (frame: ChannelFrame) => void,
    private readonly options: ChannelOptions,
  ) {}

  get running(): boolean { return this.child !== undefined || this.opening !== undefined; }
  get active(): boolean {
    return this.activeTurns.size > 0 || this.activeThreads.size > 0 || this.backgroundProcesses.size > 0
      || this.pending.size > 0 || this.calls.size > 0;
  }
  get pendingRequestCount(): number { return this.pending.size; }

  async attach(input: ChannelAttach): Promise<unknown> {
    if (this.stopping) await this.stopping;
    this.cancelIdle();
    const previousSession = this.sessionId;
    const wasRunning = this.running || this.opening !== undefined;
    this.closeCoordination();
    this.sessionId = input.sessionId;
    this.attached = true;
    if (previousSession !== input.sessionId) this.deliveries.clear();
    try {
      await this.open(input.initialize);
    } catch (error) {
      if (this.sessionId === input.sessionId) this.attached = false;
      throw error;
    }
    if (this.sessionId !== input.sessionId) throw new Error("通道已由新的前端连接接管");
    const after = input.afterSequence ?? 0;
    let resyncRequired = wasRunning && (
      previousSession !== input.sessionId || input.epoch !== this.epoch
      || after < this.lastDroppedSequence || after > this.sequence
    );
    if (input.epoch !== undefined && input.epoch !== this.epoch) resyncRequired = true;
    let frames = resyncRequired ? [] : this.frames
      .filter(({ frame }) => frame.sequence > after && this.visible(frame))
      .map(({ frame }) => frame);
    const pendingRequests = [...this.pending.values()];
    // Keep pending requests authoritative even after their original frames roll out.
    if (Buffer.byteLength(JSON.stringify({ frames, pendingRequests })) > MAX_ATTACH_BYTES) {
      frames = [];
      resyncRequired = true;
    }
    if (Buffer.byteLength(JSON.stringify(pendingRequests)) > MAX_ATTACH_BYTES) {
      throw new Error("待处理请求超过连接快照上限，请先处理现有请求");
    }
    return {
      channelId: this.channelId,
      epoch: this.epoch,
      sequence: this.sequence,
      initializeResult: this.initializeResult,
      frames,
      pendingRequests,
      runningVersion: this.version,
      clientCoordination: process.platform === "linux",
      resyncRequired,
    };
  }

  async send(input: { sessionId: string; epoch: string; messageId: string; message: unknown }): Promise<unknown> {
    this.assertSession(input.sessionId);
    if (input.epoch !== this.epoch) throw new Error("Codex 通道已重建，请重新同步");
    requireString(input.messageId, "messageId");
    const message = rpcObject(input.message);
    const serialized = JSON.stringify(message);
    if (Buffer.byteLength(serialized) > MAX_MESSAGE_BYTES) throw new Error("Codex 消息超过 16 MiB");
    const digest = createHash("sha256").update(serialized).digest("hex");
    const prior = this.deliveries.get(input.messageId);
    if (prior !== undefined) {
      if (prior.digest !== digest) throw new Error("messageId 已用于不同消息");
      return { status: prior.status, duplicate: true, epoch: this.epoch };
    }
    if (this.deliveries.size >= (this.options.maxDeliveries ?? 4096)) {
      this.publish("resync-required", "提交去重窗口已满，请建立新的前端会话");
      throw new Error("提交去重窗口已满，请建立新的前端会话");
    }
    if (!this.child || this.child.stdin.destroyed) throw new Error("Codex 进程未连接");
    this.cancelIdle();
    if (message.method === "initialize" && isId(message.id)) {
      this.deliveries.set(input.messageId, { digest, status: "accepted" });
      this.publish("rpc", { id: message.id, result: this.initializeResult }, input.sessionId);
      return { status: "accepted", duplicate: false, epoch: this.epoch };
    }
    if (message.method === "initialized") {
      this.deliveries.set(input.messageId, { digest, status: "accepted" });
      return { status: "accepted", duplicate: false, epoch: this.epoch };
    }
    let wireMessage = message;
    let wireId: RpcId | undefined;
    if (typeof message.method === "string" && isId(message.id)) {
      wireId = `remote:${this.epoch}:${++this.nextCall}`;
      const processHandle = message.method === "process/spawn" ? asRecord(message.params).processHandle : undefined;
      this.calls.set(wireId, { id: message.id, sessionId: input.sessionId, method: message.method,
        ...(typeof processHandle === "string" ? { processHandle } : {}),
      });
      if (typeof processHandle === "string" && !this.backgroundProcesses.has(processHandle)) {
        this.backgroundProcesses.set(processHandle, wireId);
      }
      wireMessage = { ...message, id: wireId };
    } else if (message.method === undefined && isId(message.id)) {
      if (!this.pending.has(message.id)) throw new Error("审批或输入请求已失效");
    }
    // Record before writing: a lost delivery receipt must never execute the same call twice.
    const delivery = { digest, status: "unknown" as "accepted" | "unknown" };
    this.deliveries.set(input.messageId, delivery);
    try {
      await this.write(wireMessage);
      delivery.status = "accepted";
      if (message.method === undefined && isId(message.id)) this.pending.delete(message.id);
    } catch (error) {
      if (wireId !== undefined) this.calls.delete(wireId);
      // A write failure has ambiguous delivery. Keep the dedup entry and require resync.
      this.publish("resync-required", "消息写入结果不确定，请重新同步");
      throw error;
    } finally {
      this.scheduleIdle();
    }
    return { status: "accepted", duplicate: false, epoch: this.epoch };
  }

  detach(sessionId?: string): void {
    if (sessionId !== undefined && this.sessionId !== sessionId) return;
    this.closeCoordination();
    this.attached = false;
    this.scheduleIdle();
  }

  coordinate(input: { sessionId: string; epoch: string; message: CoordinationMessage }): unknown {
    this.assertSession(input.sessionId);
    if (input.epoch !== this.epoch) throw new Error("Codex 通道已重建，请重新同步");
    if (process.platform !== "linux") throw new Error("当前平台不支持官方客户端协调");
    if (input.message.type === "connect") {
      this.closeCoordination();
      const home = asRecord(this.initializeResult).codexHome;
      const bridge = new ClientCoordination(typeof home === "string" ? home : "", (message) => {
        if (this.coordination === bridge && this.attached) this.publish("coordination", message, input.sessionId);
      });
      this.coordination = bridge;
      bridge.start();
    } else if (input.message.type === "message") {
      const message = input.message.message;
      if (!message || typeof message !== "object" || Array.isArray(message)) throw new Error("无效的协调消息");
      try {
        if (!this.coordination) throw new Error("Coordination disconnected");
        this.coordination.send(message as CoordinationMessage);
      } catch {
        // IPC commands are never queued or replayed after a broken connection.
        this.closeCoordination();
        this.publish("coordination", { type: "disconnected" }, input.sessionId);
        this.coordinate({ ...input, message: { type: "connect" } });
      }
    } else throw new Error("无效的协调操作");
    return {};
  }

  private closeCoordination(): void {
    this.coordination?.close();
    this.coordination = undefined;
  }

  ack(sessionId: string, sequence: number): void {
    this.assertSession(sessionId);
    if (!Number.isSafeInteger(sequence) || sequence < 0 || sequence > this.sequence) {
      throw new Error("无效的消息确认序号");
    }
    while (this.frames[0] && this.frames[0].frame.sequence <= sequence) this.dropFirst();
  }

  async restart(reopen = false): Promise<void> {
    if (this.stopping) return this.stopping;
    this.stopping = this.stop();
    try { await this.stopping; }
    finally { this.stopping = undefined; }
    if (reopen && !this.closed) await this.open();
  }

  private async stop(): Promise<void> {
    this.closeCoordination();
    this.cancelIdle();
    const child = this.child;
    this.child = undefined;
    this.opening = undefined;
    if (child) {
      this.publish("closed", "Codex app-server 已重启，请重新连接");
      await terminate(child);
    }
    this.attached = false;
    this.reset();
  }

  close(): void {
    this.closed = true;
    this.closeCoordination();
    this.cancelIdle();
    const child = this.child;
    this.child = undefined;
    this.epoch = randomUUID();
    if (child) void terminate(child);
  }

  private async open(initialize?: unknown): Promise<void> {
    if (this.closed) throw new Error("Codex 通道已关闭");
    if (this.opening) return this.opening;
    if (this.child) return;
    this.reset();
    this.initializeParams = initialize ?? this.initializeParams ?? {
      clientInfo: { name: "codex_remote_agent", title: "Codex Remote", version: APP_VERSION },
      capabilities: { experimentalApi: true, mcpServerOpenaiFormElicitation: true },
    };
    const opening = this.start();
    this.opening = opening;
    try { await opening; }
    finally { if (this.opening === opening) this.opening = undefined; }
  }

  private async start(): Promise<void> {
    const startingEpoch = this.epoch;
    const version = await readCodexVersion(this.executable);
    if (this.epoch !== startingEpoch) throw new Error("Codex 启动已取消");
    const child = spawn(this.executable, ["app-server", "--stdio"], {
      stdio: ["pipe", "pipe", "pipe"], env: process.env, windowsHide: true,
    });
    this.child = child;
    let buffer = Buffer.alloc(0);
    const initializationId = `initialize:${this.epoch}`;
    return new Promise<void>((resolve, reject) => {
      let initialized = false;
      const timer = setTimeout(() => {
        failed(new Error("Codex 初始化超时"));
      }, this.options.initializationTimeoutMs ?? 30_000);
      const failed = (error: Error): void => {
        clearTimeout(timer);
        if (!initialized) reject(error);
        if (this.child !== child) return;
        this.closeCoordination();
        this.child = undefined;
        this.calls.clear();
        this.pending.clear();
        this.activeTurns.clear();
        this.activeThreads.clear();
        this.backgroundProcesses.clear();
        this.publish("closed", error.message);
        child.kill("SIGTERM");
      };
      child.on("error", failed);
      child.on("exit", (code, signal) => failed(
        new Error(`Codex app-server 已退出 (code=${String(code)}, signal=${String(signal)})`),
      ));
      child.stdin.on("error", failed);
      child.stdout.on("error", failed);
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", (text: string) => {
        if (this.child === child) this.publish("stderr", text.slice(-65_536));
      });
      child.stdout.on("data", (chunk: Buffer) => {
        if (this.child !== child) return;
        buffer = Buffer.concat([buffer, chunk]);
        for (;;) {
          const newline = buffer.indexOf(10);
          if (newline < 0) break;
          if (newline > MAX_MESSAGE_BYTES) { failed(new Error("Codex 消息超过 16 MiB")); return; }
          const line = buffer.subarray(0, newline).toString("utf8").trim();
          buffer = buffer.subarray(newline + 1);
          if (!line) continue;
          try {
            const message = rpcObject(JSON.parse(line));
            if (!initialized && message.id === initializationId && message.method === undefined) {
              if (message.error !== undefined) {
                failed(new Error(`Codex 初始化失败: ${JSON.stringify(message.error)}`));
                return;
              }
              this.initializeResult = message.result;
              void this.write({ method: "initialized", params: {} }).then(async () => {
                const afterStartVersion = await readCodexVersion(this.executable);
                if (this.child !== child) return;
                // A CLI update can replace the configured executable between the probe and spawn.
                // Neither value can identify this process reliably when the probes disagree.
                this.version = version !== null && afterStartVersion === version ? version : null;
                initialized = true;
                clearTimeout(timer);
                resolve();
                this.scheduleIdle();
              }).catch(failed);
            } else {
              this.received(message);
            }
          } catch (error) {
            failed(error instanceof Error ? error : new Error(String(error)));
            return;
          }
        }
        if (buffer.byteLength > MAX_MESSAGE_BYTES) failed(new Error("Codex 消息超过 16 MiB"));
      });
      void this.write({ id: initializationId, method: "initialize", params: this.initializeParams })
        .catch(failed);
    });
  }

  private received(message: RawRpc): void {
    if (typeof message.method === "string" && isId(message.id)) {
      if (message.method === "currentTime/read") {
        void this.write({ id: message.id, result: { currentTimeAt: Math.floor(Date.now() / 1000) } })
          .catch(() => {});
        return;
      }
      this.pending.set(message.id, message);
      this.publish("rpc", message);
    } else if (message.method === undefined && isId(message.id)) {
      const call = this.calls.get(message.id);
      if (!call) return;
      this.calls.delete(message.id);
      this.observeResponse(call, message);
      this.publish("rpc", { ...message, id: call.id }, call.sessionId);
    } else {
      this.observe(message);
      this.publish("rpc", message);
    }
    this.scheduleIdle();
  }

  private observeResponse(call: ClientCall, message: RawRpc): void {
    if (message.error !== undefined) {
      if (call.processHandle !== undefined && this.backgroundProcesses.get(call.processHandle) === message.id) {
        this.backgroundProcesses.delete(call.processHandle);
      }
      return;
    }
    const method = call.method;
    const result = asRecord(message.result);
    const turn = asRecord(result.turn);
    if ((method === "turn/start" || method === "turn/steer") && typeof turn.id === "string"
      && turn.status !== "completed" && turn.status !== "failed" && turn.status !== "interrupted") {
      this.activeTurns.add(turn.id);
    }
    const thread = asRecord(result.thread);
    if (typeof thread.id === "string" && asRecord(thread.status).type === "active") this.activeThreads.add(thread.id);
  }

  private observe(message: RawRpc): void {
    const params = asRecord(message.params);
    const turn = asRecord(params.turn);
    if (message.method === "turn/started" && typeof turn.id === "string") this.activeTurns.add(turn.id);
    if (message.method === "turn/completed" && typeof turn.id === "string") this.activeTurns.delete(turn.id);
    if (message.method === "thread/status/changed" && typeof params.threadId === "string") {
      if (asRecord(params.status).type === "active") this.activeThreads.add(params.threadId);
      else this.activeThreads.delete(params.threadId);
    }
    if (message.method === "thread/closed" && typeof params.threadId === "string") this.activeThreads.delete(params.threadId);
    if (message.method === "serverRequest/resolved" && isId(params.requestId)) this.pending.delete(params.requestId);
    if (message.method === "process/exited" && typeof params.processHandle === "string") {
      this.backgroundProcesses.delete(params.processHandle);
    }
    // Error notifications (including willRetry) never imply that a turn has completed.
  }

  private write(message: RawRpc): Promise<void> {
    const child = this.child;
    if (!child || child.stdin.destroyed) return Promise.reject(new Error("Codex 进程未连接"));
    return new Promise((resolve, reject) => {
      child.stdin.write(`${JSON.stringify(message)}\n`, (error) => error ? reject(error) : resolve());
    });
  }

  private publish(type: ChannelFrame["type"], message: RawRpc | string, sessionId?: string): void {
    const frame: ChannelFrame = {
      channelId: this.channelId, epoch: this.epoch, sequence: ++this.sequence, type, message,
      ...(sessionId !== undefined ? { sessionId } : {}),
    };
    // Coordination belongs to the live renderer and must not survive reconnects.
    if (type !== "coordination") {
      const bytes = Buffer.byteLength(JSON.stringify(frame));
      this.frames.push({ frame, bytes });
      this.bufferBytes += bytes;
      while (this.frames.length > (this.options.maxFrames ?? 2048)
        || this.bufferBytes > (this.options.maxBufferBytes ?? 8 * 1024 * 1024)) this.dropFirst();
    }
    if (this.attached && this.visible(frame)) this.emit(frame);
  }

  private visible(frame: ChannelFrame): boolean {
    return frame.sessionId === undefined || frame.sessionId === this.sessionId;
  }

  private dropFirst(): void {
    const item = this.frames.shift();
    if (!item) return;
    this.bufferBytes -= item.bytes;
    this.lastDroppedSequence = item.frame.sequence;
  }

  private assertSession(sessionId: string): void {
    if (!this.attached || sessionId !== this.sessionId) throw new Error("前端连接已失效，请重新连接");
  }

  private scheduleIdle(): void {
    this.cancelIdle();
    if (this.attached || this.active || !this.child) return;
    this.idleTimer = setTimeout(() => {
      if (!this.attached && !this.active) void this.restart();
    }, this.options.idleMs ?? 60_000);
    this.idleTimer.unref();
  }

  private cancelIdle(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = undefined;
  }

  private reset(): void {
    this.closeCoordination();
    this.epoch = randomUUID();
    this.sequence = 0;
    this.lastDroppedSequence = 0;
    this.frames = [];
    this.bufferBytes = 0;
    this.calls.clear();
    this.pending.clear();
    this.deliveries.clear();
    this.activeTurns.clear();
    this.activeThreads.clear();
    this.backgroundProcesses.clear();
    this.initializeResult = undefined;
    this.version = null;
  }
}

export async function readCodexVersion(executable: string): Promise<string | null> {
  try { return parseCodexVersion(await commandOutput(executable, ["--version"])); }
  catch { return null; }
}

export function parseCodexVersion(output: string): string | null {
  return /(?:^|\s)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)(?:\s|$)/.exec(output.trim())?.[1] ?? null;
}

export function commandOutput(executable: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(executable, args, { timeout: 10_000, maxBuffer: 1024 * 1024, encoding: "utf8", env: process.env, windowsHide: true },
      (error, stdout) => error ? reject(error) : resolve(stdout));
  });
}

function terminate(child: ChildProcessWithoutNullStreams): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(() => { child.kill("SIGKILL"); }, 5_000);
    timer.unref();
    child.once("exit", () => { clearTimeout(timer); resolve(); });
    child.once("error", () => { clearTimeout(timer); resolve(); });
    child.kill("SIGTERM");
  });
}

function isId(value: unknown): value is RpcId {
  return typeof value === "string" || (typeof value === "number" && Number.isFinite(value));
}

function rpcObject(value: unknown): RawRpc {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Codex RPC 必须是对象");
  const message = value as RawRpc;
  if (typeof message.method !== "string") {
    if (!isId(message.id) && !(message.id === null && "error" in message)) throw new Error("Codex RPC 缺少 method/id");
    if (("result" in message) === ("error" in message)) throw new Error("Codex RPC 响应必须包含 result 或 error");
  }
  return message;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function requireString(value: string, name: string): void {
  if (typeof value !== "string" || !value || value.length > 512) throw new Error(`${name} 必须是非空字符串且不超过 512 字符`);
}
