import { EventEmitter } from "node:events";
import type { Readable, Writable } from "node:stream";

type JsonRpcId = number | string;

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

interface RpcMessage {
  id?: JsonRpcId;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

export interface ServerRequestMessage {
  id: JsonRpcId;
  method: string;
  params: unknown;
}

export interface NotificationMessage {
  method: string;
  params: unknown;
}

export class JsonRpcError extends Error {
  constructor(
    public readonly rpcCode: number,
    message: string,
    public readonly data?: unknown,
  ) {
    super(`Codex RPC ${rpcCode}: ${message}`);
    this.name = "JsonRpcError";
  }
}

const MAX_LINE_BYTES = 16 * 1024 * 1024;

export class JsonRpcPeer extends EventEmitter {
  private nextId = 1;
  private inputBuffer = Buffer.alloc(0);
  private readonly pending = new Map<JsonRpcId, PendingCall>();
  private closed = false;

  constructor(
    input: Readable,
    private readonly output: Writable,
  ) {
    super();
    input.on("data", (chunk: Buffer | string) => this.onData(Buffer.from(chunk)));
    input.on("error", (error) => this.fail(error));
    input.on("close", () => this.fail(new Error("Codex RPC 输出流已关闭")));
    input.on("end", () => this.fail(new Error("Codex RPC 输出流已结束")));
    output.on("error", (error) => this.fail(error));
  }

  request<T = unknown>(method: string, params: unknown = {}, timeoutMs = 90_000): Promise<T> {
    if (this.closed) return Promise.reject(new Error("Codex RPC 连接已关闭"));
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} 在 ${timeoutMs}ms 内未响应`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (value) => resolve(value as T),
        reject,
        timer,
      });
      this.write({ id, method, params });
    });
  }

  notify(method: string, params: unknown = {}): void {
    this.write({ method, params });
  }

  respond(id: JsonRpcId, result: unknown): void {
    this.write({ id, result });
  }

  close(): void {
    if (this.closed) return;
    this.output.end();
    this.fail(new Error("Codex RPC 连接已关闭"));
  }

  isOpen(): boolean {
    return !this.closed;
  }

  private write(message: RpcMessage): void {
    if (this.closed) throw new Error("Codex RPC 连接已关闭");
    this.output.write(`${JSON.stringify(message)}\n`);
  }

  private onData(chunk: Buffer): void {
    this.inputBuffer = Buffer.concat([this.inputBuffer, chunk]);
    if (this.inputBuffer.length > MAX_LINE_BYTES && this.inputBuffer.indexOf(0x0a) < 0) {
      this.fail(new Error("Codex RPC 消息超过 16 MiB"));
      return;
    }
    for (;;) {
      const newline = this.inputBuffer.indexOf(0x0a);
      if (newline < 0) break;
      const line = this.inputBuffer.subarray(0, newline).toString("utf8").trim();
      this.inputBuffer = this.inputBuffer.subarray(newline + 1);
      if (!line) continue;
      try {
        this.onMessage(JSON.parse(line) as RpcMessage);
      } catch (error) {
        this.emit("protocolError", error);
      }
    }
  }

  private onMessage(message: RpcMessage): void {
    if (message.id !== undefined && !message.method) {
      const call = this.pending.get(message.id);
      if (!call) return;
      clearTimeout(call.timer);
      this.pending.delete(message.id);
      if (message.error) {
        call.reject(
          new JsonRpcError(message.error.code, message.error.message, message.error.data),
        );
      } else {
        call.resolve(message.result);
      }
      return;
    }
    if (message.method && message.id !== undefined) {
      this.emit("serverRequest", {
        id: message.id,
        method: message.method,
        params: message.params ?? {},
      } satisfies ServerRequestMessage);
      return;
    }
    if (message.method) {
      this.emit("notification", {
        method: message.method,
        params: message.params ?? {},
      } satisfies NotificationMessage);
    }
  }

  private fail(error: Error): void {
    if (this.closed) return;
    this.closed = true;
    for (const call of this.pending.values()) {
      clearTimeout(call.timer);
      call.reject(error);
    }
    this.pending.clear();
    this.emit("closed", error);
  }
}
