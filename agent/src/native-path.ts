import { posix } from "node:path";

/** Windows root-relative paths depend on a current drive and are not fully qualified. */
export function isNativeAbsolutePath(value: string, platform = process.platform): boolean {
  if (value.includes("\0")) return false;
  if (platform !== "win32") return posix.isAbsolute(value);
  return /^[a-z]:[\\/]/i.test(value) || /^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$)/.test(value)
    || /^\/\/[^/]+\/[^/]+(?:\/|$)/.test(value);
}
