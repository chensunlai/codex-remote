export type ExtensionToolId = "codex" | "claude";

/** Only package identity and public VS Code entrypoints belong here. */
export const extensionTools = {
  codex: {
    publisher: "OpenAI", name: "chatgpt", sidebarViewId: "chatgpt.sidebarView", label: "Codex",
  },
  claude: {
    publisher: "Anthropic", name: "claude-code", sidebarViewId: "claudeVSCodeSidebar", label: "Claude Code",
  },
} as const;

export function extensionToolId(value: unknown): ExtensionToolId {
  if (value === undefined || value === "codex") return "codex";
  if (value === "claude") return "claude";
  throw new Error("不支持的 Coding Agent 工具");
}
