/** Validate remote paths without interpreting them using the Gateway's operating system. */
export function isRemoteAbsolutePath(value: string): boolean {
  if (value.includes("\0")) return false;
  return value.startsWith("/") || /^[a-z]:[\\/]/i.test(value)
    || /^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$)/.test(value);
}
