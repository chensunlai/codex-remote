import { describe, expect, it } from "vitest";
import { normalizeGatewayUrl } from "./config.js";
import {
  AgentSetupCancelledError,
  promptAgentSetup,
  type AgentSetupUi,
} from "./setup-tui.js";

describe("Agent setup TUI", () => {
  it("collects and confirms a masked interactive configuration", async () => {
    const ui = new FakeSetupUi({
      text: ["gateway.example.com", "build-server", "/opt/codex/bin/codex"],
      password: ["secret-token"],
      confirm: [true],
    });

    const result = await promptAgentSetup(
      { configPath: "/srv/codex-remote/agent.json" },
      normalizeGatewayUrl,
      ui,
    );

    expect(result).toEqual({
      gatewayUrl: "https://gateway.example.com",
      token: "secret-token",
      name: "build-server",
      codexExecutable: "/opt/codex/bin/codex",
    });
    expect(ui.intros).toEqual(["Codex Remote Agent"]);
    expect(ui.notes.join("\n")).toContain("build-server");
    expect(ui.notes.join("\n")).not.toContain("secret-token");
    expect(ui.outros).toEqual(["配置已确认，正在连接 Gateway"]);
  });

  it("exits cleanly when confirmation is rejected", async () => {
    const ui = new FakeSetupUi({ confirm: [false] });

    await expect(promptAgentSetup({
      gatewayUrl: "https://gateway.example.com",
      token: "secret-token",
      name: "build-server",
      codexExecutable: "codex",
      configPath: "/srv/codex-remote/agent.json",
    }, normalizeGatewayUrl, ui)).rejects.toBeInstanceOf(AgentSetupCancelledError);

    expect(ui.cancellations).toEqual(["已取消配置"]);
  });
});

class FakeSetupUi implements AgentSetupUi {
  readonly intros: string[] = [];
  readonly notes: string[] = [];
  readonly outros: string[] = [];
  readonly cancellations: string[] = [];

  constructor(private readonly answers: {
    text?: string[];
    password?: string[];
    confirm?: boolean[];
  }) {}

  intro(message: string): void {
    this.intros.push(message);
  }

  note(message: string): void {
    this.notes.push(message);
  }

  outro(message: string): void {
    this.outros.push(message);
  }

  cancel(message: string): void {
    this.cancellations.push(message);
  }

  async text(): Promise<string> {
    return this.answers.text?.shift() ?? "";
  }

  async password(): Promise<string> {
    return this.answers.password?.shift() ?? "";
  }

  async confirm(): Promise<boolean> {
    return this.answers.confirm?.shift() ?? true;
  }

  isCancel(): boolean {
    return false;
  }
}
