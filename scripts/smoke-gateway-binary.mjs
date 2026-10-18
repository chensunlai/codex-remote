#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once, EventEmitter } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer, createConnection } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import WebSocket from "ws";
import { ChunkedTransport, WIRE_LIMIT } from "../transport/websocket.cjs";

assert.ok(process.argv[2], "Usage: node scripts/smoke-gateway-binary.mjs <gateway-binary>");
const compression = !process.argv.includes("--no-compression");
const network = new WeakMap();
const directory = await mkdtemp(join(tmpdir(), "gateway-binary-smoke-"));
const token = randomUUID();
const headers = { Authorization: `Bearer ${token}` };
const listener = createServer().listen(0, "127.0.0.1");
await once(listener, "listening");
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const base = `http://127.0.0.1:${port}`;
const gateway = spawn(resolve(process.argv[2]), ["start"], {
  env: { ...process.env, HOST: "127.0.0.1", PORT: String(port), LOG_LEVEL: "silent",
    CODEX_REMOTE_API_TOKEN: token, CODEX_REMOTE_API_TOKENS: token, CODEX_REMOTE_DATA_DIR: directory },
  stdio: ["ignore", "ignore", "pipe"],
});
let stderr = "";
gateway.stderr.on("data", chunk => { stderr = (stderr + chunk).slice(-8192); });
const exited = once(gateway, "exit");
const sockets = [];
const channels = new WeakMap();
let maxWireBytes = 0;
try {
  await eventually(async () => {
    if (gateway.exitCode !== null) throw new Error(`Gateway exited: ${stderr}`);
    return fetch(`${base}/healthz`).then(r => r.ok).catch(() => false);
  });
  const serviceId = randomUUID();
  const epoch = randomUUID();
  let viewId;
  let sequence = 0;
  const agent = connect(`/agent/v2/connect?${new URLSearchParams({ serviceId, name: "binary-smoke" })}`);
  channels.get(agent).onMessage = raw => {
    const message = JSON.parse(raw);
    let result = {};
    if (message.method === "service.describe") result = { hostname: "fixture", platform: "linux", arch: "x64", home: directory, capabilities: [] };
    if (message.method === "extension.view.attach") {
      viewId = message.params.viewId;
      result = { viewId, epoch, sequence, version: "fixture", origin: "https://fixture.invalid", html: "fixture", frames: [], surfaces: [] };
    }
    if (message.id) void channels.get(agent).send({ type: "response", id: message.id, ok: true, result });
  };
  await channels.get(agent).ready;
  assert.equal(agent.extensions.includes("permessage-deflate"), compression);
  await eventually(async () => {
    const { data } = await (await fetch(`${base}/api/v1/services`, { headers })).json();
    return data.some(service => service.id === serviceId && service.runtime.state === "connected");
  });
  const renderer = connect(`/api/v4/services/${serviceId}/extension?${new URLSearchParams({
    clientId: randomUUID(), rendererId: randomUUID(), workspace: directory,
  })}`);
  assert.equal(JSON.parse(await nextMessage(renderer)).type, "ready");
  assert.equal(renderer.extensions.includes("permessage-deflate"), compression);
  for (const size of [20, 32 - 0.01, 32, 60, 70]) {
    const body = "x".repeat(Math.floor(size * 1024 * 1024));
    const expected = digest(body);
    const before = network.get(renderer).tcp.bytesRead;
    let received = nextMessage(renderer);
    // A paused reader fills native socket buffers and exercises real backpressure.
    // Bun's compatibility implementation previously duplicated accepted fragments here.
    renderer.pause();
    const resume = setTimeout(() => renderer.resume(), 1500);
    await send(body);
    await new Promise(resolve => setTimeout(resolve, 1600));
    clearTimeout(resume); renderer.resume();
    const message = JSON.parse(await received);
    assert.equal(message.type, "message");
    assert.equal(digest(message.payload[1]), expected);
    const wireBytes = network.get(renderer).tcp.bytesRead - before;
    if (compression) assert.ok(wireBytes < body.length / 10, `Compression inactive: ${wireBytes}`);
    else assert.ok(wireBytes >= body.length);
    await channels.get(renderer).send({ type: "ack", sequence: message.sequence });
    received = nextMessage(renderer);
    await send("subsequent update");
    assert.equal(JSON.parse(await received).payload[1], "subsequent update");
    assert.equal(agent.readyState, WebSocket.OPEN);
    console.log(`${size} MiB snapshot and subsequent update passed (${compression ? "compressed" : "plain"}: ${wireBytes} wire bytes)`);
  }
  assert.ok(maxWireBytes <= WIRE_LIMIT, `Unexpected network message size: ${maxWireBytes}`);
  // Verify the actual Bun server still rejects a single over-32 MiB wire message.
  const legacy = new WebSocket(base.replace("http:", "ws:") + `/agent/v1/connect?${new URLSearchParams({ serviceId: randomUUID(), name: "wire-limit" })}`, { headers });
  sockets.push(legacy); legacy.on("error", () => {});
  await once(legacy, "open");
  const closed = once(legacy, "close");
  legacy.send("x".repeat(33 * 1024 * 1024));
  await Promise.race([closed, timeout(10_000)]);
  assert.equal((await fetch(`${base}/healthz`)).ok, true);
  console.log(`Wire messages <= ${maxWireBytes} bytes; over-32 MiB rejected; Gateway healthy`);

  function send(body) {
    return channels.get(agent).send({ type: "event", event: "extension.view", payload: {
      viewId, epoch, sequence: ++sequence, type: "message", payload: ["string", body],
    } });
  }
} finally {
  for (const socket of sockets) socket.terminate();
  if (gateway.exitCode === null && gateway.signalCode === null) {
    gateway.kill("SIGTERM");
    const force = setTimeout(() => gateway.kill("SIGKILL"), 5000);
    await exited;
    clearTimeout(force);
  }
  await rm(directory, { recursive: true, force: true });
}

