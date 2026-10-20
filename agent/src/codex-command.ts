import { existsSync } from "node:fs";
import { isNativeAbsolutePath } from "./native-path.js";

/** Give scripts an executable entry without changing the official extension's spawn calls. */
export function codexCommand(executable: string): { executable: string; env: NodeJS.ProcessEnv } {
  if (process.platform !== "win32" || !/\.(?:cmd|bat)$/i.test(executable)) {
    return { executable, env: process.env };
  }
  const supervisor = process.env.CODEX_REMOTE_WINDOWS_SUPERVISOR;
  const launcher = process.env.CODEX_REMOTE_WINDOWS_CODEX_LAUNCHER
    ?? supervisor ?? "";
  if (!isNativeAbsolutePath(launcher) || !/\.exe$/i.test(launcher) || !existsSync(launcher)) {
    throw new Error("缺少 Windows Codex 脚本启动器，请完整解压新版 Windows ZIP 后重试");
  }
  return { executable: launcher, env: { ...process.env, CODEX_REMOTE_CODEX_SCRIPT: executable } };
}
