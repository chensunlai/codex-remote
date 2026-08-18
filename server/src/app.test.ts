import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import WebSocket, { type RawData } from "ws";
import { buildGateway, type BuiltGateway } from "./app.js";
import type { ServerConfig } from "./config.js";
import { APP_VERSION } from "./version.js";
import { TokenStore } from "./token-store.js";

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
    const health = await fetch(`${baseUrl}/healthz`);
    expect(await health.json()).toEqual({ status: "ok", version: APP_VERSION });

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

  it.each([
    ["structured RPC error", "unmaterialized-thread"],
    ["legacy Agent error", "legacy-unmaterialized-thread"],
  ])("returns an empty history for %s", async (_case, threadId) => {
    agent = await MockAgent.connect(baseUrl, TOKEN_A);

    const response = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}`,
      TOKEN_A,
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      data: {
        thread: {
          id: threadId,
          cwd: "/home/fixture",
          status: { type: "notLoaded" },
          turns: [],
        },
      },
    });
    expect(agent.codexRequests).toEqual([
      {
        method: "thread/read",
        params: { threadId, includeTurns: true },
      },
      {
        method: "thread/read",
        params: { threadId },
      },
    ]);
  });

  it("applies token changes from the admin process without restarting", async () => {
    const admin = await TokenStore.open(join(directory, "tokens.json"));
    const created = await admin.create("tablet");

    expect((await api("/api/v1/meta", created.token)).status).toBe(200);
    await admin.revoke(created.id);
    expect((await api("/api/v1/meta", created.token)).status).toBe(401);
  });

  it("maps rich client operations to Codex App Server RPCs", async () => {
    agent = await MockAgent.connect(baseUrl, TOKEN_A);
    const threadId = "thread-rich-client";

    const created = await api(
      `/api/v1/services/${SERVICE_ID}/sessions`,
      TOKEN_A,
      jsonBody({
        cwd: "/home/fixture/project",
        model: "fixture-model",
        effort: "high",
        permissions: ":workspace",
      }),
    );
    expect(created.status).toBe(201);

    const sessions = await api(
      `/api/v1/services/${SERVICE_ID}/sessions?archived=true&searchTerm=review`,
      TOKEN_A,
    );
    expect(sessions.status).toBe(200);

    const skills = await api(
      `/api/v1/services/${SERVICE_ID}/skills?cwd=%2Fhome%2Ffixture%2Fproject`,
      TOKEN_A,
    );
    expect(skills.status).toBe(200);

    const permissionProfiles = await api(
      `/api/v1/services/${SERVICE_ID}/permission-profiles?cwd=%2Fhome%2Ffixture%2Fproject`,
      TOKEN_A,
    );
    expect(permissionProfiles.status).toBe(200);
    expect(await permissionProfiles.json()).toEqual({
      data: {
        data: [{ id: ":workspace", description: "Workspace access", allowed: true }],
        nextCursor: null,
      },
    });

    const namedSettings = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/settings`,
      TOKEN_A,
      jsonBody({ model: "fixture-model", effort: "max", permissions: ":workspace" }, "PUT"),
    );
    expect(namedSettings.status).toBe(200);

    const sandboxSettings = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/settings`,
      TOKEN_A,
      jsonBody({
        cwd: "/home/fixture/project",
        approvalPolicy: "never",
        sandbox: "read-only",
        networkAccess: false,
      }, "PUT"),
    );
    expect(sandboxSettings.status).toBe(200);

    const currentGoal = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/goal`,
      TOKEN_A,
    );
    expect(currentGoal.status).toBe(200);
    expect(await currentGoal.json()).toEqual({ data: { goal: null } });

    const goal = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/goal`,
      TOKEN_A,
      jsonBody({ objective: "Ship the fixture" }, "PUT"),
    );
    expect(goal.status).toBe(200);
    expect((await goal.json() as { data: { goal: { objective: string } } }).data.goal.objective)
      .toBe("Ship the fixture");

    const clearedGoal = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/goal`,
      TOKEN_A,
      { method: "DELETE" },
    );
    expect(clearedGoal.status).toBe(200);

    const turn = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/turns`,
      TOKEN_A,
      jsonBody({
        text: "review this",
        model: "fixture-model",
        effort: "high",
        context: [
          { type: "mention", name: "app.ts", path: "/home/fixture/project/app.ts" },
          { type: "localImage", path: "/home/fixture/project/screenshot.png" },
          { type: "skill", name: "review", path: "/home/fixture/.codex/skills/review" },
        ],
      }),
    );
    expect(turn.status).toBe(202);

    for (const action of ["unarchive", "compact"]) {
      const response = await api(
        `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/${action}`,
        TOKEN_A,
        jsonBody({}),
      );
      expect(response.status).toBe(200);
    }

    const review = await api(
      `/api/v1/services/${SERVICE_ID}/sessions/${threadId}/review`,
      TOKEN_A,
      jsonBody({ target: { type: "uncommittedChanges" } }),
    );
    expect(review.status).toBe(200);

    expect(agent.codexRequests).toEqual([
      {
        method: "thread/start",
        params: {
          cwd: "/home/fixture/project",
          model: "fixture-model",
          approvalPolicy: "on-request",
          permissions: ":workspace",
          serviceName: "codex_remote_android",
          config: { model_reasoning_effort: "high" },
        },
      },
      {
        method: "thread/list",
        params: expect.objectContaining({ archived: true, searchTerm: "review" }),
      },
      {
        method: "skills/list",
        params: { cwds: ["/home/fixture/project"], forceReload: false },
      },
      {
        method: "permissionProfile/list",
        params: { cwd: "/home/fixture/project" },
      },
      {
        method: "thread/settings/update",
        params: {
          threadId,
          model: "fixture-model",
          effort: "max",
          permissions: ":workspace",
        },
      },
      {
        method: "thread/settings/update",
        params: {
          threadId,
          cwd: "/home/fixture/project",
          approvalPolicy: "never",
          sandboxPolicy: { type: "readOnly", networkAccess: false },
        },
      },
      { method: "thread/goal/get", params: { threadId } },
      {
        method: "thread/goal/set",
        params: { threadId, objective: "Ship the fixture" },
      },
      { method: "thread/goal/clear", params: { threadId } },
      {
        method: "turn/start",
        params: {
          threadId,
          input: [
            { type: "text", text: "review this", text_elements: [] },
            { type: "mention", name: "app.ts", path: "/home/fixture/project/app.ts" },
            { type: "localImage", path: "/home/fixture/project/screenshot.png" },
            { type: "skill", name: "review", path: "/home/fixture/.codex/skills/review" },
          ],
          model: "fixture-model",
          effort: "high",
        },
      },
      { method: "thread/unarchive", params: { threadId } },
      { method: "thread/compact/start", params: { threadId } },
      {
        method: "review/start",
        params: { threadId, target: { type: "uncommittedChanges" } },
      },
    ]);
  });

  function api(path: string, token: string, init?: RequestInit): Promise<Response> {
    return fetch(`${baseUrl}${path}`, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    });
  }

  function jsonBody(body: unknown, method = "POST"): RequestInit {
    return {
      method,
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    };
  }
});

class MockAgent {
  readonly downloadStarts: string[] = [];
  readonly codexRequests: Array<{ method: string; params: Record<string, unknown> }> = [];

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
        agentVersion: "fixture-version",
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
    } else if (message.method === "codex.rpc") {
      const method = String(params.method);
      const rpcParams = (params.params ?? {}) as Record<string, unknown>;
      this.codexRequests.push({ method, params: rpcParams });
      if (method === "thread/read" && rpcParams.includeTurns === true) {
        const legacy = rpcParams.threadId === "legacy-unmaterialized-thread";
        this.send({
          type: "response",
          id: message.id,
          ok: false,
          error: {
            code: legacy ? "AGENT_OPERATION_FAILED" : "CODEX_RPC_ERROR",
            message: "Codex RPC -32600: includeTurns is unavailable before first user message",
            ...(legacy ? {} : { details: { rpcCode: -32600 } }),
          },
        });
      } else if (method === "thread/read") {
        this.respond(message.id!, {
          thread: {
            id: String(rpcParams.threadId),
            cwd: "/home/fixture",
            status: { type: "notLoaded" },
          },
        });
      } else if (method === "thread/list" || method === "skills/list") {
        this.respond(message.id!, { data: [] });
      } else if (method === "permissionProfile/list") {
        this.respond(message.id!, {
          data: [{ id: ":workspace", description: "Workspace access", allowed: true }],
          nextCursor: null,
        });
      } else if (method === "thread/start") {
        this.respond(message.id!, {
          thread: {
            id: "thread-created",
            cwd: "/home/fixture/project",
            status: { type: "idle" },
            turns: [],
          },
        });
      } else if (method === "thread/settings/update") {
        this.respond(message.id!, {});
      } else if (method === "thread/goal/get") {
        this.respond(message.id!, { goal: null });
      } else if (method === "thread/goal/set") {
        this.respond(message.id!, {
          goal: {
            threadId: String(rpcParams.threadId),
            objective: String(rpcParams.objective),
            status: "active",
            tokenBudget: null,
            tokensUsed: 0,
            timeUsedSeconds: 0,
            createdAt: 1,
            updatedAt: 1,
          },
        });
      } else if (method === "thread/goal/clear") {
        this.respond(message.id!, { cleared: true });
      } else if (method === "turn/start" || method === "review/start") {
        this.respond(message.id!, { turn: { id: "turn-1" } });
      } else if (method === "thread/unarchive" || method === "thread/compact/start") {
        this.respond(message.id!, {});
      } else {
        this.send({
          type: "response",
          id: message.id,
          ok: false,
          error: { code: "CODEX_RPC_ERROR", message: `Unsupported Codex RPC: ${method}` },
        });
      }
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
