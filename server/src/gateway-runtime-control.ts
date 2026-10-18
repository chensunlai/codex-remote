import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { access, readFile, unlink, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { AppError, ConflictError, errorMessage } from "./errors.js";
import { writePrivateFileAtomically } from "./private-file.js";
import { APP_VERSION } from "./version.js";

export type GatewayMaintenanceAction = "update" | "restart";
export interface GatewayRuntimeOperation {
  id: string;
  type: GatewayMaintenanceAction;
  state: "queued" | "running" | "succeeded" | "failed";
  output: string;
  error?: string;
  createdAt?: number;
}
export interface GatewayRuntimeStatus {
  currentVersion: string;
  latestVersion: string | null;
  updateAvailable: boolean | null;
  updateSupported: boolean;
  restartSupported: boolean;
  unavailableReason?: string;
  checkError?: string;
  operation: GatewayRuntimeOperation | null;
  confirmationRequired?: boolean;
}
export interface PreparedGatewayOperation {
  status: GatewayRuntimeStatus;
  activate: () => Promise<void>;
}
export interface GatewayRuntimeController {
  status(checkUpdates?: boolean): Promise<GatewayRuntimeStatus>;
  prepare(type: GatewayMaintenanceAction, confirmed: boolean): Promise<PreparedGatewayOperation>;
}

interface Options {
  controlDirectory?: string;
  executable?: string;
  currentVersion?: string;
  latestVersion?: () => Promise<string>;
  managedService?: boolean;
}

/** Only the installer-provisioned root helper may restart or replace the Gateway. */
export class GatewayRuntimeControl implements GatewayRuntimeController {
  private readonly controlDirectory: string;
  private readonly operationPath: string;
  private latestVersion: string | null = null;
  private checkError: string | undefined;
  private checking: Promise<void> | undefined;
  private preparing = false;

  constructor(private readonly dataDirectory: string, private readonly options: Options = {}) {
    this.controlDirectory = options.controlDirectory ?? "/var/lib/codex-remote-control";
    this.operationPath = join(dataDirectory, "gateway-runtime-operation.json");
  }

  async status(checkUpdates = false): Promise<GatewayRuntimeStatus> {
    if (checkUpdates) await this.checkLatest();
    const unavailableReason = await this.unavailableReason();
    const updatesEnabled = await this.updatesEnabled();
    const currentVersion = this.options.currentVersion ?? APP_VERSION;
    return {
      currentVersion,
      latestVersion: this.latestVersion,
      updateAvailable: this.latestVersion ? newerVersion(this.latestVersion, currentVersion) : null,
      updateSupported: !unavailableReason && updatesEnabled,
      restartSupported: !unavailableReason,
      ...(unavailableReason ? { unavailableReason } : !updatesEnabled ? { unavailableReason: "此 Gateway 来自自定义仓库，请使用原仓库安装器更新" } : {}),
      ...(this.checkError ? { checkError: this.checkError } : {}),
      operation: await this.readOperation(),
    };
  }

  async prepare(type: GatewayMaintenanceAction, confirmed: boolean): Promise<PreparedGatewayOperation> {
    // Reserve before awaiting status, so concurrent HTTP requests cannot both queue an operation.
    if (this.preparing) throw new ConflictError("Gateway 已有维护操作正在进行");
    this.preparing = true;
    try {
      const status = await this.status();
      if (!confirmed) return { status: { ...status, confirmationRequired: true }, activate: async () => {} };
      if (!(type === "update" ? status.updateSupported : status.restartSupported)) throw new AppError(409, "MAINTENANCE_UNAVAILABLE", status.unavailableReason!);
      if (status.operation && ["queued", "running"].includes(status.operation.state)) {
        throw new ConflictError("Gateway 已有维护操作正在进行");
      }
      const operation: GatewayRuntimeOperation = { id: randomUUID(), type, state: "queued", output: "等待维护服务执行", createdAt: Date.now() };
      await this.saveOperation(operation);
      let activated = false;
      return {
        status: { ...status, operation },
        activate: async () => {
          if (activated) return;
          activated = true;
          try {
            // Filename is the entire protocol. No command, URL, path or request body reaches root.
            await writeFile(join(this.controlDirectory, "requests", `${type}-${operation.id}`), "", { flag: "wx", mode: 0o600 });
          } catch (error) {
            await this.saveOperation({ ...operation, state: "failed", output: "无法启动维护操作", error: errorMessage(error) });
            throw error;
          }
        },
      };
    } finally {
      this.preparing = false;
    }
  }

  private async unavailableReason(): Promise<string | undefined> {
    if (!(this.options.managedService ?? (Boolean(process.env.INVOCATION_ID) && process.env.CODEX_REMOTE_MANAGED_GATEWAY === "1"))) {
      return "当前 Gateway 未由支持远程维护的 systemd 服务运行";
    }
    try {
      const installed = JSON.parse(await readFile(join(this.controlDirectory, "installation.json"), "utf8")) as {
        dataDirectory?: string; executable?: string;
      };
      if (typeof installed.dataDirectory !== "string" || typeof installed.executable !== "string"
        || resolve(installed.dataDirectory) !== resolve(this.dataDirectory)
        || resolve(installed.executable) !== resolve(this.options.executable ?? process.execPath)) {
        return "当前 Gateway 未由支持远程维护的 systemd 服务运行";
      }
      await access(join(this.controlDirectory, "requests"), constants.W_OK);
      return undefined;
    } catch {
      return "请在服务器重新运行 Gateway 安装器并加上 --systemd，以启用远程更新和重启";
    }
  }

  private async readOperation(): Promise<GatewayRuntimeOperation | null> {
    const [local, helper] = await Promise.all([
      readOperationFile(this.operationPath),
      readOperationFile(join(this.controlDirectory, "operation.json")),
    ]);
    if (!local) return helper;
    if (helper?.id === local.id && ["succeeded", "failed"].includes(helper.state)) return helper;
    if (local.state === "failed") return local;
    const operation = helper?.id === local.id ? { ...helper, createdAt: local.createdAt } : local;
    if (["queued", "running"].includes(operation.state) && local.createdAt
      && Date.now() - local.createdAt > (operation.state === "queued" ? 30_000 : 17 * 60_000)) {
      await unlink(join(this.controlDirectory, "requests", `${operation.type}-${operation.id}`)).catch((error: unknown) => {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      });
      const failed: GatewayRuntimeOperation = { ...local, state: "failed", output: "维护操作超时", error: "维护服务未完成操作，请检查服务器上的 maintenance.path 和 maintenance.service" };
      await this.saveOperation(failed);
      return failed;
    }
    if (helper?.id === local.id) return helper;
    return local;
  }

  private async updatesEnabled(): Promise<boolean> {
    try {
      const installed = JSON.parse(await readFile(join(this.controlDirectory, "installation.json"), "utf8")) as { updateSupported?: boolean };
      return installed.updateSupported === true;
    } catch { return false; }
  }

  private saveOperation(operation: GatewayRuntimeOperation): Promise<void> {
    return writePrivateFileAtomically(this.operationPath, JSON.stringify(operation));
  }

  private async checkLatest(): Promise<void> {
    if (this.checking) return this.checking;
    this.checking = (async () => {
      try {
        const version = await (this.options.latestVersion ?? fetchLatestGatewayVersion)();
        if (!/^\d{1,9}\.\d{1,9}\.\d{1,9}$/.test(version)) throw new Error("最新 Release 版本格式无效");
        this.latestVersion = version;
        this.checkError = undefined;
      } catch (error) {
        this.checkError = `检查更新失败：${errorMessage(error)}`;
      }
    })();
    try { await this.checking; }
    finally { this.checking = undefined; }
  }
}

async function readOperationFile(path: string): Promise<GatewayRuntimeOperation | null> {
  try {
    const value = JSON.parse(await readFile(path, "utf8")) as GatewayRuntimeOperation;
    if (!/^[0-9a-f-]{36}$/.test(value.id) || !["update", "restart"].includes(value.type)
      || !["queued", "running", "succeeded", "failed"].includes(value.state) || typeof value.output !== "string") return null;
    return value;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT" || error instanceof SyntaxError) return null;
    throw error;
  }
}

async function fetchLatestGatewayVersion(): Promise<string> {
  const response = await fetch("https://api.github.com/repos/chensunlai/codex-remote/releases/latest", {
    headers: { Accept: "application/vnd.github+json" }, signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`GitHub HTTP ${response.status}`);
  const reader = response.body?.getReader();
  if (!reader) throw new Error("GitHub 响应为空");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      length += chunk.value.length;
      if (length > 256 * 1024) throw new Error("GitHub 响应过大");
      chunks.push(chunk.value);
    }
  } finally { await reader.cancel(); }
  const value = JSON.parse(Buffer.concat(chunks).toString("utf8")) as { tag_name?: string; draft?: boolean; prerelease?: boolean };
  if (value.draft || value.prerelease || typeof value.tag_name !== "string") throw new Error("没有可用的正式 Release");
  return value.tag_name.replace(/^v/, "");
}

function newerVersion(candidate: string, current: string): boolean {
  const target = candidate.split(".").map(Number), running = current.split(/[.+-]/).slice(0, 3).map(Number);
  for (let index = 0; index < 3; index++) {
    if (target[index]! > running[index]!) return true;
    if (target[index]! < running[index]!) return false;
  }
  return current.includes("-");
}
