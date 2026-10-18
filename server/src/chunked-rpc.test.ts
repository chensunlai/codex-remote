import { once } from "node:events";
import { randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { expect, it } from "vitest";
import { buildGateway } from "./app.js";
import { ChunkedTransport, CHUNK_BYTES } from "../../transport/websocket.cjs";

it("does not spend the RPC response deadline while a large response is progressing", async () => {
  const directory = await mkdtemp(join(tmpdir(), "chunked-rpc-"));
  const token = randomUUID(), serviceId = randomUUID();
  const gateway = await buildGateway({ host: "127.0.0.1", port: 6767, dataDirectory: directory, apiTokens: [token], maxUploadBytes: 10, maxDownloadBytes: 10 });
  await gateway.app.listen({ host: "127.0.0.1", port: 0 });
  const address = gateway.app.server.address() as { port: number };
  const socket = new WebSocket(`ws://127.0.0.1:${address.port}/agent/v2/connect?${new URLSearchParams({ serviceId, name: "pause-test" })}`, { headers: { Authorization: `Bearer ${token}` } });
  socket.on("error", () => {});
  const transport = new ChunkedTransport(socket);
  let described!: () => void;
  const description = new Promise<void>(resolve => { described = resolve; });
  let started!: () => void;
  const receiving = new Promise<void>(resolve => { started = resolve; });
  transport.onMessage = text => {
    const request = JSON.parse(text);
    if (request.method === "service.describe") { void transport.send({ type: "response", id: request.id, ok: true, result: {} }).then(described); return; }
    const bytes = Buffer.from(JSON.stringify({ type: "response", id: request.id, ok: true, result: "x".repeat(CHUNK_BYTES) }));
    function send(offset: number, end: number) {
      const part = Buffer.alloc(16 + end - offset); part.write("CRW1"); part.writeUInt32BE(1, 4); part.writeUInt32BE(offset, 8); part.writeUInt32BE(bytes.length, 12); bytes.copy(part, 16, offset, end); socket.send(part);
    }
    send(0, 500_000); started();
    setTimeout(() => send(500_000, 800_000), 80);
    setTimeout(() => send(800_000, bytes.length), 160);
  };
  try {
    await transport.ready;
    await description;
    const owner = (await gateway.services.tokens.verify(token))!;
    while (!gateway.services.agents.isOnline(owner, serviceId)) await new Promise(resolve => setTimeout(resolve, 5));
    const response = gateway.services.agents.request<string>(owner, serviceId, "large-response", {}, 50);
    await receiving;
    expect((await response).length).toBe(CHUNK_BYTES);
  } finally {
    if (socket.readyState !== socket.CLOSED) { const close = once(socket, "close"); socket.terminate(); await close; }
    await gateway.app.close();
    await rm(directory, { recursive: true, force: true });
  }
});
