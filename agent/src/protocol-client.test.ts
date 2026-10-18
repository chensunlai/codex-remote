import { createServer } from "node:http";
import { once } from "node:events";
import { afterEach, expect, it } from "vitest";
import { WebSocketServer } from "ws";
import { ProtocolClient, FatalTransportError } from "./protocol-client.js";

const handlers = { request: async () => ({}), notification: () => {}, stream: () => {} };
const cleanups: Array<() => Promise<void>> = [];
afterEach(async () => { for (const cleanup of cleanups.splice(0)) await cleanup(); });

it.each([404, 426, 401])("reports HTTP %i without hanging in the handshake", async status => {
  const server = createServer((_req, response) => { response.writeHead(status); response.end(); }).listen(0, "127.0.0.1");
  await once(server, "listening");
  cleanups.push(() => new Promise(resolve => server.close(() => resolve())));
  const address = server.address() as { port: number };
  const client = new ProtocolClient(new URL(`ws://127.0.0.1:${address.port}/agent/v2/connect`), "fixture", handlers);
  cleanups.push(async () => client.close());
  if (status === 401) await expect(client.run()).rejects.toThrow("HTTP 401");
  else await expect(client.run()).rejects.toBeInstanceOf(FatalTransportError);
});

it("marks malformed transport configuration as fatal instead of entering reconnect retries", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await once(server, "listening");
  server.on("connection", socket => socket.send(JSON.stringify({ type: "transport-config", protocol: "unknown" })));
  cleanups.push(() => { server.clients.forEach(socket => socket.terminate()); return new Promise(resolve => server.close(() => resolve())); });
  const address = server.address() as { port: number };
  const client = new ProtocolClient(new URL(`ws://127.0.0.1:${address.port}`), "fixture", handlers);
  cleanups.push(async () => client.close());
  await expect(client.run()).rejects.toBeInstanceOf(FatalTransportError);
});
