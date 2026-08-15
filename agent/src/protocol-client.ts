import type { Readable } from "node:stream";
import WebSocket, { type RawData } from "ws";

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

export class ProtocolClient {
  private socket: WebSocket | undefined;
  private closed = false;

  constructor(
    private readonly url: URL,
    private readonly token: string,
    private readonly handlers: ProtocolHandlers,
  ) {}

  run(): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(this.url, {
        headers: { Authorization: `Bearer ${this.token}` },
        maxPayload: 2 * 1024 * 1024,
        handshakeTimeout: 20_000,
      });
      this.socket = socket;
      let opened = false;
      socket.on("open", () => {
        opened = true;
        this.closed = false;
        process.stdout.write(`已连接 Gateway: ${this.url.origin}\n`);
      });
      socket.on("message", (raw, binary) => {
        if (binary) return;
        this.handleMessage(raw);
      });
      socket.on("error", (error) => {
        if (!opened) reject(error);
      });
      socket.on("close", (code, reason) => {
        this.closed = true;
        this.socket = undefined;
        if (opened) {
          process.stderr.write(
            `Gateway 连接已断开 (code=${code}, reason=${reason.toString() || "none"})\n`,
          );
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
    this.closed = true;
    this.socket?.close(1000, "Agent stopped");
  }

  private handleMessage(raw: RawData): void {
    let message: GatewayMessage;
    try {
      message = JSON.parse(raw.toString()) as GatewayMessage;
    } catch {
      this.socket?.close(1003, "Invalid JSON");
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
          message: error instanceof Error ? error.message : String(error),
        });
      });
    } else {
      void Promise.resolve(this.handlers.stream(message)).catch((error: unknown) => {
        void this.event("agent.error", {
          streamId: message.id,
          message: error instanceof Error ? error.message : String(error),
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
          message: error instanceof Error ? error.message : String(error),
        },
      }).catch(() => {});
    }
  }

  private send(message: unknown): Promise<void> {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("Gateway 连接未建立"));
    }
    return new Promise((resolve, reject) => {
      socket.send(JSON.stringify(message), (error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  }
}

export type { GatewayStream };
