import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import { PassThrough, type Readable } from "node:stream";
import WebSocket, { type RawData } from "ws";
import type {
  AgentDescription,
  AgentEventMessage,
  AgentInboundMessage,
  AgentResponseMessage,
  AgentStreamMessage,
  PendingServerRequest,
  ServiceView,
} from "./domain.js";
import { AppError, ConflictError, errorMessage } from "./errors.js";
import { EventJournal } from "./event-journal.js";
import { ServiceStore } from "./service-store.js";

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

interface DownloadStream {
  stream: PassThrough;
  received: number;
  expected: number;
  limit: number;
}

interface TerminalEvent {
  ownerId: string;
  serviceId: string;
  event: "data" | "exit";
  terminalId: string;
  data?: string;
  exitCode?: number;
}

class AgentPeer {
  private readonly pending = new Map<string, PendingCall>();
  private readonly streams = new Map<string, DownloadStream>();
  private alive = true;
  private closed = false;
  private readonly pingTimer: NodeJS.Timeout;

  constructor(
    readonly ownerId: string,
    readonly serviceId: string,
    readonly name: string,
    readonly connectedAt: string,
    private readonly socket: WebSocket,
    private readonly onEvent: (peer: AgentPeer, event: AgentEventMessage) => void,
    private readonly onClosed: (peer: AgentPeer, message: string) => void,
  ) {
    socket.on("message", (data, binary) => {
      if (binary) return;
      this.handleMessage(data);
    });
    socket.on("pong", () => {
      this.alive = true;
    });
    socket.on("error", (error) => this.closeWithError(error));
    socket.on("close", () => this.closeWithError(new Error("服务器 Agent 已断开")));
    this.pingTimer = setInterval(() => {
      if (!this.alive) {
        socket.terminate();
        return;
      }
      this.alive = false;
      socket.ping();
    }, 30_000);
  }

