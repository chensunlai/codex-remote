import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  configPathBesideProgram,
  loadAgentConfig,
  normalizeGatewayUrl,
  toAgentWebSocketUrl,
} from "./config.js";

describe("Agent configuration", () => {
  it("stores its default configuration beside the program", () => {
    expect(configPathBesideProgram("/srv/codex-remote/codex-remote-agent"))
      .toBe("/srv/codex-remote/agent.json");
  });

  it("uses normal scheme ports and maps HTTPS to WSS", () => {
    expect(normalizeGatewayUrl("gateway.example.com")).toBe("https://gateway.example.com");
    const socket = toAgentWebSocketUrl({
      gatewayUrl: "https://gateway.example.com",
      token: "token",
      name: "service",
      serviceId: "8b173ede-4038-45c7-a5f4-60f0c1e95bd5",
      codexExecutable: "codex",
      configPath: "/tmp/unused",
    });
    expect(socket.origin).toBe("wss://gateway.example.com");
    expect(socket.port).toBe("");
    expect(socket.pathname).toBe("/agent/v1/connect");
  });

  it("preserves service identity when connection settings are reconfigured", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-agent-test-"));
    const path = join(directory, "agent.json");
    try {
      const first = await loadAgentConfig([
        "--gateway", "http://127.0.0.1:6767",
        "--token", "first-token",
        "--name", "first-name",
        "--config", path,
      ], {});
      const second = await loadAgentConfig([
        "--configure",
        "--gateway", "https://gateway.example.com",
        "--token", "second-token",
        "--name", "second-name",
        "--config", path,
      ], {});
      expect(second.serviceId).toBe(first.serviceId);
      expect(JSON.parse(await readFile(path, "utf8"))).toEqual(
        expect.objectContaining({ token: "second-token", name: "second-name" }),
      );
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
