import { execFile, spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { readdir, readFile, readlink } from "node:fs/promises";
import type {
  NotificationMessage,
  ServerRequestMessage,
} from "./codex/json-rpc-peer.js";
import { JsonRpcPeer } from "./codex/json-rpc-peer.js";
import { APP_VERSION } from "./version.js";

interface CodexCallbacks {
  notification: (message: NotificationMessage) => void;
  request: (message: ServerRequestMessage) => void;
  stderr: (message: string) => void;
  closed: (message: string) => void;
}

export class CodexRuntime {
  private process: ChildProcessWithoutNullStreams | undefined;
  private peer: JsonRpcPeer | undefined;
  private connecting: Promise<JsonRpcPeer> | undefined;

  constructor(
    private readonly executable: string,
    private readonly callbacks: CodexCallbacks,
  ) {}

  async request<T = unknown>(method: string, params: unknown): Promise<T> {
    const peer = await this.connect();
    return peer.request<T>(method, params);
  }

  async respond(rpcId: number | string, result: unknown): Promise<void> {
    const peer = await this.connect();
    peer.respond(rpcId, result);
  }

  async takeoverThread<T = unknown>(threadId: string): Promise<T> {
    await this.request("thread/unsubscribe", { threadId }).catch(() => undefined);
    this.disconnect();
    await stopThreadLockOwner(threadId);
    return this.request<T>("thread/resume", { threadId });
  }

  async releaseThread(threadId: string): Promise<unknown> {
    return this.request("thread/unsubscribe", { threadId });
  }

  close(): void {
    this.disconnect();
  }

  private disconnect(): void {
    const peer = this.peer;
    const child = this.process;
    this.peer = undefined;
    this.process = undefined;
    peer?.close();
    child?.kill("SIGTERM");
  }

  private async connect(): Promise<JsonRpcPeer> {
    if (this.peer?.isOpen()) return this.peer;
    if (this.connecting) return this.connecting;
    this.connecting = this.open();
    try {
      return await this.connecting;
    } finally {
      this.connecting = undefined;
    }
  }

  private async open(): Promise<JsonRpcPeer> {
    try {
      await run(
        this.executable,
        ["app-server", "daemon", "bootstrap", "--remote-control"],
        90_000,
      );
      return await this.openProcess(["app-server", "proxy"]);
    } catch (error) {
      this.callbacks.stderr(
        `Codex daemon/proxy 不可用，使用 stdio app-server: ${messageOf(error)}`,
      );
      return this.openProcess(["app-server", "--stdio"]);
    }
  }

  private async openProcess(args: string[]): Promise<JsonRpcPeer> {
    const child = spawn(this.executable, args, {
      stdio: ["pipe", "pipe", "pipe"],
      env: process.env,
    });
    this.process = child;
    const peer = new JsonRpcPeer(child.stdout, child.stdin);
    this.peer = peer;
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk: string) => {
      const message = chunk.trim();
      if (message) this.callbacks.stderr(message);
    });
    child.on("error", (error) => {
      if (this.peer === peer) this.callbacks.closed(error.message);
    });
    child.on("exit", (code, signal) => {
      if (this.peer !== peer) return;
      this.peer = undefined;
      this.process = undefined;
      this.callbacks.closed(
        `Codex proxy 已退出 (code=${String(code)}, signal=${String(signal)})`,
      );
    });
    peer.on("notification", (message: NotificationMessage) => {
      this.callbacks.notification(message);
    });
    peer.on("serverRequest", (message: ServerRequestMessage) => {
      this.callbacks.request(message);
    });
    peer.on("protocolError", (error: Error) => {
      this.callbacks.stderr(`Codex RPC 协议错误: ${error.message}`);
    });

    try {
      await peer.request("initialize", {
        clientInfo: {
          name: "codex_remote_agent",
          title: "Codex Remote Agent",
          version: APP_VERSION,
        },
        capabilities: {
          experimentalApi: true,
          mcpServerOpenaiFormElicitation: true,
        },
      });
      peer.notify("initialized", {});
      return peer;
    } catch (error) {
      if (this.peer === peer) {
        this.peer = undefined;
        this.process = undefined;
      }
      peer.close();
      child.kill("SIGTERM");
      throw error;
    }
  }
}

function run(executable: string, args: string[], timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    execFile(
      executable,
      args,
      {
        timeout: timeoutMs,
        maxBuffer: 8 * 1024 * 1024,
        encoding: "utf8",
        env: process.env,
      },
      (error, stdout, stderr) => {
        if (error) {
          reject(
            new Error(
              String(stderr).trim()
              || String(stdout).trim()
              || `${executable} ${args.join(" ")}: ${error.message}`,
            ),
          );
        } else {
          resolve();
        }
      },
    );
  });
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function stopThreadLockOwner(threadId: string): Promise<void> {
  const codexHome = process.env.CODEX_HOME || join(homedir(), ".codex");
  const lockPath = join(codexHome, "thread-writer-locks", `${threadId}.lock`);
  const owners = await threadLockOwners(lockPath);
  for (const pid of owners) {
    if (pid === process.pid || !(await isCodexProcess(pid))) continue;
    try {
      process.kill(pid, "SIGTERM");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error;
    }
  }

  const deadline = Date.now() + 5_000;
  let remaining = owners;
  while (remaining.length > 0 && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    remaining = await threadLockOwners(lockPath);
  }
  for (const pid of remaining) {
    if (pid === process.pid || !(await isCodexProcess(pid))) continue;
    try {
      process.kill(pid, "SIGKILL");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error;
    }
  }
}

async function threadLockOwners(lockPath: string): Promise<number[]> {
  let processes: string[];
  try {
    processes = await readdir("/proc");
  } catch {
    return [];
  }
  const owners = await Promise.all(processes.filter(isPid).map(async (value) => {
    const pid = Number(value);
    let descriptors: string[];
    try {
      descriptors = await readdir(`/proc/${pid}/fd`);
    } catch {
      return undefined;
    }
    for (const descriptor of descriptors) {
      try {
        const target = await readlink(`/proc/${pid}/fd/${descriptor}`);
        if (target.replace(/ \(deleted\)$/, "") === lockPath) return pid;
      } catch {
        // The descriptor or process may disappear while /proc is being scanned.
      }
    }
    return undefined;
  }));
  return owners.filter((pid): pid is number => pid !== undefined);
}

async function isCodexProcess(pid: number): Promise<boolean> {
  try {
    const command = (await readFile(`/proc/${pid}/comm`, "utf8")).trim().toLowerCase();
    if (basename(command).includes("codex")) return true;
    const args = (await readFile(`/proc/${pid}/cmdline`))
      .toString("utf8")
      .split("\0")
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    return args.includes("/@openai/codex/") || /(?:^|\s)codex(?:\s|$)/.test(args);
  } catch {
    return false;
  }
}

function isPid(value: string): boolean {
  return /^[1-9]\d*$/.test(value);
}
