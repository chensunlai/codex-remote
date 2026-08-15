import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket, { type RawData } from "ws";
import { buildGateway, type BuiltGateway } from "./app.js";
import type { ServerConfig } from "./config.js";

const TOKEN_A = "test-token-a-with-enough-entropy";
const TOKEN_B = "test-token-b-with-enough-entropy";
const SERVICE_ID = "8b173ede-4038-45c7-a5f4-60f0c1e95bd5";
const SMALL_FILE = Buffer.from("hello from agent\n", "utf8");

describe("Gateway Agent relay", () => {
  let directory: string;
  let gateway: BuiltGateway;
  let baseUrl: string;
  let agent: MockAgent | undefined;

  beforeEach(async () => {
    process.env.LOG_LEVEL = "silent";
    directory = await mkdtemp(join(tmpdir(), "codex-remote-test-"));
    const config: ServerConfig = {
      host: "127.0.0.1",
      port: 6767,
      dataDirectory: directory,
      apiTokens: [TOKEN_A, TOKEN_B],
      maxUploadBytes: 1024 * 1024,
      maxDownloadBytes: 32,
    };
    gateway = await buildGateway(config);
    await gateway.app.listen({ host: "127.0.0.1", port: 0 });
    const address = gateway.app.server.address();
    if (!address || typeof address === "string") throw new Error("Gateway did not bind TCP");
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => {
    agent?.close();
    await gateway.app.close();
    await rm(directory, { recursive: true, force: true });
    delete process.env.LOG_LEVEL;
  });

  it("isolates users and relays files and terminal data", async () => {
    const unauthorized = await fetch(`${baseUrl}/api/v1/meta`);
    expect(unauthorized.status).toBe(401);

    agent = await MockAgent.connect(baseUrl, TOKEN_A);
    await eventually(async () => {
      const response = await api("/api/v1/services", TOKEN_A);
      const body = await response.json() as {
        data: Array<{ hostname?: string; runtime: { state: string } }>;
      };
      return body.data[0]?.runtime.state === "connected" &&
        body.data[0]?.hostname === "fixture-host";
    });

    const ownerA = await api("/api/v1/services", TOKEN_A);
    expect(ownerA.headers.get("cache-control")).toBe("no-store");
    const ownerABody = await ownerA.json() as {
      data: Array<{ id: string; name: string; hostname: string }>;
    };
    expect(ownerABody.data).toEqual([
      expect.objectContaining({ id: SERVICE_ID, name: "fixture", hostname: "fixture-host" }),
    ]);

    const ownerB = await api("/api/v1/services", TOKEN_B);
    expect((await ownerB.json() as { data: unknown[] }).data).toEqual([]);

    const small = await api(
      `/api/v1/services/${SERVICE_ID}/fs/download?path=%2Fsmall.txt`,
      TOKEN_A,
    );
    expect(small.status).toBe(200);
    expect(Buffer.from(await small.arrayBuffer())).toEqual(SMALL_FILE);

    const preview = await api(
      `/api/v1/services/${SERVICE_ID}/fs/preview?path=%2Fsmall.txt`,
      TOKEN_A,
    );
    expect(preview.status).toBe(200);
    expect(await preview.text()).toBe(SMALL_FILE.toString("utf8"));

    const large = await api(
      `/api/v1/services/${SERVICE_ID}/fs/download?path=%2Flarge.bin`,
      TOKEN_A,
    );
    expect(large.status).toBe(413);
    expect(await large.json()).toEqual({
      error: expect.objectContaining({ code: "DOWNLOAD_TOO_LARGE" }),
    });
    expect(agent.downloadStarts).not.toContain("/large.bin");

    const deleteOnline = await api(`/api/v1/services/${SERVICE_ID}`, TOKEN_A, {
      method: "DELETE",
    });
    expect(deleteOnline.status).toBe(409);

    const terminal = new WebSocket(
      `${baseUrl.replace("http:", "ws:")}/api/v1/services/${SERVICE_ID}/terminal?cols=100&rows=30`,
      { headers: { Authorization: `Bearer ${TOKEN_A}` } },
    );
    const terminalMessages = new MessageQueue(terminal);
    await onceOpen(terminal);
    expect(await terminalMessages.next("ready")).toEqual(
      expect.objectContaining({ type: "ready", terminalId: "terminal-1" }),
    );
    terminal.send(JSON.stringify({ type: "input", data: "echo relay\n" }));
    expect(await terminalMessages.next("data")).toEqual({
      type: "data",
      data: "echo relay\n",
    });
    terminal.close();
  });

  function api(path: string, token: string, init?: RequestInit): Promise<Response> {
    return fetch(`${baseUrl}${path}`, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    });
  }
});

