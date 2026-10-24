import { ChunkedTransport, WIRE_LIMIT } from "../../transport/websocket.cjs";
import type { Readable } from "node:stream";
import WebSocket, { type RawData } from "ws";
import { errorMessage } from "./error-message.js";

interface GatewayRequest {
  type: "request";
  id: string;
  method: string;
  params?: unknown;
}

interface GatewayNotification {
  type: "notify";
  method: string;
  params?: unknown;
}

interface GatewayStream {
  type: "stream" | "stream.end" | "stream.error";
  id: string;
  data?: string;
  message?: string;
}

type GatewayMessage = GatewayRequest | GatewayNotification | GatewayStream;

interface ProtocolHandlers {
  request: (method: string, params: unknown) => Promise<unknown>;
  notification: (method: string, params: unknown) => Promise<void> | void;
  stream: (message: GatewayStream) => Promise<void> | void;
}

interface ProtocolClientOptions {
  handshakeTimeout?: number;
  heartbeatIntervalMs?: number;
  connected?: () => void;
  log?: (message: string) => void;
}

export class ProtocolClient {
  private socket: WebSocket | undefined;
  private closed = false;
  private transport: ChunkedTransport | undefined;
  private heartbeatTimer: NodeJS.Timeout | undefined;

  constructor(
    private readonly url: URL,
    private readonly token: string,
    private readonly handlers: ProtocolHandlers,
    private readonly options: ProtocolClientOptions = {},
  ) {}

  run(): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(this.url.toString(), {
        headers: { Authorization: `Bearer ${this.token}` },
        maxPayload: WIRE_LIMIT,
        handshakeTimeout: this.options.handshakeTimeout ?? 20_000,
      });
      this.socket = socket;
      let opened = false;
      socket.on("open", () => {
        opened = true;
        this.closed = false;
        this.startHeartbeat(socket);
        this.transport = new ChunkedTransport(socket, {
          onMessage: text => this.handleMessage(text),
          onFatal: (error, code) => reject(code === 4400 || code === 4409 ? new FatalTransportError(error.message) : error),
        });
        if (this.options.connected) this.options.connected();
        else process.stdout.write(`已连接 Gateway: ${this.url.origin}\n`);
      });

      socket.on("error", (error) => {
        if (!opened) reject(new Error(errorMessage(error), { cause: error }));
      });
      socket.on("unexpected-response", (_request, response) => {
        response.resume();
        reject(response.statusCode === 404 || response.statusCode === 426
          ? new FatalTransportError("Gateway 不支持分块传输，请升级 Gateway")
          : new Error(`Gateway WebSocket HTTP ${response.statusCode}`));
        socket.terminate();
      });
      socket.on("close", (code, reason) => {
        this.stopHeartbeat();
        this.closed = true;
        this.socket = undefined;
        this.transport?.dispose();
        this.transport = undefined;
        if (code === 4400 || code === 4409) {
          reject(new FatalTransportError(reason.toString()));
          return;
        }
        if (opened) {
          const message = `Gateway 连接已断开 (code=${code}, reason=${reason.toString() || "none"})\n`;
          if (this.options.log) this.options.log(message); else process.stderr.write(message);
          resolve();
        } else {
          reject(
            new Error(
              `Gateway 在握手完成前关闭连接 (code=${code}, reason=${reason.toString() || "none"})`,
            ),
          );
        }
      });
    });
  }

  async event(event: string, payload: unknown): Promise<void> {
    if (!this.socket || this.closed) return;
    await this.send({ type: "event", event, payload });
  }

  async sendStream(id: string, input: Readable): Promise<void> {
    try {
      for await (const chunk of input) {
        await this.send({
          type: "stream",
          id,
          data: Buffer.from(chunk as Uint8Array).toString("base64"),
        });
      }
      await this.send({ type: "stream.end", id });
    } catch (error) {
      await this.send({
        type: "stream.error",
        id,
        message: error instanceof Error ? error.message : String(error),
      }).catch(() => {});
    }
  }

  close(): void {
    this.stopHeartbeat();
    this.closed = true;
    this.transport?.dispose();
    if (this.socket?.readyState === WebSocket.CONNECTING) this.socket.terminate();
    else this.socket?.close(1000, "Agent stopped");
  }

  private startHeartbeat(socket: WebSocket): void {
    const interval = this.options.heartbeatIntervalMs ?? 30_000;
    if (interval <= 0) return;
    let alive = true;
    socket.on("pong", () => { alive = true; });
    this.heartbeatTimer = setInterval(() => {
      if (this.closed || socket.readyState !== WebSocket.OPEN) return;
      if (!alive) {
        socket.terminate();
        return;
      }
      alive = false;
      socket.ping();
    }, interval);
    this.heartbeatTimer.unref();
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = undefined;
  }

  private handleMessage(raw: RawData | string): void {
    let message: GatewayMessage;
    try {
      message = JSON.parse(raw.toString()) as GatewayMessage;
    } catch {
      this.socket?.close(4400, "Invalid JSON");
      return;
    }
    if (message.type === "request") {
      void this.handleRequest(message);
    } else if (message.type === "notify") {
      void Promise.resolve(
        this.handlers.notification(message.method, message.params ?? {}),
      ).catch((error: unknown) => {
        void this.event("agent.error", {
          method: message.method,
          message: errorMessage(error),
        });
      });
    } else {
      void Promise.resolve(this.handlers.stream(message)).catch((error: unknown) => {
        void this.event("agent.error", {
          streamId: message.id,
          message: errorMessage(error),
        });
      });
    }
  }

  private async handleRequest(message: GatewayRequest): Promise<void> {
    try {
      const result = await this.handlers.request(message.method, message.params ?? {});
      await this.send({ type: "response", id: message.id, ok: true, result });
    } catch (error) {
      await this.send({
        type: "response",
        id: message.id,
        ok: false,
        error: {
          code: "AGENT_OPERATION_FAILED",
          message: errorMessage(error),
        },
      }).catch(() => {});
    }
  }

  private send(message: unknown): Promise<void> {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("Gateway 连接未建立"));
    }
    if (!this.transport?.negotiated) return Promise.reject(new Error("Transport handshake incomplete"));
    return this.transport.send(message);
  }
}

export class FatalTransportError extends Error {}

export type { GatewayStream };
