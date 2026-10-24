#!/usr/bin/env node

import { setTimeout as delay } from "node:timers/promises";
import { installService, markStarted } from "../maintenance/service.cjs";
import { APP_VERSION } from "./version.js";
import { AgentService } from "./agent-service.js";
import { loadAgentConfig, toAgentWebSocketUrl } from "./config.js";
import { ProtocolClient, FatalTransportError } from "./protocol-client.js";
import { AgentSetupCancelledError } from "./setup-tui.js";
import { errorMessage } from "./error-message.js";
import { dirname, join } from "node:path";
import { DesktopControl, redactToken } from "./desktop-control.js";

if (process.argv.includes("--version")) { console.log(APP_VERSION); process.exit(0); }
const installManaged = process.argv.includes("--install-service");
const configureOnly = process.argv.includes("--configure-only");
process.argv = process.argv.filter(arg => !["--install-service", "--configure-only"].includes(arg));
let client: ProtocolClient | undefined;
let service: AgentService | undefined;
let stopping = false;
let retryDelay = 1_000;
const retryAbort = new AbortController();
const desktop = process.argv.includes("--desktop-control") ? new DesktopControl(shutdown) : undefined;
if (desktop && !await desktop.started) { desktop.close(); process.exit(0); }
const config = await loadAgentConfig().catch((error: unknown) => {
  if (error instanceof AgentSetupCancelledError) process.exit(0);
  const message = desktop ? "Agent 配置无效，请检查连接设置和 Codex 路径" : errorMessage(error);
  desktop?.status("error", message);
  desktop?.close();
  process.stderr.write(
    `Agent 启动失败: ${message}\n`,
  );
  process.exit(1);
});
if (configureOnly) process.exit(0);
if (installManaged) {
  if (!process.versions.bun) throw new Error("请使用发行版 Agent 安装托管服务");
  await installService(process.execPath, config.configPath);
  process.exit(0);
}
await markStarted(process.execPath, APP_VERSION);
service = new AgentService(config.codexExecutable, join(dirname(config.configPath), "agent-data"));
const agent = service;
const log = (message: string): void => { process.stderr.write(desktop ? redactToken(message, config.token) : message); };

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

if (!desktop) process.stdout.write(
  [
    `服务名: ${config.name}`,
    `服务 ID: ${config.serviceId}`,
    `Gateway: ${config.gatewayUrl}`,
    "",
  ].join("\n"),
);

const endpoints = config.gatewayUrls ?? [config.gatewayUrl];
let endpointIndex = 0;
while (!stopping) {
  desktop?.status(retryDelay === 1_000 ? "connecting" : "reconnecting");
  let connected = false;
  client = new ProtocolClient(toAgentWebSocketUrl(config, endpoints[endpointIndex]), config.token, {
    request: (method, params) => agent.request(method, params),
    notification: (method, params) => agent.notification(method, params),
    stream: (message) => agent.stream(message),
  }, {
    handshakeTimeout: endpoints.length > 1 ? 5_000 : 20_000,
    connected: () => {
      connected = true;
      desktop?.status("connected");
      log(`已连接 Gateway: ${endpoints[endpointIndex]}\n`);
    }, log,
  });
  agent.setTransport({
    event: (event, payload) => client?.event(event, payload) ?? Promise.resolve(),
    stream: (streamId, input) => client?.sendStream(streamId, input) ?? Promise.resolve(),
  });
  try {
    await client.run();
    retryDelay = 1_000;
  } catch (error) {
    if (error instanceof FatalTransportError) {
      const message = redactToken(errorMessage(error), config.token);
      desktop?.status("error", message);
      log(`传输已停止：${message}。请更新组件或调整消息上限后重启 Agent。\n`);
      break;
    }
    log(
      `连接 Gateway 失败: ${errorMessage(error)}\n`,
    );
  } finally {
    client.close();
    agent.setTransport(undefined);
  }
  if (!stopping) {
    // Only one socket at a time. A disconnected healthy connection starts a new
    // priority sweep; failed handshakes try the next address without backoff.
    if (!connected && endpointIndex + 1 < endpoints.length) {
      endpointIndex += 1;
      continue;
    }
    endpointIndex = 0;
    desktop?.status("reconnecting");
    log(`${retryDelay}ms 后重连\n`);
    await delay(retryDelay, undefined, { signal: retryAbort.signal }).catch(() => {});
    retryDelay = Math.min(retryDelay * 2, 30_000);
  }
}

// A fatal transport error stops reconnecting but must not terminate offline tasks.
if (!stopping) client?.close();

function shutdown(): void {
  if (stopping) return;
  stopping = true;
  retryAbort.abort();
  client?.close();
  service?.close();
  desktop?.status("stopped");
  desktop?.close();
}
