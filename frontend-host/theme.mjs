export function applyTheme(theme, target = document) {
  if (!["dark", "light", "anthropic"].includes(theme)) return false;
  const mode = theme === "dark" ? "dark" : "light";
  const vscodeKind = `vscode-${mode}`;
  target.documentElement.dataset.codexTheme = theme;
  target.documentElement.dataset.theme = mode;
  target.body.classList.remove("vscode-dark", "vscode-light", "vscode-high-contrast", "vscode-high-contrast-light");
  target.body.classList.add(vscodeKind);
  // The official frontend watches this attribute, rather than body classes.
  target.body.dataset.vscodeThemeKind = vscodeKind;
  return true;
}