  request<T = unknown>(method: string, params: unknown = {}, timeoutMs = 45_000): Promise<T> {
    if (this.closed || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new ConflictError("服务器 Agent 当前不在线"));
    }
    const id = randomUUID();
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new AppError(504, "AGENT_TIMEOUT", `${method} 在 ${timeoutMs}ms 内未响应`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (value) => resolve(value as T),
        reject,
        timer,
      });
      void this.send({ type: "request", id, method, params }).catch((error: unknown) => {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      });
    });
  }

  async notify(method: string, params: unknown = {}): Promise<void> {
    await this.send({ type: "notify", method, params });
  }

  createDownloadStream(id: string, expected: number, limit: number): PassThrough {
    const stream = new PassThrough();
    this.streams.set(id, { stream, received: 0, expected, limit });
    stream.once("close", () => this.streams.delete(id));
    return stream;
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
      await this.send({ type: "stream.error", id, message: errorMessage(error) }).catch(() => {});
      throw error;
    }
  }

  close(code = 1000, reason = "Gateway closed connection"): void {
    if (this.closed) return;
    this.socket.close(code, reason);
    this.closeWithError(new Error(reason));
  }

  private handleMessage(raw: RawData): void {
    let message: AgentInboundMessage;
    try {
      message = JSON.parse(raw.toString()) as AgentInboundMessage;
    } catch {
      this.socket.close(1003, "Invalid JSON");
      return;
    }
    if (message.type === "response") {
      this.handleResponse(message);
      return;
    }
    if (message.type === "event") {
      this.onEvent(this, message);
      return;
    }
    this.handleStream(message);
  }

  private handleResponse(message: AgentResponseMessage): void {
    const call = this.pending.get(message.id);
    if (!call) return;
    clearTimeout(call.timer);
    this.pending.delete(message.id);
    if (message.ok) {
      call.resolve(message.result);
    } else {
      call.reject(
        new AppError(
          502,
          message.error?.code ?? "AGENT_ERROR",
          message.error?.message ?? "服务器 Agent 请求失败",
          message.error?.details,
        ),
      );
    }
  }

  private handleStream(message: AgentStreamMessage): void {
    const entry = this.streams.get(message.id);
    if (!entry) return;
    if (message.type === "stream") {
      if (message.data) {
        const chunk = Buffer.from(message.data, "base64");
        entry.received += chunk.length;
        if (entry.received > entry.limit || entry.received > entry.expected) {
          this.streams.delete(message.id);
          entry.stream.destroy(new AppError(413, "DOWNLOAD_TOO_LARGE", "远端文件在传输时超过允许大小"));
          return;
        }
        entry.stream.write(chunk);
      }
      return;
    }
    this.streams.delete(message.id);
    if (message.type === "stream.error") {
      entry.stream.destroy(new Error(message.message ?? "远端文件流失败"));
    } else if (entry.received !== entry.expected) {
      entry.stream.destroy(new Error("远端文件在传输期间发生变化"));
    } else {
      entry.stream.end();
    }
  }

  private send(message: unknown): Promise<void> {
    if (this.closed || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new ConflictError("服务器 Agent 当前不在线"));
    }
    return new Promise((resolve, reject) => {
      this.socket.send(JSON.stringify(message), (error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  }

  private closeWithError(error: Error): void {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pingTimer);
    for (const call of this.pending.values()) {
      clearTimeout(call.timer);
      call.reject(error);
    }
    this.pending.clear();
    for (const entry of this.streams.values()) entry.stream.destroy(error);
    this.streams.clear();
    this.onClosed(this, error.message);
  }
}

export class AgentRegistry extends EventEmitter {
  private readonly peers = new Map<string, AgentPeer>();
  private readonly pendingRequests = new Map<string, PendingServerRequest>();

  constructor(
    private readonly services: ServiceStore,
    private readonly journal: EventJournal,
  ) {
    super();
  }

  async attach(
    ownerId: string,
    serviceId: string,
    name: string,
    socket: WebSocket,
  ): Promise<void> {
    const key = this.key(ownerId, serviceId);
    this.peers.get(key)?.close(4001, "同一服务建立了新连接");
    const connectedAt = new Date().toISOString();
    const peer = new AgentPeer(
      ownerId,
      serviceId,
      name,
      connectedAt,
      socket,
      (source, event) => this.handleEvent(source, event),
      (source, message) => this.handleClose(source, message),
    );
    this.peers.set(key, peer);
    await this.services.register(ownerId, serviceId, name);
    this.journal.publish(ownerId, "service.status", { state: "connected" }, serviceId);

    try {
      const description = await peer.request<AgentDescription>("service.describe", {}, 15_000);
      await this.services.register(ownerId, serviceId, name, description);
      this.journal.publish(ownerId, "service.updated", description, serviceId);
    } catch (error) {
      this.journal.publish(
        ownerId,
        "service.warning",
        { message: errorMessage(error) },
        serviceId,
      );
    }
  }

  async list(ownerId: string): Promise<ServiceView[]> {
    const records = await this.services.list(ownerId);
    return records.map((record) => {
      const peer = this.peers.get(this.key(ownerId, record.id));
      return {
        id: record.id,
        name: record.name,
        ...(record.hostname ? { hostname: record.hostname } : {}),
        ...(record.platform ? { platform: record.platform } : {}),
        ...(record.arch ? { arch: record.arch } : {}),
        ...(record.agentVersion ? { agentVersion: record.agentVersion } : {}),
        ...(record.home ? { home: record.home } : {}),
        ...(peer ? { connectedAt: peer.connectedAt } : {}),
        runtime: peer
          ? { state: "connected" as const }
          : { state: "disconnected" as const, message: "等待服务器 Agent 主动连接" },
      };
    });
  }

  isOnline(ownerId: string, serviceId: string): boolean {
    return this.peers.has(this.key(ownerId, serviceId));
  }

  async request<T = unknown>(
    ownerId: string,
    serviceId: string,
    method: string,
    params: unknown = {},
    timeoutMs?: number,
  ): Promise<T> {
    return this.peer(ownerId, serviceId).request<T>(method, params, timeoutMs);
  }

  async notify(
    ownerId: string,
    serviceId: string,
    method: string,
    params: unknown = {},
  ): Promise<void> {
    await this.peer(ownerId, serviceId).notify(method, params);
  }

  async download(
    ownerId: string,
    serviceId: string,
    path: string,
    maxBytes: number,
  ): Promise<{ name: string; size: number; stream: Readable }> {
    const peer = this.peer(ownerId, serviceId);
    const metadata = await peer.request<{ name: string; size: number }>(
      "fs.download.stat",
      { path },
      15_000,
    );
    if (metadata.size > maxBytes) {
      throw new AppError(
        413,
        "DOWNLOAD_TOO_LARGE",
        `文件大小 ${metadata.size} bytes，超过 Gateway 限制 ${maxBytes} bytes`,
      );
    }
    const streamId = randomUUID();
    const stream = peer.createDownloadStream(streamId, metadata.size, maxBytes);
    try {
      await peer.request(
        "fs.download.start",
        { path, streamId },
        15_000,
      );
      return { ...metadata, stream };
    } catch (error) {
      stream.destroy(error instanceof Error ? error : new Error(String(error)));
      throw error;
    }
  }

  async upload(
    ownerId: string,
    serviceId: string,
    path: string,
    input: Readable,
  ): Promise<unknown> {
    const peer = this.peer(ownerId, serviceId);
    const streamId = randomUUID();
    await peer.request("fs.upload.open", { path, streamId });
    await peer.sendStream(streamId, input);
    return peer.request("fs.upload.finish", { streamId }, 90_000);
  }

  listPending(ownerId: string, serviceId?: string): Omit<PendingServerRequest, "ownerId">[] {
    return [...this.pendingRequests.values()]
      .filter(
        (request) => request.ownerId === ownerId && (!serviceId || request.serviceId === serviceId),
      )
      .map(({ ownerId: _ownerId, ...request }) => structuredClone(request));
  }

  async respond(ownerId: string, requestId: string, result: unknown): Promise<void> {
    const pending = this.pendingRequests.get(requestId);
    if (!pending || pending.ownerId !== ownerId) {
      throw new ConflictError("该 Codex 请求已不再等待响应");
    }
    await this.request(pending.ownerId, pending.serviceId, "codex.respond", {
      rpcId: pending.rpcId,
      result,
    });
    this.pendingRequests.delete(requestId);
    this.journal.publish(ownerId, "codex.request.resolved", { requestId }, pending.serviceId);
  }

  disconnect(ownerId: string, serviceId: string): void {
    this.peers.get(this.key(ownerId, serviceId))?.close(4002, "服务已从 Gateway 移除");
  }

  async close(): Promise<void> {
    for (const peer of this.peers.values()) peer.close();
    this.peers.clear();
    await this.journal.flush();
  }

  private peer(ownerId: string, serviceId: string): AgentPeer {
    const peer = this.peers.get(this.key(ownerId, serviceId));
    if (!peer) throw new ConflictError("服务器 Agent 当前不在线");
    return peer;
  }

  private handleEvent(peer: AgentPeer, message: AgentEventMessage): void {
    const payload = asRecord(message.payload);
    if (message.event === "codex.notification") {
      const method = typeof payload.method === "string" ? payload.method : "unknown";
      this.journal.publish(peer.ownerId, `codex.${method}`, payload.params ?? {}, peer.serviceId);
      return;
    }
    if (message.event === "codex.request") {
      const requestId = randomUUID();
      const pending: PendingServerRequest = {
        requestId,
        rpcId: normalizeRpcId(payload.rpcId),
        ownerId: peer.ownerId,
        serviceId: peer.serviceId,
        method: typeof payload.method === "string" ? payload.method : "unknown",
        params: payload.params ?? {},
        createdAt: new Date().toISOString(),
      };
      this.pendingRequests.set(requestId, pending);
      const { ownerId: _ownerId, ...publicRequest } = pending;
      this.journal.publish(peer.ownerId, "codex.request", publicRequest, peer.serviceId);
      return;
    }
    if (message.event === "terminal.data" || message.event === "terminal.exit") {
      const terminalId = typeof payload.terminalId === "string" ? payload.terminalId : "";
      if (!terminalId) return;
      const event: TerminalEvent = {
        ownerId: peer.ownerId,
        serviceId: peer.serviceId,
        event: message.event === "terminal.data" ? "data" : "exit",
        terminalId,
        ...(typeof payload.data === "string" ? { data: payload.data } : {}),
        ...(typeof payload.exitCode === "number" ? { exitCode: payload.exitCode } : {}),
      };
      this.emit("terminal", event);
      return;
    }
    this.journal.publish(peer.ownerId, `agent.${message.event}`, message.payload, peer.serviceId);
  }

  private handleClose(peer: AgentPeer, message: string): void {
    const key = this.key(peer.ownerId, peer.serviceId);
    if (this.peers.get(key) !== peer) return;
    this.peers.delete(key);
    for (const [id, request] of this.pendingRequests) {
      if (request.ownerId === peer.ownerId && request.serviceId === peer.serviceId) {
        this.pendingRequests.delete(id);
      }
    }
    this.journal.publish(
      peer.ownerId,
      "service.status",
      { state: "disconnected", message },
      peer.serviceId,
    );
  }

  private key(ownerId: string, serviceId: string): string {
    return `${ownerId}:${serviceId}`;
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? value as Record<string, unknown> : {};
}

function normalizeRpcId(value: unknown): number | string {
  if (typeof value === "number" || typeof value === "string") return value;
  throw new Error("Agent sent an invalid Codex request id");
}

export type { TerminalEvent };
