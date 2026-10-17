// VS Code supplies these host tokens and the official chat-font-settings message.
export function applyFontScale(scale, target = document) {
  if (typeof scale !== "number" || !Number.isFinite(scale) || scale <= 0) return null;
  const size = Math.round(13 * scale * 100) / 100;
  target.documentElement.style.setProperty("--vscode-font-size", `${size}px`);
  target.documentElement.style.setProperty("--vscode-editor-font-size", `${size}px`);
  return { type: "chat-font-settings", chatFontSize: size, chatCodeFontSize: size };
}
