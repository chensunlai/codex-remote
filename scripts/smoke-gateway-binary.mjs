#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import WebSocket from "ws";

assert.ok(process.argv[2], "Usage: node scripts/smoke-gateway-binary.mjs <gateway-binary>");
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
try {
  await eventually(async () => {
    if (gateway.exitCode !== null) throw new Error(`Gateway exited: ${stderr}`);
    return fetch(`${base}/healthz`).then(r => r.ok).catch(() => false);
  });
  const serviceId = randomUUID();
  const epoch = randomUUID();
  let viewId;
  let sequence = 0;
  const agent = connect(`/agent/v1/connect?${new URLSearchParams({ serviceId, name: "binary-smoke" })}`);
  agent.on("message", raw => {
    const message = JSON.parse(raw);
    let result = {};
    if (message.method === "service.describe") result = { hostname: "fixture", platform: "linux", arch: "x64", home: directory, capabilities: [] };
    if (message.method === "extension.view.attach") {
      viewId = message.params.viewId;
      result = { viewId, epoch, sequence, version: "fixture", origin: "https://fixture.invalid", html: "fixture", frames: [], surfaces: [] };
    }
    if (message.id) agent.send(JSON.stringify({ type: "response", id: message.id, ok: true, result }));
  });
  await once(agent, "open");
  await eventually(async () => {
    const { data } = await (await fetch(`${base}/api/v1/services`, { headers })).json();
    return data.some(service => service.id === serviceId && service.runtime.state === "connected");
  });
  const renderer = connect(`/api/v3/services/${serviceId}/extension?${new URLSearchParams({
    clientId: randomUUID(), rendererId: randomUUID(), workspace: directory,
  })}`);
  assert.equal(JSON.parse(await nextMessage(renderer)).type, "ready");
  for (const size of [20, 60]) {
    const body = "x".repeat(size * 1024 * 1024);
    const expected = digest(body);
    let received = nextMessage(renderer);
    send(body);
    const message = JSON.parse(await received);
    assert.equal(message.type, "message");
    assert.equal(digest(message.payload[1]), expected);
    renderer.send(JSON.stringify({ type: "ack", sequence: message.sequence }));
    received = nextMessage(renderer);
    send("subsequent update");
    assert.equal(JSON.parse(await received).payload[1], "subsequent update");
    assert.equal(agent.readyState, WebSocket.OPEN);
    console.log(`${size} MiB snapshot and subsequent update passed`);
  }
  // The runtime limit must still reject oversize messages before parsing JSON.
  const closed = once(agent, "close");
  agent.send("x".repeat(65 * 1024 * 1024));
  await Promise.race([closed, timeout(10_000)]);
  assert.equal((await fetch(`${base}/healthz`)).ok, true);
  console.log("Over-64 MiB message rejected; Gateway remains healthy");

  function send(body) {
    agent.send(JSON.stringify({ type: "event", event: "extension.view", payload: {
      viewId, epoch, sequence: ++sequence, type: "message", payload: ["string", body],
    } }));
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
  const socket = new WebSocket(base.replace("http:", "ws:") + path, { headers, maxPayload: 64 * 1024 * 1024 });
  // Awaited operations below report failures; retain a handler during cleanup.
  socket.on("error", () => {});
  sockets.push(socket);
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
      socket.off("message", onMessage).off("close", onClose).off("error", onError);
      if (error) reject(error); else resolve(value);
    }
    socket.once("message", onMessage).once("close", onClose).once("error", onError);
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
