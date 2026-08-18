import { homedir, hostname, platform, arch } from "node:os";
import type { Readable } from "node:stream";
import { CodexRuntime } from "./codex-runtime.js";
import { FileService } from "./file-service.js";
import type { GatewayStream } from "./protocol-client.js";
import { TerminalService } from "./terminal-service.js";
import { APP_VERSION } from "./version.js";

interface Transport {
  event: (event: string, payload: unknown) => Promise<void>;
  stream: (streamId: string, input: Readable) => Promise<void>;
}

export class AgentService {
  private transport: Transport | undefined;
  private readonly files = new FileService();
  private readonly codex: CodexRuntime;
  private readonly terminals: TerminalService;

  constructor(codexExecutable: string) {
    this.codex = new CodexRuntime(codexExecutable, {
      notification: (message) => {
        void this.emit("codex.notification", message);
      },
      request: (message) => {
        void this.emit("codex.request", {
          rpcId: message.id,
          method: message.method,
          params: message.params,
        });
      },
      stderr: (message) => {
        void this.emit("codex.stderr", { message });
      },
      closed: (message) => {
        void this.emit("codex.closed", { message });
      },
    });
    this.terminals = new TerminalService({
      data: (terminalId, data) => {
        void this.emit("terminal.data", { terminalId, data });
      },
      exit: (terminalId, exitCode) => {
        void this.emit("terminal.exit", { terminalId, exitCode });
      },
    });
  }

  setTransport(transport?: Transport): void {
    this.transport = transport;
  }

  async request(method: string, rawParams: unknown): Promise<unknown> {
    const params = record(rawParams);
    switch (method) {
      case "service.describe":
        return {
          hostname: hostname(),
          platform: platform(),
          arch: arch(),
          agentVersion: APP_VERSION,
          home: homedir(),
          capabilities: [
            "codex-app-server",
            "codex-daemon-preferred",
            "codex-thread-takeover",
            "terminal",
            "files",
            "stream-transfer",
          ],
        };
      case "codex.rpc": {
        const rpcMethod = stringValue(params.method, "Codex RPC method");
        return this.codex.request(rpcMethod, params.params ?? {});
      }
      case "codex.respond":
        await this.codex.respond(rpcId(params.rpcId), params.result);
        return {};
      case "codex.takeover":
        return this.codex.takeoverThread(stringValue(params.threadId, "threadId"));
      case "fs.home":
        return this.files.home();
      case "fs.list":
        return this.files.list(stringValue(params.path, "path"));
      case "fs.mkdir":
        return this.files.mkdir(stringValue(params.path, "path"));
      case "fs.delete":
        await this.files.delete(
          stringValue(params.path, "path"),
          Boolean(params.directory),
        );
        return {};
      case "fs.download.stat":
        return this.files.downloadStat(stringValue(params.path, "path"));
      case "fs.download.start": {
        const path = stringValue(params.path, "path");
        const streamId = stringValue(params.streamId, "streamId");
        const input = this.files.openDownload(path);
        setImmediate(() => {
          void this.transport?.stream(streamId, input);
        });
        return {};
      }
      case "fs.upload.open":
        this.files.openUpload(
          stringValue(params.path, "path"),
          stringValue(params.streamId, "streamId"),
        );
        return {};
      case "fs.upload.finish":
        return this.files.finishUpload(stringValue(params.streamId, "streamId"));
      case "terminal.open":
        return this.terminals.open(
          integer(params.cols, 100),
          integer(params.rows, 30),
          optionalString(params.cwd),
        );
      default:
        throw new Error(`不支持的 Agent 方法: ${method}`);
    }
  }

  async notification(method: string, rawParams: unknown): Promise<void> {
    const params = record(rawParams);
    switch (method) {
      case "terminal.write":
        this.terminals.write(
          stringValue(params.terminalId, "terminalId"),
          stringValue(params.data, "data"),
        );
        return;
      case "terminal.resize":
        this.terminals.resize(
          stringValue(params.terminalId, "terminalId"),
          integer(params.cols, 100),
          integer(params.rows, 30),
        );
        return;
      case "terminal.close":
        this.terminals.close(stringValue(params.terminalId, "terminalId"));
        return;
      default:
        throw new Error(`不支持的 Agent 通知: ${method}`);
    }
  }

  stream(message: GatewayStream): void {
    if (message.type === "stream") {
      if (message.data) this.files.writeUpload(message.id, Buffer.from(message.data, "base64"));
    } else if (message.type === "stream.end") {
      this.files.endUpload(message.id);
    } else {
      this.files.failUpload(message.id, message.message ?? "Gateway 上传流失败");
    }
  }

  close(): void {
    this.terminals.closeAll();
    this.codex.close();
  }

  private emit(event: string, payload: unknown): Promise<void> {
    return this.transport?.event(event, payload) ?? Promise.resolve();
  }
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Agent 参数必须是对象");
  }
  return value as Record<string, unknown>;
}

function stringValue(value: unknown, name: string): string {
  if (typeof value !== "string" || !value) throw new Error(`${name} 必须是非空字符串`);
  return value;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined;
}

function integer(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) ? value : fallback;
}

function rpcId(value: unknown): string | number {
  if (typeof value === "string" || typeof value === "number") return value;
  throw new Error("Codex RPC id 无效");
}
