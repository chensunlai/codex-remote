#!/usr/bin/env node

import { setTimeout as delay } from "node:timers/promises";
import { AgentService } from "./agent-service.js";
import { loadAgentConfig, toAgentWebSocketUrl } from "./config.js";
import { ProtocolClient, FatalTransportError } from "./protocol-client.js";
import { AgentSetupCancelledError } from "./setup-tui.js";
import { errorMessage } from "./error-message.js";
import { dirname, join } from "node:path";

const config = await loadAgentConfig().catch((error: unknown) => {
  if (error instanceof AgentSetupCancelledError) process.exit(0);
  process.stderr.write(
    `Agent 启动失败: ${errorMessage(error)}\n`,
  );
  process.exit(1);
});
const service = new AgentService(config.codexExecutable, join(dirname(config.configPath), "agent-data"));
let client: ProtocolClient | undefined;
let stopping = false;
let retryDelay = 1_000;

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

process.stdout.write(
  [
    `服务名: ${config.name}`,
    `服务 ID: ${config.serviceId}`,
    `Gateway: ${config.gatewayUrl}`,
    "",
  ].join("\n"),
);

while (!stopping) {
  client = new ProtocolClient(toAgentWebSocketUrl(config), config.token, {
    request: (method, params) => service.request(method, params),
    notification: (method, params) => service.notification(method, params),
    stream: (message) => service.stream(message),
  });
  service.setTransport({
    event: (event, payload) => client?.event(event, payload) ?? Promise.resolve(),
    stream: (streamId, input) => client?.sendStream(streamId, input) ?? Promise.resolve(),
  });
  try {
    await client.run();
    retryDelay = 1_000;
  } catch (error) {
    if (error instanceof FatalTransportError) {
      process.stderr.write(`传输已停止：${errorMessage(error)}。请更新组件或调整消息上限后重启 Agent。\n`);
      break;
    }
    process.stderr.write(
      `连接 Gateway 失败: ${errorMessage(error)}\n`,
    );
  } finally {
    service.setTransport(undefined);
  }
  if (!stopping) {
    process.stderr.write(`${retryDelay}ms 后重连\n`);
    await delay(retryDelay);
    retryDelay = Math.min(retryDelay * 2, 30_000);
  }
}

// A fatal transport error stops reconnecting but must not terminate offline tasks.
if (!stopping) client?.close();

function shutdown(): void {
  if (stopping) return;
  stopping = true;
  client?.close();
  service.close();
}
