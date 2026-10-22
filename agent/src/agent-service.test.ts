import { describe, expect, it, vi } from "vitest";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { AgentService } from "./agent-service.js";
import { APP_VERSION } from "./version.js";
import { OfficialExtensionStore } from "./official-extension-store.js";
import { extensionTools, type ExtensionToolId } from "./extension-tools.js";

describe("Agent service metadata", () => {
  it("reports the package version and available channel/runtime capabilities", async () => {
    const service = new AgentService("codex");
    try {
      await expect(service.request("service.describe", {})).resolves.toEqual(
        expect.objectContaining({
          agentVersion: APP_VERSION,
          capabilities: ["codex-raw-channels", "codex-runtime-control", "official-extension-host-v1", "extension-window-management-v1", "extension-tools-v1", "claude-extension-host-v1", "terminal", "files", "stream-transfer"],
        }),
      );
    } finally {
      service.close();
    }
  });

  it("rejects an unknown tool instead of treating it as Codex", async () => {
    const service = new AgentService("codex");
    try {
      await expect(service.request("extension.status", { toolId: "other" })).rejects.toThrow("不支持");
      await expect(service.request("extension.status", { toolId: null })).rejects.toThrow("不支持");
    } finally { service.close(); }
  });

  it.skipIf(process.platform !== "linux")("runs Codex and Claude independently and scopes management to the selected tool", async () => {
    const directory = await mkdtemp(join(tmpdir(), "extension-tools-test-"));
    const stores = {} as Record<ExtensionToolId, OfficialExtensionStore>;
    const prepare = async (toolId: ExtensionToolId, version: string) => {
      const tool = extensionTools[toolId];
      const source = join(directory, `${toolId}-${version}`);
      await mkdir(source, { recursive: true });
      await writeFile(join(source, "package.json"), JSON.stringify({ ...tool, version, main: "extension.cjs" }));
      await writeFile(join(source, "asset.txt"), `${toolId}:${version}`);
      await writeFile(join(source, "extension.cjs"), `const v=require('vscode');exports.activate=ctx=>{
        ctx.subscriptions.push(v.window.registerWebviewViewProvider(${JSON.stringify(tool.sidebarViewId)},{resolveWebviewView(view){
          view.webview.html=${JSON.stringify(`<main>${toolId}:${version}</main>`)};
        }}));};`);
      return stores[toolId].importDirectory(source);
    };
    for (const toolId of ["codex", "claude"] as const) {
      const root = toolId === "codex" ? "official-extension" : "claude-extension";
      stores[toolId] = new OfficialExtensionStore(join(directory, root, "packages"), { toolId });
      await prepare(toolId, "1.0.0"); await stores[toolId].activate();
    }
    const service = new AgentService("/deliberately/missing/codex", directory);
    const identity = { viewId: "same-test-id", rendererId: "renderer", workspace: directory };
    try {
      const codex = await service.request("extension.view.attach", identity) as { epoch: string; html: string; toolId: string };
      const claude = await service.request("extension.view.attach", { ...identity, toolId: "claude" }) as { epoch: string; html: string; toolId: string };
      expect(codex.html).toContain("codex:1.0.0"); expect(claude.html).toContain("claude:1.0.0");
      expect(codex.toolId).toBe("codex"); expect(claude.toolId).toBe("claude");
      expect(codex.epoch).not.toBe(claude.epoch);
      const resource = await service.request("extension.resource", { toolId: "claude", version: "1.0.0", path: "asset.txt" }) as { content: string };
      expect(Buffer.from(resource.content, "base64").toString()).toBe("claude:1.0.0");
      await prepare("claude", "2.0.0");
      expect(await service.request("extension.apply", { toolId: "claude", confirmed: false })).toMatchObject({ confirmationRequired: true, hosts: 1 });
      await service.request("extension.apply", { toolId: "claude", confirmed: true });
      expect(await service.request("extension.status", { toolId: "claude" })).toMatchObject({ toolId: "claude", version: "2.0.0", hosts: 0 });
      expect((await service.request("extension.view.attach", identity) as { epoch: string }).epoch).toBe(codex.epoch);
      const renewed = await service.request("extension.view.attach", { ...identity, toolId: "claude" }) as { epoch: string; html: string };
      expect(renewed.html).toContain("claude:2.0.0");
      service.setTransport();
      expect((await service.request("extension.view.attach", { ...identity, toolId: "claude" }) as { epoch: string }).epoch).toBe(renewed.epoch);
      await service.request("codex.runtime.restart", { confirmed: true });
      await vi.waitFor(async () => expect(await service.request("extension.status", {})).toMatchObject({ hosts: 0 }));
      expect((await service.request("extension.view.attach", { ...identity, toolId: "claude" }) as { epoch: string }).epoch).toBe(renewed.epoch);
      await service.request("extension.view.close", { ...identity, toolId: "claude" });
      expect(await service.request("extension.status", { toolId: "claude" })).toMatchObject({ hosts: 0 });
    } finally {
      service.close();
      await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    }
  });
});
