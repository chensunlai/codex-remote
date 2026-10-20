import { spawn, type ChildProcess } from "node:child_process";
import { isAbsolute } from "node:path";
import { createInterface } from "node:readline";

export interface HostSupervisor { close(): Promise<void> }

/** Assign the still-idle Node host to a Windows Job before activating the extension. */
export async function superviseWindowsHost(host: ChildProcess): Promise<HostSupervisor | undefined> {
  if (process.platform !== "win32") return undefined;
  const executable = process.env.CODEX_REMOTE_WINDOWS_SUPERVISOR;
  if (!executable || !isAbsolute(executable) || !host.pid) throw new Error("Windows 扩展宿主需要托盘进程监护");
  const helper = spawn(executable, ["--watch-host", String(host.pid)], {
    stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
  });
  const input = createInterface({ input: helper.stdout });
  helper.stderr.resume();
  let closing = false;
  let cleanup: Promise<void> | undefined;
  const exited = new Promise<void>(resolve => {
    helper.once("exit", () => { input.close(); if (!closing) host.kill("SIGKILL"); resolve(); });
    helper.once("error", () => { if (!closing) host.kill("SIGKILL"); resolve(); });
  });
  helper.stdin.on("error", () => { if (!closing) host.kill("SIGKILL"); });
  const close = (): Promise<void> => {
    cleanup ??= (async () => {
      closing = true;
      helper.stdin.end();
      const timer = setTimeout(() => { helper.kill("SIGKILL"); }, 5_000);
      try { await exited; } finally { clearTimeout(timer); }
    })();
    return cleanup;
  };
  const hostExited = (): void => { void close(); };
  host.once("exit", hostExited);
  try {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => fail(new Error("Windows 宿主监护启动超时")), 10_000);
      const finish = (): void => { clearTimeout(timer); input.off("line", line); helper.off("error", fail); helper.off("exit", premature); host.off("exit", premature); };
      const fail = (error: Error): void => { finish(); reject(error); };
      const premature = (): void => fail(new Error("Windows 宿主监护未完成进程隔离便退出"));
      const line = (value: string): void => {
        try { if ((JSON.parse(value) as { type?: unknown }).type === "ready") { finish(); resolve(); } } catch { /* Ignore non-control diagnostics. */ }
      };
      input.on("line", line);
      helper.once("error", fail);
      helper.once("exit", premature);
      host.once("exit", premature);
    });
    return { close };
  } catch (error) {
    host.kill("SIGKILL");
    await close();
    throw error;
  }
}
