import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { WebSocketServer } from "ws";
import { ChunkedTransport } from "../transport/websocket.cjs";

const bundle = resolve(process.argv[2] ?? "release/windows/CodexRemote/app/agent.mjs");
const node = process.platform === "win32" ? resolve("release/windows/CodexRemote/runtime/node.exe") : process.execPath;
const directory = await mkdtemp(join(tmpdir(), "packaged-agent 中文 "));
const token = randomUUID();
const server = new WebSocketServer({ host: "127.0.0.1", port: 0, perMessageDeflate: true });
await once(server, "listening");
const config = join(directory, "agent.json");
await writeFile(config, JSON.stringify({ version: 1, gatewayUrl: `http://127.0.0.1:${server.address().port}`,
  token, name: "Packaged Agent", serviceId: randomUUID(), codexExecutable: node }));
let accepted = 0, wire, peer, compressed = false;
const responses = new Map(), streams = new Map();
server.on("connection", (socket, request) => {
  accepted++;
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  peer = socket;
  compressed = socket.extensions.includes("permessage-deflate");
  wire = new ChunkedTransport(socket, { server: true, maxMessageBytes: 128 * 1024 * 1024,
    onMessage: text => {
      const value = JSON.parse(text);
      if (value.type === "response") responses.get(value.id)?.(value);
      if (value.type === "stream") streams.get(value.id)?.chunks.push(Buffer.from(value.data, "base64"));
      if (value.type === "stream.end") streams.get(value.id)?.done();
    },
  });
});
const agent = spawn(node, [bundle, "--desktop-control", "--config", config], {
  stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
});
const exited = once(agent, "exit");
const statuses = [];
let output = "", errors = "";
agent.stdout.on("data", bytes => { output += bytes; });
agent.stderr.on("data", bytes => { errors += bytes; });
createInterface({ input: agent.stdout }).on("line", line => statuses.push(JSON.parse(line)));
try {
  await new Promise(done => setTimeout(done, 200));
  assert.equal(accepted, 0, "Packaged Agent must wait for its owner's start gate");
  agent.stdin.write('{"type":"start"}\n');
  await until(() => wire?.negotiated);
  assert.equal(compressed, true);
  const metadata = await rpc("service.describe");
  assert.equal(metadata.platform, process.platform);
  assert.ok(metadata.capabilities.includes("files"));
  if (process.platform === "win32") {
    assert.equal(metadata.capabilities.includes("terminal"), false);
    await assert.rejects(rpc("terminal.open"));
  }
  const folder = join(directory, "files 中文");
  const file = join(folder, "snapshot 图片.bin");
  await rpc("fs.mkdir", { path: folder });
  await rpc("fs.upload.open", { path: file, streamId: "upload" });
  const bytes = Buffer.alloc(34 * 1024 * 1024);
  Buffer.from("中文🙂 opaque snapshot").copy(bytes);
  // The serialized base64 envelope exceeds 32 MiB, exercising negotiated fragments and compression.
  await wire.send({ type: "stream", id: "upload", data: bytes.toString("base64") });
  await wire.send({ type: "stream.end", id: "upload" });
  assert.equal((await rpc("fs.upload.finish", { streamId: "upload" })).size, bytes.length);
  assert.equal(hash(await readFile(file)), hash(bytes));
  const chunks = [];
  let downloaded;
  const done = new Promise(resolve => { downloaded = resolve; });
  streams.set("download", { chunks, done: downloaded });
  await rpc("fs.download.start", { path: file, streamId: "download" });
  await Promise.race([done, timeout(60_000)]);
  assert.equal(hash(Buffer.concat(chunks)), hash(bytes));
  const listed = await rpc("fs.list", { path: folder });
  assert.equal(listed[0].path, file);
  agent.stdin.write('{"type":"stop"}\n');
  const [code] = await Promise.race([exited, timeout(10_000)]);
  assert.equal(code, 0);
  assert.ok(statuses.some(value => value.state === "connected"));
  assert.equal(statuses.at(-1).state, "stopped");
  assert.equal((output + errors).includes(token), false);
  console.log("Packaged Node Agent: startup gate, UTF-8 paths, compressed fragmented transfer, download integrity and stop passed");
} finally {
  if (agent.exitCode === null && agent.signalCode === null) agent.kill("SIGKILL");
  wire?.dispose();
  peer?.terminate();
  await new Promise(done => server.close(done));
  await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}

function hash(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function timeout(ms) { return new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error("Packaged Agent timed out")), ms); timer.unref(); }); }
async function until(check) {
  const deadline = Date.now() + 15_000;
  while (!check()) { assert.ok(Date.now() < deadline, `Packaged Agent did not connect: ${errors}`); await new Promise(done => setTimeout(done, 25)); }
}
async function rpc(method, params = {}) {
  const id = randomUUID();
  const reply = new Promise(done => responses.set(id, done));
  try {
    await wire.send({ type: "request", id, method, params });
    const response = await Promise.race([reply, timeout(60_000)]);
    if (!response.ok) throw new Error(response.error.message);
    return response.result;
  } finally { responses.delete(id); }
}
