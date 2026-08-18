import * as prompts from "@clack/prompts";
import { resolve } from "node:path";
import type { ServerConfig } from "./config.js";
import type { ServiceRecord } from "./domain.js";
import { ServiceStore } from "./service-store.js";
import { TokenStore, type TokenRecord } from "./token-store.js";

interface SelectOption {
  value: string;
  label: string;
  hint?: string;
}

interface SelectOptions {
  message: string;
  options: SelectOption[];
  initialValue?: string;
  maxItems?: number;
  showInstructions?: boolean;
}

interface TextOptions {
  message: string;
  initialValue?: string;
  validate?: (value: string | undefined) => string | undefined;
}

interface ConfirmOptions {
  message: string;
  active?: string;
  inactive?: string;
  initialValue?: boolean;
}

export interface GatewayAdminUi {
  intro(message: string): void;
  note(message: string, title?: string): void;
  outro(message: string): void;
  cancel(message: string): void;
  info(message: string): void;
  success(message: string): void;
  warning(message: string): void;
  error(message: string): void;
  select(options: SelectOptions): Promise<string | symbol>;
  text(options: TextOptions): Promise<string | symbol>;
  confirm(options: ConfirmOptions): Promise<boolean | symbol>;
  isCancel(value: unknown): boolean;
}

const clackUi: GatewayAdminUi = {
  intro: (message) => prompts.intro(message),
  note: (message, title) => prompts.note(message, title),
  outro: (message) => prompts.outro(message),
  cancel: (message) => prompts.cancel(message),
  info: (message) => prompts.log.info(message),
  success: (message) => prompts.log.success(message),
  warning: (message) => prompts.log.warn(message),
  error: (message) => prompts.log.error(message),
  select: (options) => prompts.select(options),
  text: (options) => prompts.text(options),
  confirm: (options) => prompts.confirm(options),
  isCancel: (value) => prompts.isCancel(value),
};

export async function runGatewayAdminTui(
  config: ServerConfig,
  ui: GatewayAdminUi = clackUi,
): Promise<void> {
  const tokenStore = await TokenStore.open(resolve(config.dataDirectory, "tokens.json"));
  await tokenStore.import(config.apiTokens);
  const serviceStore = new ServiceStore(resolve(config.dataDirectory, "services.json"));

  ui.intro("Codex Remote Gateway");
  ui.note(
    [
      `监听地址  ${config.host}:${config.port}`,
      `数据目录  ${config.dataDirectory}`,
      `文件限制  下载 ${formatBytes(config.maxDownloadBytes)} · 上传 ${formatBytes(config.maxUploadBytes)}`,
    ].join("\n"),
    "管理控制台",
  );
  if (config.generatedApiToken) {
    ui.note(
      `${config.generatedApiToken}\n\n该令牌只在首次初始化时显示，请立即保存。`,
      "首次访问令牌",
    );
  }

  while (true) {
    const action = await ui.select({
      message: "选择管理项目",
      options: [
        { value: "overview", label: "Gateway 概览", hint: "配置与数据统计" },
        { value: "tokens", label: "访问令牌", hint: "创建、查看或撤销" },
        { value: "services", label: "已注册服务", hint: "查看或删除离线记录" },
        { value: "exit", label: "退出" },
      ],
      initialValue: "overview",
      showInstructions: false,
    });
    if (ui.isCancel(action)) {
      ui.cancel("已退出管理控制台");
      return;
    }

    try {
      if (action === "exit") {
        ui.outro("Gateway 管理完成");
        return;
      }
      if (action === "overview") await showOverview(config, tokenStore, serviceStore, ui);
      else if (action === "tokens") await runTokenMenu(tokenStore, ui);
      else if (action === "services") await runServiceMenu(serviceStore, ui);
    } catch (error) {
      ui.error(error instanceof Error ? error.message : String(error));
    }
  }
}

async function showOverview(
  config: ServerConfig,
  tokens: TokenStore,
  services: ServiceStore,
  ui: GatewayAdminUi,
): Promise<void> {
  const tokenCount = (await tokens.list()).length;
  const serviceCount = (await services.listAll()).length;
  ui.note(
    [
      `监听地址    ${config.host}:${config.port}`,
      `访问令牌    ${tokenCount}`,
      `注册服务    ${serviceCount}`,
      `下载上限    ${formatBytes(config.maxDownloadBytes)}`,
      `上传上限    ${formatBytes(config.maxUploadBytes)}`,
    ].join("\n"),
    "Gateway 概览",
  );
}

async function runTokenMenu(store: TokenStore, ui: GatewayAdminUi): Promise<void> {
  while (true) {
    const action = await ui.select({
      message: "访问令牌",
      options: [
        { value: "create", label: "创建令牌" },
        { value: "list", label: "查看令牌" },
        { value: "revoke", label: "撤销令牌" },
        { value: "back", label: "返回主菜单" },
      ],
      initialValue: "create",
      showInstructions: false,
    });
    if (ui.isCancel(action) || action === "back") return;
    if (action === "create") await createToken(store, ui);
    else if (action === "list") showTokens(await store.list(), ui);
    else if (action === "revoke") await revokeToken(store, ui);
  }
}

