#!/usr/bin/env node

import { resolve } from "node:path";
import { buildGateway } from "./app.js";
import { loadConfig, resolveDataDirectory } from "./config.js";
import { ServiceStore } from "./service-store.js";
import { TokenStore } from "./token-store.js";

const [group = "help", command, ...args] = process.argv.slice(2);

if (group === "start") {
  await start();
} else if (group === "token") {
  await tokenCommand(command, args);
} else if (group === "service") {
  await serviceCommand(command, args);
} else {
  printUsage();
  if (group !== "help" && group !== "--help" && group !== "-h") process.exitCode = 1;
}

async function start(): Promise<void> {
  const config = await loadConfig();
  const { app } = await buildGateway(config);
  const address = await app.listen({ host: config.host, port: config.port });
  app.log.info({ address, dataDirectory: config.dataDirectory }, "Codex Remote gateway started");
  if (config.generatedApiToken) {
    app.log.warn(
      { apiToken: config.generatedApiToken },
      "Generated initial user token; it is shown only for initial setup",
    );
  }
  let closing = false;
  const shutdown = async (signal: string) => {
    if (closing) return;
    closing = true;
    app.log.info({ signal }, "Shutting down gateway");
    await app.close();
  };
  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
}

async function tokenCommand(command: string | undefined, args: string[]): Promise<void> {
  const config = await loadConfig();
  const store = await TokenStore.open(resolve(config.dataDirectory, "tokens.json"));
  await store.import(config.apiTokens);
  if (command === "create") {
    const label = args.join(" ").trim() || "user";
    const result = await store.create(label);
    process.stdout.write(
      `Token ID: ${result.id}\nToken: ${result.token}\n请立即保存；之后只能撤销，不能再次查看。\n`,
    );
  } else if (command === "list") {
    console.table(store.list());
  } else if (command === "revoke") {
    const id = args[0];
    if (!id) throw new Error("用法: codex-remote-gateway token revoke <token-id>");
    await store.revoke(id);
    process.stdout.write(`已撤销 Token: ${id}\n`);
  } else {
    throw new Error("用法: token create [label] | token list | token revoke <token-id>");
  }
}

async function serviceCommand(command: string | undefined, args: string[]): Promise<void> {
  const store = new ServiceStore(resolve(resolveDataDirectory(), "services.json"));
  if (command === "list") {
    const services = await store.listAll();
    console.table(
      services.map((service) => ({
        ownerId: service.ownerId,
        serviceId: service.id,
        name: service.name,
        hostname: service.hostname ?? "",
        lastConnectedAt: service.lastConnectedAt,
      })),
    );
  } else if (command === "remove") {
    const [ownerId, serviceId] = args;
    if (!ownerId || !serviceId) {
      throw new Error(
        "用法: codex-remote-gateway service remove <owner-id> <service-id>",
      );
    }
    await store.delete(ownerId, serviceId);
    process.stdout.write(`已删除服务记录: ${serviceId}\n`);
  } else {
    throw new Error("用法: service list | service remove <owner-id> <service-id>");
  }
}

function printUsage(): void {
  process.stdout.write(
    [
      "codex-remote-gateway <command>",
      "",
      "  start                              前台启动 Gateway",
      "  token create [label]               创建用户令牌",
      "  token list                         列出令牌元数据",
      "  token revoke <token-id>            撤销令牌",
      "  service list                       列出已注册服务",
      "  service remove <owner-id> <id>      删除服务记录",
      "",
    ].join("\n"),
  );
}
