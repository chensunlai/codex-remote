export function errorMessage(error: unknown): string {
  return describe(error, new Set(), 0) || "未知错误";
}

function describe(value: unknown, seen: Set<object>, depth: number): string {
  if (typeof value === "string") return value.trim();
  if (value === null || value === undefined) return "";
  if (typeof value !== "object") return String(value);
  if (seen.has(value) || depth > 3) return "";
  seen.add(value);

  const record = value as Record<string, unknown>;
  const message = typeof record.message === "string" ? record.message.trim() : "";
  const nested = describe(record.error ?? record.cause, seen, depth + 1);
  const details = ["code", "errno", "syscall", "hostname"]
    .flatMap((key) => {
      const item = record[key];
      return typeof item === "string" || typeof item === "number" ? [`${key}=${item}`] : [];
    });
  const type = typeof record.type === "string" && record.type !== "error"
    ? record.type
    : "";

  const parts = [message, nested, ...details, type].filter(Boolean);
  if (parts.length > 0) return [...new Set(parts)].join("; ");

  const name = typeof record.name === "string" ? record.name : "";
  const text = String(value);
  if (text && text !== "[object Object]" && text !== "[object ErrorEvent]") return text;
  return name || Object.prototype.toString.call(value).slice(8, -1);
}
