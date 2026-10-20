import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { commandOutput, readCodexVersion } from "./codex-channels.js";

interface RuntimeProcesses {
  status(): Array<{ channelId: string; version: string | null; active: boolean; pendingRequests: number }>;
  restart(): Promise<void>;
}

export interface RuntimeOperation {
  id: string;
  type: "update" | "restart";
  state: "running" | "succeeded" | "failed";
  output: string;
  error?: string;
}

interface RuntimeControlOptions {
  latestVersion?: () => Promise<string | null>;
  operationTimeoutMs?: number;
}

/** Only invokes the user's configured CLI; never installs a second copy or owns its daemon. */
export class CodexRuntimeControl {
  private operation: RuntimeOperation | null = null;
  private installedVersion: string | null = null;
  private updateSupported = false;
  private lastProbe = 0;
  private probing: Promise<void> | undefined;
  private latestVersion: string | null = null;
  private latestChecked = 0;
  private latestChecking: Promise<void> | undefined;

  constructor(
    private readonly executable: string,
    private readonly channels: RuntimeProcesses,
    private readonly changed: () => void = () => {},
    private readonly options: RuntimeControlOptions = {},
  ) {}

  async status(): Promise<unknown> {
    await Promise.all([this.probe(), this.checkLatest()]);
    return this.snapshot();
  }

  snapshot(): unknown {
    const runningVersions = this.channels.status();
    return {
      executable: this.executable,
      installedVersion: this.installedVersion,
      runningVersions,
      latestVersion: this.latestVersion,
      updateAvailable: this.installedVersion !== null && this.latestVersion !== null
        ? newerStableVersion(this.latestVersion, this.installedVersion) : null,
      restartRequired: this.installedVersion !== null && runningVersions.some(
        ({ version }) => version !== null && version !== this.installedVersion,
      ),
      operation: this.operation,
      updateSupported: this.updateSupported,
    };
  }

  update(): RuntimeOperation {
    if (process.platform === "win32") throw new Error("Windows 暂不支持自动更新 Codex CLI，请手动更新官方安装");
    if (this.operation?.state === "running") return this.operation;
    const operation = this.begin("update");
    void this.performUpdate(operation);
    return operation;
  }

  restart(confirmed: boolean): unknown {
    if (this.operation?.state === "running") return { operation: this.operation };
    const affected = this.channels.status();
    if (!confirmed && affected.some(({ active, pendingRequests }) => active || pendingRequests > 0)) {
      return { confirmationRequired: true, affected };
    }
    const operation = this.begin("restart");
    void this.channels.restart().then(() => {
      operation.state = "succeeded";
      this.changed();
    }, (error: unknown) => this.fail(operation, error));
    return { confirmationRequired: false, operation };
  }

  private begin(type: RuntimeOperation["type"]): RuntimeOperation {
    const operation: RuntimeOperation = { id: randomUUID(), type, state: "running", output: "" };
    this.operation = operation;
    this.changed();
    return operation;
  }

  private async performUpdate(operation: RuntimeOperation): Promise<void> {
    try {
      await this.probe(true);
      if (!this.updateSupported) throw new Error("当前 Codex CLI 未提供 update 命令，请在服务器上手动更新");
      await new Promise<void>((resolve, reject) => {
        // No shell, elevated permissions, global package manager, or daemon update command.
        const child = spawn(this.executable, ["update"], {
          stdio: ["ignore", "pipe", "pipe"], env: process.env,
        });
        let timedOut = false;
        const timer = setTimeout(() => {
          timedOut = true;
          child.kill("SIGTERM");
          const killTimer = setTimeout(() => child.kill("SIGKILL"), 5_000);
          killTimer.unref();
          child.once("exit", () => clearTimeout(killTimer));
        }, this.options.operationTimeoutMs ?? 10 * 60_000);
        timer.unref();
        const output = (data: Buffer): void => {
          operation.output = `${operation.output}${data.toString("utf8")}`.slice(-65_536);
        };
        child.stdout.on("data", output);
        child.stderr.on("data", output);
        child.once("error", (error) => { clearTimeout(timer); reject(error); });
        child.once("exit", (code, signal) => {
          clearTimeout(timer);
          if (timedOut) reject(new Error("Codex update 超时"));
          else if (code === 0) resolve();
          else reject(new Error(`Codex update 失败 (code=${String(code)}, signal=${String(signal)})`));
        });
      });
      await this.probe(true);
      operation.state = "succeeded";
      this.changed();
    } catch (error) {
      this.fail(operation, error);
    }
  }

  private fail(operation: RuntimeOperation, error: unknown): void {
    operation.state = "failed";
    operation.error = error instanceof Error ? error.message : String(error);
    this.changed();
  }

  private async probe(force = false): Promise<void> {
    if (this.probing) {
      await this.probing;
      if (!force) return;
    }
    if (!force && this.lastProbe && Date.now() - this.lastProbe < 30_000) return;
    this.probing = (async () => {
      const [version, help] = await Promise.all([
        readCodexVersion(this.executable),
        process.platform === "win32" ? Promise.resolve("") : commandOutput(this.executable, ["--help"]).catch(() => ""),
      ]);
      this.installedVersion = version;
      this.updateSupported = /^\s{2,}update(?:\s|$)/m.test(help);
      this.lastProbe = Date.now();
    })();
    try { await this.probing; }
    finally { this.probing = undefined; }
  }

  private async checkLatest(): Promise<void> {
    if (process.platform === "win32") return;
    if (this.latestChecking) return this.latestChecking;
    if (this.latestChecked && Date.now() - this.latestChecked < 6 * 60 * 60_000) return;
    this.latestChecking = (async () => {
      try {
        const version = await (this.options.latestVersion ?? fetchLatestVersion)();
        this.latestVersion = version && /^\d+\.\d+\.\d+$/.test(version) ? version : null;
      } catch {
        this.latestVersion = null;
      }
      this.latestChecked = Date.now();
    })();
    try { await this.latestChecking; }
    finally { this.latestChecking = undefined; }
  }
}

async function fetchLatestVersion(): Promise<string | null> {
  const response = await fetch("https://registry.npmjs.org/@openai%2fcodex/latest", {
    signal: AbortSignal.timeout(2_000),
    headers: { Accept: "application/json" },
  });
  if (!response.ok) return null;
  const metadata: unknown = await response.json();
  if (!metadata || typeof metadata !== "object") return null;
  const version = (metadata as { version?: unknown }).version;
  return typeof version === "string" ? version : null;
}

function newerStableVersion(candidate: string, current: string): boolean {
  const target = candidate.split(".").map(Number);
  const running = current.split(/[.+-]/).slice(0, 3).map(Number);
  for (let index = 0; index < 3; index++) {
    if (target[index]! > running[index]!) return true;
    if (target[index]! < running[index]!) return false;
  }
  return current.includes("-");
}