async function createToken(store: TokenStore, ui: GatewayAdminUi): Promise<void> {
  const existingTags = new Set(
    (await store.list()).map((record) => record.label.toLocaleLowerCase()),
  );
  const label = await ui.text({
    message: "令牌 tag",
    validate(value) {
      const tag = value?.trim();
      if (!tag) return "请输入令牌 tag";
      if (existingTags.has(tag.toLocaleLowerCase())) return "该 tag 已存在";
      return undefined;
    },
  });
  if (ui.isCancel(label)) return;
  const result = await store.create(String(label));
  ui.note(
    [`Tag       ${String(label).trim()}`, `Token ID  ${result.id}`, `Token     ${result.token}`].join("\n"),
    "令牌已创建 · 请立即保存",
  );
}

function showTokens(records: Array<Omit<TokenRecord, "tokenHash">>, ui: GatewayAdminUi): void {
  if (records.length === 0) {
    ui.info("当前没有访问令牌");
    return;
  }
  ui.note(
    records.map((record, index) => [
      `${index + 1}. ${record.label}`,
      `   ${shortId(record.id)} · ${formatTime(record.createdAt)}`,
    ].join("\n")).join("\n"),
    `访问令牌 · ${records.length}`,
  );
}

async function revokeToken(store: TokenStore, ui: GatewayAdminUi): Promise<void> {
  const records = await store.list();
  if (records.length === 0) {
    ui.info("当前没有可撤销的访问令牌");
    return;
  }
  const id = await ui.select({
    message: "选择要撤销的令牌",
    options: records.map((record) => ({
      value: record.id,
      label: record.label,
      hint: `${shortId(record.id)} · ${formatTime(record.createdAt)}`,
    })),
    maxItems: 8,
    showInstructions: false,
  });
  if (ui.isCancel(id)) return;
  const record = records.find((candidate) => candidate.id === id);
  if (!record) throw new Error("令牌记录已发生变化，请重试");
  const confirmed = await ui.confirm({
    message: `撤销 tag 为“${record.label}”的令牌？使用该令牌的 Agent 和 App 将失去访问权限。`,
    active: "撤销",
    inactive: "保留",
    initialValue: false,
  });
  if (ui.isCancel(confirmed) || !confirmed) return;
  await store.revoke(record.id);
  ui.success(`已撤销令牌：${record.label}`);
}

async function runServiceMenu(store: ServiceStore, ui: GatewayAdminUi): Promise<void> {
  while (true) {
    const action = await ui.select({
      message: "已注册服务",
      options: [
        { value: "list", label: "查看服务" },
        { value: "remove", label: "删除离线服务记录" },
        { value: "back", label: "返回主菜单" },
      ],
      initialValue: "list",
      showInstructions: false,
    });
    if (ui.isCancel(action) || action === "back") return;
    if (action === "list") await showService(store, ui);
    else if (action === "remove") await removeService(store, ui);
  }
}

async function showService(store: ServiceStore, ui: GatewayAdminUi): Promise<void> {
  const records = await store.listAll();
  const selected = await selectService(records, "选择要查看的服务", ui);
  if (!selected) return;
  ui.note(formatService(selected), selected.name);
}

async function removeService(store: ServiceStore, ui: GatewayAdminUi): Promise<void> {
  ui.warning("这里只删除离线记录；请先停止对应服务器上的 Agent。");
  const records = await store.listAll();
  const selected = await selectService(records, "选择要删除的离线服务", ui);
  if (!selected) return;
  const confirmed = await ui.confirm({
    message: `删除服务“${selected.name}”？请先确认该服务器上的 Agent 已停止。`,
    active: "删除",
    inactive: "保留",
    initialValue: false,
  });
  if (ui.isCancel(confirmed) || !confirmed) return;
  await store.delete(selected.ownerId, selected.id);
  ui.success(`已删除服务记录：${selected.name}`);
}

async function selectService(
  records: ServiceRecord[],
  message: string,
  ui: GatewayAdminUi,
): Promise<ServiceRecord | undefined> {
  if (records.length === 0) {
    ui.info("当前没有已注册服务");
    return undefined;
  }
  const index = await ui.select({
    message,
    options: records.map((record, recordIndex) => ({
      value: String(recordIndex),
      label: record.name,
      hint: `${record.hostname ?? "未知主机"} · ${formatTime(record.lastConnectedAt)}`,
    })),
    maxItems: 8,
    showInstructions: false,
  });
  if (ui.isCancel(index)) return undefined;
  return records[Number(index)];
}

function formatService(record: ServiceRecord): string {
  return [
    `服务 ID    ${record.id}`,
    `用户 ID    ${shortId(record.ownerId)}`,
    `主机        ${record.hostname ?? "未知"}`,
    `平台        ${[record.platform, record.arch].filter(Boolean).join(" / ") || "未知"}`,
    `Agent       ${record.agentVersion ?? "未知"}`,
    `主目录      ${record.home ?? "未知"}`,
    `最后连接    ${formatTime(record.lastConnectedAt)}`,
  ].join("\n");
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 12)}…` : value;
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function formatBytes(value: number): string {
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GiB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MiB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${value} B`;
}
