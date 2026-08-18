#!/usr/bin/env node

import { setTimeout as delay } from "node:timers/promises";
import { AgentService } from "./agent-service.js";
import { loadAgentConfig, toAgentWebSocketUrl } from "./config.js";
import { ProtocolClient } from "./protocol-client.js";
import { AgentSetupCancelledError } from "./setup-tui.js";
import { errorMessage } from "./error-message.js";

const config = await loadAgentConfig().catch((error: unknown) => {
  if (error instanceof AgentSetupCancelledError) process.exit(0);
  process.stderr.write(
    `Agent 启动失败: ${errorMessage(error)}\n`,
  );
  process.exit(1);
});
const service = new AgentService(config.codexExecutable);
let client: ProtocolClient | undefined;
let stopping = false;
let retryDelay = 1_000;

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

function shutdown(): void {
  if (stopping) return;
  stopping = true;
  client?.close();
  service.close();
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
