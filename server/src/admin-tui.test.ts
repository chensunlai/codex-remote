import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { runGatewayAdminTui, type GatewayAdminUi } from "./admin-tui.js";
import type { ServerConfig } from "./config.js";
import { ServiceStore } from "./service-store.js";
import { TokenStore } from "./token-store.js";

describe("Gateway admin TUI", () => {
  it("creates a token from the nested management menu", async () => {
    await withGateway(async (config) => {
      const ui = new FakeAdminUi({
        select: ["tokens", "create", "back", "exit"],
        text: ["phone"],
      });

      await runGatewayAdminTui(config, ui);

      const store = await TokenStore.open(join(config.dataDirectory, "tokens.json"));
      expect(await store.list()).toEqual([
        expect.objectContaining({ label: "phone" }),
      ]);
      expect(ui.notes.some((note) => note.includes("请立即保存"))).toBe(true);
      expect(ui.outros).toEqual(["Gateway 管理完成"]);
    });
  });

  it("removes a selected offline service after confirmation", async () => {
    await withGateway(async (config) => {
      const store = new ServiceStore(join(config.dataDirectory, "services.json"));
      await store.register("owner-1", "service-1", "build-server", {
        hostname: "fixture-host",
        platform: "linux",
        arch: "x64",
        agentVersion: "0.3.1",
        home: "/home/fixture",
        capabilities: [],
      });
      const ui = new FakeAdminUi({
        select: ["services", "remove", "0", "back", "exit"],
        confirm: [true],
      });

      await runGatewayAdminTui(config, ui);

      expect(await new ServiceStore(join(config.dataDirectory, "services.json")).listAll())
        .toEqual([]);
      expect(ui.successes).toEqual(["已删除服务记录：build-server"]);
    });
  });
});

async function withGateway(test: (config: ServerConfig) => Promise<void>): Promise<void> {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-admin-tui-"));
  try {
    await test({
      host: "127.0.0.1",
      port: 6767,
      dataDirectory: directory,
      apiTokens: [],
      maxDownloadBytes: 10 * 1024 * 1024,
      maxUploadBytes: 512 * 1024 * 1024,
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

class FakeAdminUi implements GatewayAdminUi {
  readonly notes: string[] = [];
  readonly outros: string[] = [];
  readonly successes: string[] = [];

  constructor(private readonly answers: {
    select?: string[];
    text?: string[];
    confirm?: boolean[];
  }) {}

  intro(): void {}

  note(message: string, title?: string): void {
    this.notes.push(`${title ?? ""}\n${message}`);
  }

  outro(message: string): void {
    this.outros.push(message);
  }

  cancel(): void {}

  info(): void {}

  success(message: string): void {
    this.successes.push(message);
  }

  warning(): void {}

  error(message: string): void {
    throw new Error(message);
  }

  async select(): Promise<string> {
    return this.answers.select?.shift() ?? "exit";
  }

  async text(): Promise<string> {
    return this.answers.text?.shift() ?? "user";
  }

  async confirm(): Promise<boolean> {
    return this.answers.confirm?.shift() ?? false;
  }

  isCancel(): boolean {
    return false;
  }
}
