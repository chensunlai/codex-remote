import { connect, type Socket } from "node:net";
import { lstat } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";

export type CoordinationMessage = Record<string, unknown>;
const MAX_FRAME_BYTES = 16 * 1024 * 1024 - 1024;

/** Bridges the official local IPC transport; requests and ownership stay in the frontend. */
export class ClientCoordination {
  private socket: Socket | undefined;
  private retry: NodeJS.Timeout | undefined;
  private closed = false;
  private generation = 0;
  private connecting = false;

  constructor(
    private readonly codexHome: string,
    private readonly emit: (message: CoordinationMessage) => void,
    private readonly endpoints?: string[],
  ) {}

  start(): void { void this.open(); }

  send(message: CoordinationMessage): void {
    const socket = this.socket;
    if (!socket || !socket.writable) throw new Error("Official client coordination is disconnected");
    const json = Buffer.from(JSON.stringify(message));
    if (json.length > MAX_FRAME_BYTES || socket.writableLength + json.length > MAX_FRAME_BYTES) {
      throw new Error("Official client coordination message exceeds the transport limit");
    }
    const frame = Buffer.allocUnsafe(4 + json.length);
    frame.writeUInt32LE(json.length, 0);
    json.copy(frame, 4);
    socket.write(frame);
  }

  close(): void {
    this.closed = true;
    this.generation += 1;
    clearTimeout(this.retry);
    this.socket?.destroy();
    this.socket = undefined;
  }

  private async open(): Promise<void> {
    if (this.closed || this.socket || this.connecting) return;
    this.connecting = true;
    const generation = this.generation;
    const uid = process.getuid?.();
    const endpoints = this.endpoints ?? [
      join(this.codexHome || process.env.CODEX_HOME || join(homedir(), ".codex"), "ipc", "ipc.sock"),
      join(tmpdir(), "codex-ipc", uid ? `ipc-${uid}.sock` : "ipc.sock"),
    ];
    for (const endpoint of endpoints) {
      if (this.closed || generation !== this.generation) break;
      try {
        const [file, directory] = await Promise.all([lstat(endpoint), lstat(dirname(endpoint))]);
        if (uid === undefined || !file.isSocket() || file.uid !== uid || !directory.isDirectory()
          || directory.uid !== uid || (directory.mode & 0o022) !== 0) continue;
        const socket = await new Promise<Socket>((resolve, reject) => {
          const peer = connect(endpoint);
          const timer = setTimeout(() => peer.destroy(new Error("IPC connection timed out")), 1000);
          peer.once("error", reject);
          peer.once("close", () => clearTimeout(timer));
          peer.once("connect", () => { clearTimeout(timer); resolve(peer); });
        });
        if (this.closed || generation !== this.generation) { socket.destroy(); break; }
        this.socket = socket;
        this.connecting = false;
        this.read(socket);
        this.emit({ type: "connected" });
        return;
      } catch { /* No official desktop router is running at this endpoint. */ }
    }
    this.connecting = false;
    if (!this.closed && generation === this.generation) {
      this.emit({ type: "unavailable" });
      this.schedule();
    }
  }

  private read(socket: Socket): void {
    let buffer = Buffer.alloc(0);
    socket.on("data", (chunk: Buffer) => {
      if (this.socket !== socket) return;
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4) {
        const length = buffer.readUInt32LE(0);
        if (length === 0 || length > MAX_FRAME_BYTES) {
          socket.destroy(new Error("Invalid official IPC frame length"));
          return;
        }
        if (buffer.length < length + 4) return;
        try {
          const message: unknown = JSON.parse(buffer.subarray(4, length + 4).toString("utf8"));
          if (!message || typeof message !== "object" || Array.isArray(message)) throw new Error("Invalid IPC message");
          this.emit({ type: "message", message });
        } catch { socket.destroy(new Error("Invalid official IPC message")); return; }
        buffer = buffer.subarray(length + 4);
      }
    });
    socket.on("error", () => {});
    socket.on("close", () => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      this.emit({ type: "disconnected" });
      this.schedule();
    });
  }

  private schedule(): void {
    if (this.closed) return;
    clearTimeout(this.retry);
    this.retry = setTimeout(() => void this.open(), 2500);
    this.retry.unref();
  }
}