function connect(path) {
  const metric = {};
  const socket = new WebSocket(base.replace("http:", "ws:") + path, { headers, maxPayload: WIRE_LIMIT,
    perMessageDeflate: compression,
    createConnection(options) { metric.tcp = createConnection(options); return metric.tcp; },
  });
  network.set(socket, metric);
  // Awaited operations below report failures; retain a handler during cleanup.
  socket.on("error", () => {});
  sockets.push(socket);
  socket.on("message", raw => { maxWireBytes = Math.max(maxWireBytes, raw.length); });
  const transport = new ChunkedTransport(socket);
  const messages = new EventEmitter();
  transport.onMessage = text => messages.emit("complete", text);
  transport.messages = messages;
  channels.set(socket, transport);
  return socket;
}

function nextMessage(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error("WebSocket message timeout")), 10_000);
    const onMessage = raw => finish(null, raw.toString());
    const onClose = (code, reason) => finish(new Error(`Unexpected disconnect: ${code} ${reason}`));
    const onError = error => finish(error);
    function finish(error, value) {
      clearTimeout(timer);
      channels.get(socket).messages.off("complete", onMessage);
      socket.off("close", onClose).off("error", onError);
      if (error) reject(error); else resolve(value);
    }
    channels.get(socket).messages.once("complete", onMessage);
    socket.once("close", onClose).once("error", onError);
  });
}

function digest(value) { return createHash("sha256").update(value).digest("hex"); }
function timeout(ms) { return new Promise((_, reject) => setTimeout(() => reject(new Error("Timed out")), ms).unref()); }
async function eventually(check) {
  const deadline = Date.now() + 10_000;
  while (!await check()) {
    assert.ok(Date.now() < deadline, "Gateway startup timed out");
    await new Promise(resolve => setTimeout(resolve, 50));
  }
}
