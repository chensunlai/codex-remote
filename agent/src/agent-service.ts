import { homedir, hostname, platform, arch } from "node:os";
import type { Readable } from "node:stream";
import { FileService } from "./file-service.js";
import type { GatewayStream } from "./protocol-client.js";
import { TerminalService } from "./terminal-service.js";
import { APP_VERSION } from "./version.js";
import { CodexChannels } from "./codex-channels.js";
import { CodexRuntimeControl } from "./codex-runtime-control.js";
import { OfficialExtensionService } from "./official-extension-service.js";
import { join, resolve } from "node:path";

interface Transport {
  event: (event: string, payload: unknown) => Promise<void>;
  stream: (streamId: string, input: Readable) => Promise<void>;
}

export class AgentService {
  private transport: Transport | undefined;
  private readonly files = new FileService();
  private readonly terminals: TerminalService;
  private readonly channels: CodexChannels;
  private readonly runtimeControl: CodexRuntimeControl;
  private readonly extensions: OfficialExtensionService;

  constructor(codexExecutable: string, dataDirectory = resolve(".codex-remote-agent")) {
    this.channels = new CodexChannels(codexExecutable, (frame) => {
      void this.emit("codex.channel", frame).catch(() => {});
    });
    this.extensions = new OfficialExtensionService(join(dataDirectory, "official-extension"), codexExecutable, frame => {
      void this.emit("extension.view", frame).catch(() => {});
    });
    const runtimes = {
      status: () => [...this.channels.status(), ...this.extensions.runtimeStatus()],
      restart: async () => { await Promise.all([this.channels.restart(), this.extensions.restart()]); },
    };
    this.runtimeControl = new CodexRuntimeControl(codexExecutable, runtimes, () => {
      void this.emit("codex.runtime", this.runtimeControl.snapshot()).catch(() => {});
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
    if (!transport) { this.channels.disconnected(); this.extensions.disconnected(); }
  }

  async request(method: string, rawParams: unknown): Promise<unknown> {
    this.assertSupported(method);
    const params = record(rawParams);
    if (method.startsWith("extension.")) return this.extensions.request(method, params);
    switch (method) {
      case "service.describe":
        return {
          hostname: hostname(),
          platform: platform(),
          arch: arch(),
          agentVersion: APP_VERSION,
          home: homedir(),
          capabilities: [
            "codex-raw-channels",
            "codex-runtime-control",
            "official-extension-host-v1",
            "extension-window-management-v1",
            ...(process.platform === "win32" ? [] : ["terminal"]),
            "files",
            "stream-transfer",
          ],
        };
      case "codex.channel.attach":
        return this.channels.attach({
          channelId: stringValue(params.channelId, "channelId"),
          sessionId: stringValue(params.sessionId, "sessionId"),
          ...(params.afterSequence !== undefined ? { afterSequence: Number(params.afterSequence) } : {}),
          ...(params.epoch !== undefined ? { epoch: stringValue(params.epoch, "epoch") } : {}),
          ...(params.initialize !== undefined ? { initialize: params.initialize } : {}),
        });
      case "codex.channel.send":
        return this.channels.send({
          channelId: stringValue(params.channelId, "channelId"),
          sessionId: stringValue(params.sessionId, "sessionId"),
          epoch: stringValue(params.epoch, "epoch"),
          messageId: stringValue(params.messageId, "messageId"),
          message: params.message,
        });
      case "codex.channel.detach":
        return this.channels.detach({
          channelId: stringValue(params.channelId, "channelId"),
          sessionId: stringValue(params.sessionId, "sessionId"),
        });
      case "codex.channel.coordinate":
        return this.channels.coordinate({
          channelId: stringValue(params.channelId, "channelId"),
          sessionId: stringValue(params.sessionId, "sessionId"),
          epoch: stringValue(params.epoch, "epoch"),
          message: record(params.message),
        });
      case "codex.channel.ack":
        return this.channels.ack({
          channelId: stringValue(params.channelId, "channelId"),
          sessionId: stringValue(params.sessionId, "sessionId"),
          sequence: Number(params.sequence),
        });
      case "codex.runtime.status":
        return this.runtimeControl.status();
      case "codex.runtime.update":
        return { operation: this.runtimeControl.update() };
      case "codex.runtime.restart":
        return this.runtimeControl.restart(params.confirmed === true);
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
    this.assertSupported(method);
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
    this.channels.close();
    this.extensions.close();
  }

  private emit(event: string, payload: unknown): Promise<void> {
    return this.transport?.event(event, payload) ?? Promise.resolve();
  }

  private assertSupported(method: string): void {
    if (process.platform === "win32" && method.startsWith("terminal.")) throw new Error("Windows Agent 暂不支持终端");
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
