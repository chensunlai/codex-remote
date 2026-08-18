import * as prompts from "@clack/prompts";

export interface AgentSetupInput {
  gatewayUrl?: string;
  token?: string;
  name?: string;
  codexExecutable?: string;
  configPath: string;
}

export interface AgentSetupResult {
  gatewayUrl: string;
  token: string;
  name: string;
  codexExecutable: string;
}

interface TextOptions {
  message: string;
  placeholder?: string;
  initialValue?: string;
  validate?: (value: string | undefined) => string | undefined;
}

interface PasswordOptions {
  message: string;
  mask?: string;
  validate?: (value: string | undefined) => string | undefined;
}

interface ConfirmOptions {
  message: string;
  active?: string;
  inactive?: string;
  initialValue?: boolean;
}

export interface AgentSetupUi {
  intro(message: string): void;
  note(message: string, title?: string): void;
  outro(message: string): void;
  cancel(message: string): void;
  text(options: TextOptions): Promise<string | symbol>;
  password(options: PasswordOptions): Promise<string | symbol>;
  confirm(options: ConfirmOptions): Promise<boolean | symbol>;
  isCancel(value: unknown): boolean;
}

export class AgentSetupCancelledError extends Error {
  constructor() {
    super("Agent setup cancelled");
    this.name = "AgentSetupCancelledError";
  }
}

const clackUi: AgentSetupUi = {
  intro: (message) => prompts.intro(message),
  note: (message, title) => prompts.note(message, title),
  outro: (message) => prompts.outro(message),
  cancel: (message) => prompts.cancel(message),
  text: (options) => prompts.text(options),
  password: (options) => prompts.password(options),
  confirm: (options) => prompts.confirm(options),
  isCancel: (value) => prompts.isCancel(value),
};

export async function promptAgentSetup(
  input: AgentSetupInput,
  normalizeGatewayUrl: (value: string) => string,
  ui: AgentSetupUi = clackUi,
): Promise<AgentSetupResult> {
  ui.intro("Codex Remote Agent");

  const gatewayUrl = input.gatewayUrl
    ? normalizeGatewayUrl(input.gatewayUrl)
    : normalizeGatewayUrl(await requirePrompt(ui, ui.text({
      message: "Gateway 地址",
      placeholder: "https://gateway.example.com",
      validate(value) {
        if (!value?.trim()) return "请输入 Gateway 地址";
        try {
          normalizeGatewayUrl(value);
          return undefined;
        } catch (error) {
          return error instanceof Error ? error.message : "Gateway 地址无效";
        }
      },
    })));

  const name = input.name?.trim() || await requirePrompt(ui, ui.text({
    message: "服务名称",
    placeholder: "my-server",
    validate: (value) => value?.trim() ? undefined : "请输入服务名称",
  }));

  const token = input.token?.trim() || await requirePrompt(ui, ui.password({
    message: "访问令牌",
    mask: "*",
    validate: (value) => value?.trim() ? undefined : "请输入访问令牌",
  }));

  const codexExecutable = input.codexExecutable?.trim() || await requirePrompt(
    ui,
    ui.text({
      message: "Codex 可执行文件",
      initialValue: "codex",
      validate: (value) => value?.trim() ? undefined : "请输入 Codex 可执行文件路径",
    }),
  );

  ui.note(
    [
      `Gateway  ${gatewayUrl}`,
      `服务名称  ${name.trim()}`,
      `Codex     ${codexExecutable.trim()}`,
      `配置文件  ${input.configPath}`,
    ].join("\n"),
    "连接配置",
  );

  const confirmed = await requirePrompt(ui, ui.confirm({
    message: "保存配置并连接？",
    active: "连接",
    inactive: "取消",
    initialValue: true,
  }));
  if (!confirmed) cancelSetup(ui);

  ui.outro("配置已确认，正在连接 Gateway");
  return {
    gatewayUrl,
    token: token.trim(),
    name: name.trim(),
    codexExecutable: codexExecutable.trim(),
  };
}

async function requirePrompt<T>(
  ui: AgentSetupUi,
  prompt: Promise<T | symbol>,
): Promise<T> {
  const value = await prompt;
  if (ui.isCancel(value)) cancelSetup(ui);
  return value as T;
}

function cancelSetup(ui: AgentSetupUi): never {
  ui.cancel("已取消配置");
  throw new AgentSetupCancelledError();
}
