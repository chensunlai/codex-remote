import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { OfficialExtensionStore } from "../agent/src/official-extension-store.js";
import { OfficialExtensionService, type ExtensionViewFrame } from "../agent/src/official-extension-service.js";

assert.equal(process.platform, "darwin", "Run this check on a native macOS host");
const directory = await mkdtemp(join(tmpdir(), "official-macos 中文 "));
const previousCodexHome = process.env.CODEX_HOME;
const previousClaudeConfig = process.env.CLAUDE_CONFIG_DIR;
process.env.CODEX_HOME = join(directory, "codex-home");
process.env.CLAUDE_CONFIG_DIR = join(directory, "claude-config");
await mkdir(process.env.CODEX_HOME);
await mkdir(process.env.CLAUDE_CONFIG_DIR);
try {
  for (const toolId of ["codex", "claude"] as const) {
    const store = new OfficialExtensionStore(join(directory, toolId, "packages"), { toolId });
    await store.update();
    const deadline = Date.now() + 180_000;
    while ((await store.status()).operation?.state === "running") {
      assert.ok(Date.now() < deadline, `${toolId} official package download timed out`);
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    const status = await store.status();
    assert.notEqual(status.operation?.state, "failed", status.operation?.error);
    const installed = await store.activate();
    const digest = async () => createHash("sha256").update(await readFile(join(installed.directory, installed.manifest.main))).digest("hex");
    const original = await digest();
    const frames: ExtensionViewFrame[] = [];
    const service = new OfficialExtensionService(join(directory, toolId, "state"), "codex", frame => frames.push(frame), store, toolId);
    try {
      const attach = { viewId: `official-macos-${toolId}`, rendererId: "macos", workspace: directory };
      const first = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
      assert.match(first.html, /<html|<!doctype/i);
      await service.restart();
      const second = await service.request("extension.view.attach", attach) as { epoch: string; html: string };
      assert.notEqual(second.epoch, first.epoch);
      assert.match(second.html, /<html|<!doctype/i);
      assert.equal(frames.some(frame => frame.type === "status" && (frame.payload as { state?: string }).state === "error"), false);
      assert.equal(await digest(), original);
      console.log(`${toolId}: official ${process.platform}-${process.arch} package activation/restart passed; resource unchanged`);
    } finally {
      await service.restart();
      service.close();
    }
  }
  // No login or model request. Live conversation/approval tests still need a signed-in Mac.
} finally {
  if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
  else process.env.CODEX_HOME = previousCodexHome;
  if (previousClaudeConfig === undefined) delete process.env.CLAUDE_CONFIG_DIR;
  else process.env.CLAUDE_CONFIG_DIR = previousClaudeConfig;
  await rm(directory, { recursive: true, force: true });
}
