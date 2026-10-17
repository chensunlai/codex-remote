import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { resolveExtensionHostDirectory, resolveNodeExecutable, verifyNodeExecutable } from "./node-runtime.js";

const directories: string[] = [];
afterEach(async () => {
  vi.unstubAllEnvs();
  for (const directory of directories.splice(0)) await rm(directory, { recursive: true, force: true });
});
async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "codex-node-runtime-"));
  directories.push(directory);
  await mkdir(join(directory, "runtime", "extension-host"), { recursive: true });
  return directory;
}
async function node(path: string, version: string) {
  await writeFile(path, `#!/bin/sh\nprintf '%s\\n' '${version}'\n`);
  await chmod(path, 0o755);
}

describe("official extension Node runtime", () => {
  it("prefers the bundled real-Node executable and matching host files", async () => {
    const directory = await fixture();
    const executable = join(directory, "runtime", "node");
    await node(executable, "v24.18.0");
    await writeFile(join(directory, "runtime", "extension-host", "runtime.cjs"), "// host");
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", "");
    expect(await resolveNodeExecutable(join(directory, "codex-remote-agent"))).toBe(executable);
    expect(await resolveExtensionHostDirectory(join(directory, "codex-remote-agent"))).toBe(join(directory, "runtime", "extension-host"));
  });

  it("honors a validated explicit Node override and rejects an old override", async () => {
    const directory = await fixture();
    const executable = join(directory, "custom-node");
    await node(executable, "v25.1.0");
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", executable);
    expect(await resolveNodeExecutable()).toBe(executable);
    await node(executable, "v24.17.0");
    await expect(resolveNodeExecutable()).rejects.toThrow("24.18.0");
  });

  it("fails clearly for a corrupted bundled runtime instead of silently changing runtimes", async () => {
    const directory = await fixture();
    await node(join(directory, "runtime", "node"), "v22.0.0");
    vi.stubEnv("CODEX_REMOTE_NODE_PATH", "");
    await expect(resolveNodeExecutable(join(directory, "codex-remote-agent"))).rejects.toThrow("24.18.0");
  });

  it("rejects non-Node output", async () => {
    const directory = await fixture();
    const executable = join(directory, "not-node");
    await node(executable, "bun 1.3.14");
    await expect(verifyNodeExecutable(executable)).rejects.toThrow("24.18.0");
  });
});
