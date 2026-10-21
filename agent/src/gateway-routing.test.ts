import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:http";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";
import { WebSocketServer, type WebSocket } from "ws";
import { ChunkedTransport } from "../../transport/websocket.cjs";

it("falls back in order and returns to the primary on reconnect without replacing the Agent", async () => {
  const directory = await mkdtemp(join(tmpdir(), "agent-routing-"));
  let primaryAvailable = false;
  const attempts: string[] = [];
  const peers: WebSocket[] = [];
  const wires: ChunkedTransport[] = [];
  const ids: string[] = [];
  const servers = ["primary", "fallback"].map(label => {
    const http = createServer();
    const ws = new WebSocketServer({ noServer: true });
    http.on("upgrade", (req, socket, head) => {
      attempts.push(label);
      if (label === "primary" && !primaryAvailable) {
        socket.end("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
      }
      expect(req.headers.authorization).toBe("Bearer fixture");
      ids.push(new URL(req.url!, "http://fixture").searchParams.get("serviceId")!);
      ws.handleUpgrade(req, socket, head, peer => {
        peers.push(peer);
        wires.push(new ChunkedTransport(peer, { server: true, onMessage() {} }));
      });
    });
    http.listen(0, "127.0.0.1");
    return { http, ws };
  });
  await Promise.all(servers.map(s => once(s.http, "listening")));
  const urls = servers.map(s => `http://127.0.0.1:${(s.http.address() as { port: number }).port}`);
  const configPath = join(directory, "agent.json");
  await writeFile(configPath, JSON.stringify({version: 1, gatewayUrl: urls[0], gatewayUrls: urls,
    token: "fixture", name: "routing", serviceId: "routing-fixture", codexExecutable: process.execPath}));
  const child = spawn(process.execPath, ["--import", "tsx", fileURLToPath(new URL("./index.ts", import.meta.url)),
    "--desktop-control", "--config", configPath], {
    stdio: ["pipe", "pipe", "pipe"],
    env: Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith("CODEX_REMOTE_"))),
  });
  const exited = once(child, "exit");
  let output = "";
  child.stdout.on("data", data => { output += data; });
  child.stderr.resume();
  child.stdin.write('{"type":"start"}\n');
  try {
    await expect.poll(() => peers.length, {timeout: 15_000}).toBe(1);
    expect(attempts).toEqual(["primary", "fallback"]);
    primaryAvailable = true;
    // A working fallback remains connected; no background probing or route switch.
    await new Promise(resolve => setTimeout(resolve, 1200));
    expect(attempts).toEqual(["primary", "fallback"]);
    peers[0]!.close(1000, "fixture reconnect");
    await expect.poll(() => peers.length, {timeout: 10_000}).toBe(2);
    expect(attempts).toEqual(["primary", "fallback", "primary"]);
    expect(new Set(ids).size).toBe(1);
    expect(child.exitCode).toBeNull();
    child.stdin.write('{"type":"stop"}\n');
    await exited;
    expect(output).toContain('"state":"stopped"');
    expect(child.exitCode).toBe(0);
  } finally {
    if(child.exitCode === null) { child.kill(); await exited; }
    wires.forEach(w => w.dispose());
    peers.forEach(p => p.terminate());
    await Promise.all(servers.map(async s => { s.ws.close(); await new Promise<void>(resolve => s.http.close(() => resolve())); }));
    await rm(directory, {recursive: true, force: true});
  }
}, 30_000);