class MockAgent {
  readonly downloadStarts: string[] = [];

  private constructor(private readonly socket: WebSocket) {
    socket.on("message", (raw, binary) => {
      if (!binary) this.handle(raw);
    });
  }

  static async connect(baseUrl: string, token: string): Promise<MockAgent> {
    const url = new URL("/agent/v1/connect", baseUrl.replace("http:", "ws:"));
    url.searchParams.set("serviceId", SERVICE_ID);
    url.searchParams.set("name", "fixture");
    const socket = new WebSocket(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const agent = new MockAgent(socket);
    await onceOpen(socket);
    return agent;
  }

  close(): void {
    this.socket.close();
  }

  private handle(raw: RawData): void {
    const message = JSON.parse(raw.toString()) as {
      type: "request" | "notify";
      id?: string;
      method: string;
      params?: Record<string, unknown>;
    };
    if (message.type === "notify") {
      if (message.method === "terminal.write") {
        this.send({
          type: "event",
          event: "terminal.data",
          payload: {
            terminalId: message.params?.terminalId,
            data: message.params?.data,
          },
        });
      }
      return;
    }

    const params = message.params ?? {};
    if (message.method === "service.describe") {
      this.respond(message.id!, {
        hostname: "fixture-host",
        platform: "linux",
        arch: "x64",
        agentVersion: "0.2.0",
        home: "/home/fixture",
        capabilities: ["terminal", "files"],
      });
    } else if (message.method === "fs.download.stat") {
      const path = String(params.path);
      this.respond(message.id!, {
        name: path.slice(1),
        size: path === "/large.bin" ? 64 : SMALL_FILE.length,
      });
    } else if (message.method === "fs.download.start") {
      const path = String(params.path);
      const streamId = String(params.streamId);
      this.downloadStarts.push(path);
      this.respond(message.id!, {});
      queueMicrotask(() => {
        this.send({ type: "stream", id: streamId, data: SMALL_FILE.toString("base64") });
        this.send({ type: "stream.end", id: streamId });
      });
    } else if (message.method === "terminal.open") {
      this.respond(message.id!, { terminalId: "terminal-1" });
    } else {
      this.send({
        type: "response",
        id: message.id,
        ok: false,
        error: { code: "UNKNOWN", message: message.method },
      });
    }
  }

  private respond(id: string, result: unknown): void {
    this.send({ type: "response", id, ok: true, result });
  }

  private send(message: unknown): void {
    this.socket.send(JSON.stringify(message));
  }
}

class MessageQueue {
  private readonly messages: Array<Record<string, unknown>> = [];
  private readonly waiters: Array<{
    type: string;
    resolve: (message: Record<string, unknown>) => void;
  }> = [];

  constructor(socket: WebSocket) {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString()) as Record<string, unknown>;
      const waiterIndex = this.waiters.findIndex((waiter) => waiter.type === message.type);
      if (waiterIndex >= 0) this.waiters.splice(waiterIndex, 1)[0]!.resolve(message);
      else this.messages.push(message);
    });
  }

  next(type: string): Promise<Record<string, unknown>> {
    const index = this.messages.findIndex((message) => message.type === type);
    if (index >= 0) return Promise.resolve(this.messages.splice(index, 1)[0]!);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${type}`)), 5_000);
      this.waiters.push({
        type,
        resolve: (message) => {
          clearTimeout(timeout);
          resolve(message);
        },
      });
    });
  }
}

function onceOpen(socket: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
}

async function eventually(test: () => Promise<boolean>): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    if (await test()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("Condition was not met before timeout");
}
