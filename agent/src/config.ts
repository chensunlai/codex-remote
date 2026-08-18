import { randomUUID } from "node:crypto";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { promptAgentSetup } from "./setup-tui.js";

export interface AgentConfig {
  gatewayUrl: string;
  token: string;
  name: string;
  serviceId: string;
  codexExecutable: string;
  configPath: string;
}

interface StoredConfig {
  version: 1;
  gatewayUrl: string;
  token: string;
  name: string;
  serviceId: string;
  codexExecutable?: string;
}

interface Arguments {
  gatewayUrl?: string;
  token?: string;
  name?: string;
  codexExecutable?: string;
  configPath: string;
  configure: boolean;
}

export async function loadAgentConfig(
  argv = process.argv.slice(2),
  env: NodeJS.ProcessEnv = process.env,
): Promise<AgentConfig> {
  const args = parseArguments(argv, env);
  const existing = await readStored(args.configPath);
  const stored = args.configure ? undefined : existing;
  let gatewayUrl = args.gatewayUrl ?? env.CODEX_REMOTE_GATEWAY ?? stored?.gatewayUrl;
  let token = args.token ?? env.CODEX_REMOTE_TOKEN ?? stored?.token;
  let name = args.name ?? env.CODEX_REMOTE_SERVICE_NAME ?? stored?.name;
  let codexExecutable =
    args.codexExecutable
    ?? env.CODEX_REMOTE_CODEX
    ?? stored?.codexExecutable;

  if ((!gatewayUrl || !token || !name) && (!process.stdin.isTTY || !process.stdout.isTTY)) {
    throw new Error(
      "缺少配置；请传入 --gateway、--token、--name，或设置对应环境变量",
    );
  }
  if (!gatewayUrl || !token || !name) {
    const setup = await promptAgentSetup(
      {
        ...(gatewayUrl !== undefined ? { gatewayUrl } : {}),
        ...(token !== undefined ? { token } : {}),
        ...(name !== undefined ? { name } : {}),
        ...(codexExecutable !== undefined ? { codexExecutable } : {}),
        configPath: args.configPath,
      },
      normalizeGatewayUrl,
    );
    gatewayUrl = setup.gatewayUrl;
    token = setup.token;
    name = setup.name;
    codexExecutable = setup.codexExecutable;
  }

  const config: AgentConfig = {
    gatewayUrl: normalizeGatewayUrl(gatewayUrl),
    token: token.trim(),
    name: name.trim(),
    serviceId: existing?.serviceId ?? randomUUID(),
    codexExecutable: codexExecutable?.trim() || "codex",
    configPath: args.configPath,
  };
  if (!config.token || !config.name) throw new Error("访问令牌和服务名不能为空");
  await saveConfig(config);
  return config;
}

export function toAgentWebSocketUrl(config: AgentConfig): URL {
  const url = new URL(config.gatewayUrl);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/agent/v1/connect";
  url.search = "";
  url.searchParams.set("serviceId", config.serviceId);
  url.searchParams.set("name", config.name);
  return url;
}

export function normalizeGatewayUrl(value: string): string {
  const input = value.trim();
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(input) ? input : `https://${input}`;
  const url = new URL(withScheme);
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error("Gateway 地址仅支持 http:// 或 https://");
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("Gateway 地址不应包含账号、查询参数或片段");
  }
  url.pathname = url.pathname.replace(/\/+$/, "");
  return url.toString().replace(/\/$/, "");
}

export function configPathBesideProgram(programPath: string): string {
  return resolve(dirname(programPath), "agent.json");
}

function defaultAgentProgramPath(): string {
  // Standalone releases run on Bun; source and dist development run on Node.
  return process.versions.bun
    ? process.execPath
    : (process.argv[1] ?? process.execPath);
}

function parseArguments(argv: string[], env: NodeJS.ProcessEnv): Arguments {
  const result: Arguments = {
    configPath: resolve(
      env.CODEX_REMOTE_AGENT_CONFIG
        ?? configPathBesideProgram(defaultAgentProgramPath()),
    ),
    configure: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]!;
    const value = argv[index + 1];
    if (argument === "--configure") result.configure = true;
    else if (argument === "--gateway" && value) {
      result.gatewayUrl = value;
      index += 1;
    } else if (argument === "--token" && value) {
      result.token = value;
      index += 1;
    } else if (argument === "--name" && value) {
      result.name = value;
      index += 1;
    } else if (argument === "--codex" && value) {
      result.codexExecutable = value;
      index += 1;
    } else if (argument === "--config" && value) {
      result.configPath = resolve(value);
      index += 1;
    } else if (argument === "--help" || argument === "-h") {
      printUsage();
      process.exit(0);
    } else {
      throw new Error(`未知参数: ${argument}`);
    }
  }
  return result;
}

async function readStored(path: string): Promise<StoredConfig | undefined> {
  try {
    const parsed = JSON.parse(await readFile(path, "utf8")) as StoredConfig;
    if (
      parsed.version !== 1
      || !parsed.gatewayUrl
      || !parsed.token
      || !parsed.name
      || !parsed.serviceId
    ) {
      throw new Error("Agent 配置格式无效");
    }
    return parsed;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

async function saveConfig(config: AgentConfig): Promise<void> {
  await mkdir(dirname(config.configPath), { recursive: true, mode: 0o700 });
  const stored: StoredConfig = {
    version: 1,
    gatewayUrl: config.gatewayUrl,
    token: config.token,
    name: config.name,
    serviceId: config.serviceId,
    codexExecutable: config.codexExecutable,
  };
  await writeFile(config.configPath, `${JSON.stringify(stored, null, 2)}\n`, {
    mode: 0o600,
  });
  await chmod(config.configPath, 0o600);
}

function printUsage(): void {
  process.stdout.write(
    [
      "codex-remote-agent [options]",
      "",
      "  --gateway <url>   Gateway 地址，省略 scheme 时使用 https",
      "  --token <token>   用户访问令牌",
      "  --name <name>     在 App 中显示的服务名",
      "  --codex <path>    Codex 可执行文件，默认 codex",
      "  --configure       忽略已保存的连接参数并重新输入",
      "  --config <path>   配置文件路径",
      "",
    ].join("\n"),
  );
}
