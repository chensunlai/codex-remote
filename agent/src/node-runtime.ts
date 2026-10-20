import { execFile } from "node:child_process";
import { access } from "node:fs/promises";
import { constants } from "node:fs";
import { delimiter, dirname, isAbsolute, join, resolve } from "node:path";
import { promisify } from "node:util";

const execute = promisify(execFile);
export const minimumNodeRuntimeVersion = "24.18.0";

/** The official extension must run in real Node, never inside Bun's compiled runtime. */
export async function resolveNodeExecutable(programPath = process.execPath): Promise<string> {
  const override = process.env.CODEX_REMOTE_NODE_PATH?.trim();
  if (override) {
    await verifyNodeExecutable(override);
    return override;
  }
  const bundled = join(dirname(resolve(programPath)), "runtime", process.platform === "win32" ? "node.exe" : "node");
  try {
    await access(bundled, constants.X_OK);
    await verifyNodeExecutable(bundled);
    return bundled;
  } catch (error) {
    // An installed but unusable runtime must not silently fall back to a different Node.
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  const actualNode = process.release?.name === "node" && !(process.versions as Record<string, string | undefined>).bun;
  const candidates = actualNode ? [process.execPath, ...nodePathCandidates()] : nodePathCandidates();
  for (const candidate of [...new Set(candidates)]) {
    try { await verifyNodeExecutable(candidate); return candidate; } catch { /* Try another development runtime. */ }
  }
  throw new Error(`未找到 Node.js ${minimumNodeRuntimeVersion}+；请重新运行 Agent 安装器，或设置 CODEX_REMOTE_NODE_PATH`);
}

export async function resolveExtensionHostDirectory(programPath = process.execPath): Promise<string> {
  const candidates = [
    join(dirname(resolve(programPath)), "runtime", "extension-host"),
    join(dirname(resolve(programPath)), "extension-host"),
    join(import.meta.dirname, "extension-host"),
    join(import.meta.dirname, "..", "src", "extension-host"),
  ];
  for (const candidate of candidates) {
    try { await access(join(candidate, "runtime.cjs"), constants.R_OK); return candidate; } catch { /* Check next development layout. */ }
  }
  throw new Error("缺少官方扩展宿主文件；请重新运行 Agent 安装器");
}

export async function verifyNodeExecutable(executable: string): Promise<void> {
  const { stdout } = await execute(executable, ["--version"], { timeout: 10_000, maxBuffer: 1024, windowsHide: true });
  const match = /^v(\d+)\.(\d+)\.(\d+)\s*$/.exec(stdout);
  const actual = match?.slice(1).map(Number);
  const required = minimumNodeRuntimeVersion.split(".").map(Number);
  if (!actual || !versionAtLeast(actual, required)) throw new Error(`扩展宿主需要 Node.js ${minimumNodeRuntimeVersion}+：${executable}`);
}

function nodePathCandidates(): string[] {
  return (process.env.PATH ?? "").split(delimiter).filter((path) => path && isAbsolute(path)).map((path) => join(path, process.platform === "win32" ? "node.exe" : "node"));
}

function versionAtLeast(actual: number[], required: number[]): boolean {
  for (let index = 0; index < 3; index++) {
    if (actual[index] !== required[index]) return actual[index]! > required[index]!;
  }
  return true;
}
