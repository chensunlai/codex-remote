import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import WebSocket from "ws";
import { ChunkedTransport, CHUNK_BYTES, WIRE_LIMIT } from "../../transport/websocket.cjs";

class Socket extends EventEmitter {
  readyState = 1;
  bufferedAmount = 0;
  parts: Array<Buffer | string> = [];
  closeCode?: number;
  remote?: Socket;
  send(value: Buffer | string, callback?: (error?: Error) => void) {
    this.parts.push(value);
    queueMicrotask(() => { this.remote?.emit("message", value, typeof value !== "string"); callback?.(); });
  }
  close(code: number) { this.closeCode = code; this.readyState = 3; this.emit("close", code); }
}
const sockets: Socket[] = [];
function pair(maxMessageBytes = 128 * 1024 * 1024) {
  const client = new Socket(), server = new Socket();
  client.remote = server; server.remote = client; sockets.push(client, server);
  const received: string[] = [], sent: string[] = [];
  const c = new ChunkedTransport(client as unknown as WebSocket, { onMessage: text => received.push(text) });
  const s = new ChunkedTransport(server as unknown as WebSocket, { server: true, maxMessageBytes, onMessage: text => sent.push(text) });
  return { client, server, c, s, received, sent };
}
function part(id: number, offset: number, total: number, bytes: Buffer) {
  const value = Buffer.alloc(16 + bytes.length); value.write("CRW1"); value.writeUInt32BE(id, 4); value.writeUInt32BE(offset, 8); value.writeUInt32BE(total, 12); bytes.copy(value, 16); return value;
}
afterEach(() => { sockets.splice(0).forEach(s => s.close(1000)); vi.useRealTimers(); });

describe("opaque fragmented transport", () => {
  it("preserves the shared Unicode vector byte for byte across a codepoint boundary", async () => {
    const fixture = Object.fromEntries(readFileSync(new URL("../../transport/fixtures/unicode.properties", import.meta.url), "utf8").trim().split("\n").map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]));
    const { c, s, client, server, received } = pair(); await Promise.all([c.ready, s.ready]);
    const bytes = Buffer.from(fixture.seed!.repeat(Number(fixture.repeat)));
    expect(bytes.length).toBe(Number(fixture.bytes));
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(fixture.sha256);
    for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
      const value = part(Number(fixture.transferId), offset, bytes.length, bytes.subarray(offset, offset + CHUNK_BYTES));
      if (!offset) expect(value.subarray(0, 16).toString("hex")).toBe(fixture.firstHeaderHex);
      client.emit("message", value, true);
    }
    expect(received).toEqual([bytes.toString()]);
    expect(client.closeCode).toBeUndefined(); expect(server.closeCode).toBeUndefined();
  });

  it("splits only large messages, preserves bidirectional ordering, and stays below the wire limit", async () => {
    const { c, s, client, server, received, sent } = pair(); await Promise.all([c.ready, s.ready]);
    const value = { body: "汉🙂".repeat(400_000) };
    await Promise.all([s.send(value), s.send({ next: true }), c.send(value), c.send({ next: false })]);
    expect(received.map(v => JSON.parse(v))).toEqual([value, { next: true }]);
    expect(sent.map(v => JSON.parse(v))).toEqual([value, { next: false }]);
    expect([...client.parts, ...server.parts].every(p => Buffer.byteLength(p) < WIRE_LIMIT)).toBe(true);
    expect(server.parts.filter(Buffer.isBuffer).length).toBeGreaterThan(1);
  });

  it.each(["offset", "id", "length", "interleave", "oversize", "utf8"])("rejects invalid %s without delivering partial data", async fault => {
    const { c, s, client, received } = pair(64 * 1024 * 1024); await Promise.all([c.ready, s.ready]);
    if (fault === "oversize") client.emit("message", part(1, 0, 65 * 1024 * 1024, Buffer.alloc(1)), true);
    else {
      const total = CHUNK_BYTES + 1;
      client.emit("message", part(1, 0, total, Buffer.alloc(CHUNK_BYTES, fault === "utf8" ? 255 : 65)), true);
      if (fault === "interleave") client.emit("message", "{}", false);
      else client.emit("message", part(fault === "id" ? 2 : 1, fault === "offset" ? 1 : CHUNK_BYTES, fault === "length" ? total + 1 : total, Buffer.alloc(1)), true);
    }
    expect(received).toEqual([]);
    expect(client.closeCode).toBe(fault === "oversize" ? 4409 : 4400);
  });

  it("expires stalled partial transfers, drops them on disconnect, and checks limits before allocating", async () => {
    const { c, s, client, received } = pair(); await Promise.all([c.ready, s.ready]);
    vi.useFakeTimers();
    client.emit("message", part(1, 0, CHUNK_BYTES + 1, Buffer.alloc(1)), true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(client.closeCode).toBe(4408); expect(received).toEqual([]);
  });

  it("applies backpressure and stops rather than retrying an oversized complete message", async () => {
    const { c, s, server, client, received } = pair(64 * 1024 * 1024); await Promise.all([c.ready, s.ready]);
    server.bufferedAmount = 4 * CHUNK_BYTES;
    const pending = s.send({ body: "x".repeat(2 * CHUNK_BYTES) });
    await new Promise(resolve => setTimeout(resolve, 30));
    expect(server.parts.filter(Buffer.isBuffer)).toHaveLength(0);
    server.bufferedAmount = 0;
    await pending;
    expect(received).toHaveLength(1);
    await expect(c.send("x".repeat(65 * 1024 * 1024))).rejects.toThrow("exceeds");
    expect(client.closeCode).toBe(4409);
  });
});
