import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { compareVersion } from "./official-extension-store.js";

/** Read VS Code's installation index, which excludes superseded package directories. */
export async function localCodexVersion(home = homedir(), serverRoot = process.env.VSCODE_AGENT_FOLDER): Promise<string | undefined> {
  const roots = [serverRoot, join(home, ".vscode-server"), join(home, ".vscode")];
  for (const root of roots) {
    if (!root) continue;
    let entries;
    try { entries = JSON.parse(await readFile(join(root, "extensions", "extensions.json"), "utf8")); }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw error;
    }
    const versions = entries.filter((e: {identifier?: {id?: string}; version?: string}) =>
      e?.identifier?.id?.toLowerCase() === "openai.chatgpt" && /^\d+\.\d+\.\d+$/.test(e.version ?? "")
    ).map((e: {version: string}) => e.version) as string[];
    if (versions.length) return versions.sort(compareVersion).at(-1);
  }
}
