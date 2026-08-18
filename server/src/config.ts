import { randomBytes } from "node:crypto";
import { mkdir, readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { writePrivateFileExclusively } from "./private-file.js";

export interface ServerConfig {
  host: string;
  port: number;
  dataDirectory: string;
  apiTokens: string[];
  generatedApiToken?: string;
  maxUploadBytes: number;
  maxDownloadBytes: number;
  tls?: { key: Buffer; cert: Buffer };
}

const DEFAULT_DATA_DIRECTORY = fileURLToPath(new URL("../../.data", import.meta.url));

export function resolveDataDirectory(
  env: NodeJS.ProcessEnv = process.env,
  fallback = defaultDataDirectory(),
): string {
  return env.CODEX_REMOTE_DATA_DIR
    ? resolve(env.CODEX_REMOTE_DATA_DIR)
    : fallback;
}

export function defaultDataDirectory(
  executable = process.execPath,
  standalone = Boolean(process.versions.bun),
): string {
  return standalone
    ? resolve(dirname(executable), "data")
    : DEFAULT_DATA_DIRECTORY;
}

async function readTokens(dataDirectory: string, env: NodeJS.ProcessEnv): Promise<{
  tokens: string[];
  generated?: string;
}> {
  const fromEnvironment = (env.CODEX_REMOTE_API_TOKENS ?? env.CODEX_REMOTE_API_TOKEN ?? "")
    .split(",")
    .map((token) => token.trim())
    .filter(Boolean);
  const fromFiles: string[] = [];
  for (const name of ["api-tokens", "api-token"]) {
    try {
      const content = await readFile(resolve(dataDirectory, name), "utf8");
      fromFiles.push(
        ...content
          .split("\n")
          .map((line) => line.trim())
          .filter((line) => line && !line.startsWith("#")),
      );
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  const tokens = [...new Set([...fromEnvironment, ...fromFiles])];
  if (tokens.length > 0) return { tokens };

  const generated = randomBytes(32).toString("base64url");
  const path = resolve(dataDirectory, "api-token");
  await writePrivateFileExclusively(path, `${generated}\n`);
  return { tokens: [generated], generated };
}

export async function loadConfig(env: NodeJS.ProcessEnv = process.env): Promise<ServerConfig> {
  const dataDirectory = resolveDataDirectory(env);
  await mkdir(dataDirectory, { recursive: true, mode: 0o700 });

  const port = Number.parseInt(env.PORT ?? "6767", 10);
  const maxUploadBytes = Number.parseInt(env.CODEX_REMOTE_MAX_UPLOAD_BYTES ?? "536870912", 10);
  const maxDownloadBytes = Number.parseInt(env.CODEX_REMOTE_MAX_DOWNLOAD_BYTES ?? "10485760", 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT must be 1-65535");
  if (!Number.isSafeInteger(maxUploadBytes) || maxUploadBytes < 1) {
    throw new Error("CODEX_REMOTE_MAX_UPLOAD_BYTES must be a positive integer");
  }
  if (!Number.isSafeInteger(maxDownloadBytes) || maxDownloadBytes < 1) {
    throw new Error("CODEX_REMOTE_MAX_DOWNLOAD_BYTES must be a positive integer");
  }

  const tokenResult = await readTokens(dataDirectory, env);
  let tls: ServerConfig["tls"];
  if (env.CODEX_REMOTE_TLS_KEY && env.CODEX_REMOTE_TLS_CERT) {
    tls = {
      key: await readFile(resolve(env.CODEX_REMOTE_TLS_KEY)),
      cert: await readFile(resolve(env.CODEX_REMOTE_TLS_CERT)),
    };
  } else if (env.CODEX_REMOTE_TLS_KEY || env.CODEX_REMOTE_TLS_CERT) {
    throw new Error("CODEX_REMOTE_TLS_KEY and CODEX_REMOTE_TLS_CERT must be set together");
  }

  return {
    host: env.HOST ?? "0.0.0.0",
    port,
    dataDirectory,
    apiTokens: tokenResult.tokens,
    ...(tokenResult.generated ? { generatedApiToken: tokenResult.generated } : {}),
    maxUploadBytes,
    maxDownloadBytes,
    ...(tls ? { tls } : {}),
  };
}
