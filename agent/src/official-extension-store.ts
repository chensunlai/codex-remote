import { createHash, randomUUID } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { chmod, cp, lstat, mkdir, readFile, readdir, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import { extname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { promisify } from "node:util";
import { gzip } from "node:zlib";
import * as yauzl from "yauzl";
import { extensionTools, type ExtensionToolId } from "./extension-tools.js";

export interface OfficialExtensionManifest extends Record<string, unknown> {
  name: string;
  publisher: string;
  version: string;
  main: string;
}

export interface OfficialExtensionPackage {
  version: string;
  directory: string;
  /** Downloaded VSIX digest; imported directories use a deterministic file-tree digest. */
  sha256: string;
  manifest: OfficialExtensionManifest;
}

export interface ExtensionUpdateOperation {
  state: "running" | "succeeded" | "failed";
  stage: "checking" | "downloading" | "extracting" | "verifying" | "ready";
  version?: string;
  downloadedBytes: number;
  totalBytes?: number;
  bytesPerSecond: number;
  error?: string;
}

export interface OfficialExtensionStatus {
  active: OfficialExtensionPackage | null;
  candidate: OfficialExtensionPackage | null;
  operation: ExtensionUpdateOperation | null;
}

export interface OfficialExtensionSource { version: string; downloadUrl: string }
export interface OfficialExtensionResource {
  content: string;
  mime: string;
  sha256: string;
  packageSha256: string;
  encoding?: "gzip";
}
export interface OfficialExtensionStoreOptions {
  toolId?: ExtensionToolId;
  fetch?: typeof globalThis.fetch;
  latest?: () => Promise<OfficialExtensionSource>;
  targetPlatform?: string;
}

interface StoreState { active: string | null; candidate: string | null }
const maximumArchiveBytes = 1024 * 1024 * 1024;
const maximumExtractedBytes = 2 * 1024 * 1024 * 1024;
// Leave room for JSON/base64 envelopes inside the 16 MiB transport frame.
export const maximumExtensionResourceBytes = 12 * 1024 * 1024 - 4096;
const gzipAsync = promisify(gzip);
const maximumCompressedResourceBytes = 64 * 1024 * 1024;
const maximumCompressedResourceEntries = 1024;

/** Complete, unmodified official packages. No private frontend exports or adapters. */
export class OfficialExtensionStore {
  private operation: ExtensionUpdateOperation | null = null;
  private inFlight: Promise<void> | null = null;
  private readonly fetch: typeof globalThis.fetch;
  private readonly latest: () => Promise<OfficialExtensionSource>;
  private readonly toolId: ExtensionToolId;
  private mutation: Promise<void> = Promise.resolve();
  private readonly resourcePending = new Map<string, Promise<OfficialExtensionResource>>();
  private readonly compressedResources = new Map<string, Buffer | null>();
  private readonly compressionPending = new Map<string, Promise<Buffer | null>>();
  private compressedResourceBytes = 0;
  private compressionHits = 0;
  private compressions = 0;

  constructor(private readonly root: string, options: OfficialExtensionStoreOptions = {}) {
    this.fetch = options.fetch ?? globalThis.fetch;
    this.toolId = options.toolId ?? "codex";
    this.latest = options.latest ?? (() => queryLatestOfficialExtension(this.fetch, options.targetPlatform, this.toolId));
  }

  async status(): Promise<OfficialExtensionStatus> {
    const state = await this.readState();
    return {
      active: await this.readPackage(state.active),
      candidate: await this.readPackage(state.candidate),
      operation: this.operation ? { ...this.operation } : null,
    };
  }

  async active(): Promise<OfficialExtensionPackage | null> {
    return this.readPackage((await this.readState()).active);
  }

  /** Start one manual check/download; callers poll status instead of holding an RPC open. */
  async update(): Promise<OfficialExtensionStatus> {
    if (!this.inFlight) {
      this.operation = { state: "running", stage: "checking", downloadedBytes: 0, bytesPerSecond: 0 };
      this.inFlight = this.performUpdate().catch((error: unknown) => {
        this.operation = { ...this.operation!, state: "failed", error: error instanceof Error ? error.message : String(error) };
      }).finally(() => { this.inFlight = null; });
    }
    return this.status();
  }

  /** Explicit application boundary: never changes the running host on a download. */
  async activate(expectedSha256?: string): Promise<OfficialExtensionPackage> {
    return this.serial(async () => {
      const state = await this.readState();
      const candidate = await this.readPackage(state.candidate);
      const current = await this.readPackage(state.active);
      if (expectedSha256 !== undefined && candidate?.sha256 !== expectedSha256) throw new Error("待应用的插件包已变化，请重新检查更新");
      if (!candidate) {
        if (current) return current;
        throw new Error("尚未安装官方扩展，请先检查并更新前端");
      }
      await this.writeState({ active: state.candidate, candidate: null });
      return candidate;
    });
  }

  /** Development/test import. Copies every regular file unchanged into the candidate slot. */
  async importDirectory(source: string): Promise<OfficialExtensionPackage> {
    const sourceRoot = await realpath(source);
    const manifest = await verifyExtension(sourceRoot, undefined, this.toolId);
    await inspectTree(sourceRoot);
    const digest = await treeDigest(sourceRoot);
    return this.serial(async () => {
      const stage = join(this.root, `.import-${randomUUID()}`);
      await mkdir(this.root, { recursive: true, mode: 0o700 });
      try {
        await cp(sourceRoot, join(stage, "extension"), { recursive: true, preserveTimestamps: true, verbatimSymlinks: true });
        await inspectTree(join(stage, "extension"));
        await verifyExtension(join(stage, "extension"), manifest.version, this.toolId);
        return await this.saveCandidate(stage, manifest, digest);
      } finally { await rm(stage, { recursive: true, force: true }); }
    });
  }

  async resource(version: string, path: string, options: { acceptEncoding?: "gzip"; packageSha256?: string } = {}): Promise<OfficialExtensionResource> {
    const state = await this.readState();
    const packages = await Promise.all([this.readPackage(state.active), this.readPackage(state.candidate)]);
    const installed = packages.find((item) => item?.version === version
      && (options.packageSha256 === undefined || item.sha256 === options.packageSha256));
    if (!installed) throw new Error("官方扩展资源版本未安装");
    const pathname = safeRelativePath(path);
    const target = resolve(installed.directory, pathname);
    await rejectSymlinkPath(installed.directory, pathname);
    const metadata = await stat(target, { bigint: true });
    if (!metadata.isFile() || metadata.size > maximumExtensionResourceBytes) throw new Error("扩展资源不是文件或超过 12 MiB");
    // Every caller still validates the package, path and file. Only identical validated reads
    // in flight are shared; later requests hash the actual bytes before consulting gzip results.
    const key = JSON.stringify([installed.sha256, pathname, options.acceptEncoding,
      `${metadata.dev}:${metadata.ino}:${metadata.size}:${metadata.mtimeNs}:${metadata.ctimeNs}`]);
    const pending = this.resourcePending.get(key);
    if (pending) return pending;
    const result = this.readResource(installed, pathname, target, options.acceptEncoding);
    this.resourcePending.set(key, result);
    try { return await result; } finally {
      if (this.resourcePending.get(key) === result) this.resourcePending.delete(key);
    }
  }

  resourceDiagnostics(): { compressionHits: number; compressions: number; cachedBytes: number; cachedEntries: number } {
    return { compressionHits: this.compressionHits, compressions: this.compressions,
      cachedBytes: this.compressedResourceBytes, cachedEntries: this.compressedResources.size };
  }

  private async readResource(installed: OfficialExtensionPackage, path: string, target: string, encoding?: "gzip"): Promise<OfficialExtensionResource> {
    const content = await readFile(target);
    if (content.length > maximumExtensionResourceBytes) throw new Error("扩展资源不是文件或超过 12 MiB");
    const result = { mime: mimeType(path), sha256: createHash("sha256").update(content).digest("hex"), packageSha256: installed.sha256 };
    if (encoding === "gzip" && content.length >= 1024) {
      const key = JSON.stringify([installed.sha256, path, result.sha256]);
      const compressed = await this.compressResource(key, content);
      if (compressed) return { ...result, encoding: "gzip", content: compressed.toString("base64") };
    }
    return { ...result, content: content.toString("base64") };
  }

  private async compressResource(key: string, content: Buffer): Promise<Buffer | null> {
    if (this.compressedResources.has(key)) {
      const compressed = this.compressedResources.get(key)!;
      this.compressedResources.delete(key);
      this.compressedResources.set(key, compressed);
      this.compressionHits++;
      return compressed;
    }
    const pending = this.compressionPending.get(key);
    if (pending) return pending;
    const result = gzipAsync(content).then((candidate) => {
      this.compressions++;
      const compressed = candidate.length < content.length ? candidate : null;
      this.compressedResources.set(key, compressed);
      this.compressedResourceBytes += compressed?.length ?? 0;
      while (this.compressedResourceBytes > maximumCompressedResourceBytes || this.compressedResources.size > maximumCompressedResourceEntries) {
        const oldest = this.compressedResources.keys().next().value!;
        this.compressedResourceBytes -= this.compressedResources.get(oldest)?.length ?? 0;
        this.compressedResources.delete(oldest);
      }
      return compressed;
    });
    this.compressionPending.set(key, result);
    try { return await result; } finally { this.compressionPending.delete(key); }
  }

  private async performUpdate(): Promise<void> {
    const source = await this.latest();
    validVersion(source.version);
    this.operation = { ...this.operation!, version: source.version };
    const state = await this.status();
    if ([state.active, state.candidate].some((item) => item?.version === source.version)) {
      this.operation = { ...this.operation!, state: "succeeded", stage: "ready" };
      return;
    }
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const archive = join(this.root, `.download-${randomUUID()}.vsix`);
    const stage = join(this.root, `.extract-${randomUUID()}`);
    try {
      officialUrl(source.downloadUrl);
      const response = await this.fetch(source.downloadUrl, { signal: AbortSignal.timeout(15 * 60_000) });
      if (response.url) officialUrl(response.url);
      if (!response.ok || !response.body) throw new Error(`官方扩展下载失败：HTTP ${response.status}`);
      const header = response.headers.get("content-length");
      const compressed = Boolean(response.headers.get("content-encoding"));
      const declared = header === null || compressed ? undefined : Number(header);
      const total = declared !== undefined && Number.isSafeInteger(declared) && declared >= 0 ? declared : undefined;
      if (total !== undefined && total > maximumArchiveBytes) throw new Error("官方扩展包超过 1 GiB");
      this.operation = { ...this.operation!, stage: "downloading", ...(total !== undefined ? { totalBytes: total } : {}) };
      const started = performance.now();
      const hash = createHash("sha256");
      const counter = new Transform({ transform: (chunk: Buffer, _encoding, callback) => {
        const received = this.operation!.downloadedBytes + chunk.byteLength;
        if (received > maximumArchiveBytes) { callback(new Error("官方扩展包超过 1 GiB")); return; }
        hash.update(chunk);
        this.operation = { ...this.operation!, downloadedBytes: received, bytesPerSecond: received * 1000 / Math.max(1, performance.now() - started) };
        callback(null, chunk);
      } });
      await pipeline(response.body as unknown as NodeJS.ReadableStream, counter, createWriteStream(archive, { flags: "wx", mode: 0o600 }));
      if (total !== undefined && this.operation.downloadedBytes !== total) throw new Error("官方扩展下载不完整");
      this.operation = { ...this.operation!, stage: "extracting", totalBytes: total ?? this.operation.downloadedBytes };
      await extractVsix(archive, stage);
      this.operation = { ...this.operation!, stage: "verifying" };
      const manifest = await verifyExtension(join(stage, "extension"), source.version, this.toolId);
      await this.serial(() => this.saveCandidate(stage, manifest, hash.digest("hex")));
      this.operation = { ...this.operation!, state: "succeeded", stage: "ready" };
    } finally {
      await rm(archive, { force: true });
      await rm(stage, { recursive: true, force: true });
    }
  }

  private async saveCandidate(stage: string, manifest: OfficialExtensionManifest, digest: string): Promise<OfficialExtensionPackage> {
    const key = `${manifest.version}-${digest.slice(0, 16)}`;
    const directory = join(this.root, "packages", key);
    await mkdir(join(this.root, "packages"), { recursive: true, mode: 0o700 });
    await writeFile(join(stage, "package.json"), JSON.stringify({ version: manifest.version, sha256: digest }), { mode: 0o600 });
    try { await rename(stage, directory); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOTEMPTY" && (error as NodeJS.ErrnoException).code !== "EEXIST") throw error; }
    const installed = await this.readPackage(key);
    if (!installed) throw new Error("无法保存完整官方扩展");
    const state = await this.readState();
    await this.writeState({ ...state, candidate: state.active === key ? null : key });
    // Keep at most the active package and one pending candidate. The previous active is
    // retained until the next successful download, so an existing host can finish using it.
    const keep = new Set([state.active, key]);
    for (const entry of await readdir(join(this.root, "packages"))) {
      if (!keep.has(entry)) await rm(join(this.root, "packages", entry), { recursive: true, force: true });
    }
    return installed;
  }

  private async readPackage(key: string | null): Promise<OfficialExtensionPackage | null> {
    if (!key) return null;
    if (!/^[0-9][0-9A-Za-z.+-]*-[0-9a-f]{16}$/.test(key)) throw new Error("官方扩展安装记录无效");
    const root = join(this.root, "packages", key);
    try {
      const metadata = JSON.parse(await readFile(join(root, "package.json"), "utf8")) as { version: string; sha256: string };
      if (!/^[0-9a-f]{64}$/.test(metadata.sha256)) throw new Error("官方扩展摘要无效");
      const directory = join(root, "extension");
      const manifest = await verifyExtension(directory, metadata.version, this.toolId);
      return { version: manifest.version, directory, sha256: metadata.sha256, manifest };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
      throw error;
    }
  }

  private async readState(): Promise<StoreState> {
    try {
      const state = JSON.parse(await readFile(join(this.root, "state.json"), "utf8")) as StoreState;
      if ((state.active !== null && typeof state.active !== "string") || (state.candidate !== null && typeof state.candidate !== "string")) throw new Error("官方扩展安装记录无效");
      return state;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return { active: null, candidate: null };
      throw error;
    }
  }

  private async writeState(state: StoreState): Promise<void> {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const path = join(this.root, `.state-${randomUUID()}`);
    try {
      await writeFile(path, JSON.stringify(state), { mode: 0o600 });
      await rename(path, join(this.root, "state.json"));
    } finally { await rm(path, { force: true }); }
  }

  private async serial<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.mutation;
    let done!: () => void;
    this.mutation = new Promise<void>((resolveDone) => { done = resolveDone; });
    await previous;
    try { return await operation(); } finally { done(); }
  }
}

export async function queryLatestOfficialExtension(fetch: typeof globalThis.fetch = globalThis.fetch, targetPlatform?: string, toolId: ExtensionToolId = "codex"): Promise<OfficialExtensionSource> {
  const tool = extensionTools[toolId];
  const target = targetPlatform ?? officialExtensionTarget();
  if (toolId === "claude" && !/^(?:linux|darwin)-(?:x64|arm64)$/.test(target)) throw new Error("Claude Code 暂仅支持 Linux/macOS x64/arm64 Agent");
  const response = await fetch("https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery", {
    method: "POST",
    headers: { "Accept": "application/json;api-version=7.2-preview.1", "Content-Type": "application/json" },
    body: JSON.stringify({ filters: [{ criteria: [{ filterType: 7, value: `${tool.publisher}.${tool.name}` }], pageNumber: 1, pageSize: 1 }], flags: 147 }),
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`官方扩展版本查询失败：HTTP ${response.status}`);
  const body = await response.json() as { results?: Array<{ extensions?: Array<{ publisher?: { publisherName?: string }; extensionName?: string; versions?: Array<{ version: string; targetPlatform?: string; properties?: Array<{ key: string; value: string }>; files?: Array<{ assetType: string; source: string }> }> }> }> };
  const extension = body.results?.[0]?.extensions?.[0];
  if (extension?.publisher?.publisherName?.toLowerCase() !== tool.publisher.toLowerCase() || extension.extensionName !== tool.name) throw new Error("官方扩展发布者身份不匹配");
  const versions = (extension.versions ?? []).filter((entry) => entry.targetPlatform === target && !entry.properties?.some((property) => property.key === "Microsoft.VisualStudio.Code.PreRelease" && property.value.toLowerCase() === "true"));
  versions.sort((left, right) => compareVersion(right.version, left.version));
  const latest = versions[0];
  const downloadUrl = latest?.files?.find((file) => file.assetType === "Microsoft.VisualStudio.Services.VSIXPackage")?.source;
  if (!latest || !downloadUrl) throw new Error(`未找到适用于 ${target} 的官方扩展正式版`);
  validVersion(latest.version);
  officialUrl(downloadUrl);
  return { version: latest.version, downloadUrl };
}

export function officialExtensionTarget(platform: string = process.platform, arch: string = process.arch): string {
  if (["linux", "darwin", "win32"].includes(platform) && ["x64", "arm64"].includes(arch)) return `${platform}-${arch}`;
  throw new Error(`暂不支持此平台的官方扩展：${platform}-${arch}`);
}

function compareVersion(a: string, b: string): number {
  const av = a.split(/[.+-]/).map(Number), bv = b.split(/[.+-]/).map(Number);
  for (let index = 0; index < Math.max(av.length, bv.length); index++) {
    const difference = (av[index] ?? 0) - (bv[index] ?? 0);
    if (difference) return difference;
  }
  return a.localeCompare(b);
}

function validVersion(version: string): void {
  if (!/^\d+\.\d+\.\d+(?:[+-][0-9A-Za-z.-]+)?$/.test(version)) throw new Error("官方扩展版本无效");
}

function officialUrl(value: string): void {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || !(url.hostname === "marketplace.visualstudio.com" || url.hostname.endsWith(".vsassets.io"))) throw new Error("非官方扩展下载地址");
}

function safeRelativePath(path: string): string {
  if (!path || path.includes("\\") || path.includes("\0") || isAbsolute(path) || /^[A-Za-z]:/.test(path) || path.split("/").some((part) => part === "." || part === ".." || part === "")) throw new Error("扩展资源路径无效");
  return path;
}

async function rejectSymlinkPath(root: string, path: string): Promise<void> {
  let current = root;
  if ((await lstat(root)).isSymbolicLink()) throw new Error("扩展资源禁止符号链接");
  for (const segment of path.split("/")) {
    current = join(current, segment);
    if ((await lstat(current)).isSymbolicLink()) throw new Error("扩展资源禁止符号链接");
  }
  const canonical = await realpath(current);
  if (!canonical.startsWith(`${await realpath(root)}${sep}`)) throw new Error("扩展资源路径无效");
}

async function verifyExtension(directory: string, expectedVersion?: string, toolId: ExtensionToolId = "codex"): Promise<OfficialExtensionManifest> {
  const tool = extensionTools[toolId];
  await rejectSymlinkPath(directory, "package.json");
  const metadata = await stat(join(directory, "package.json"));
  if (metadata.size > 1024 * 1024) throw new Error("官方扩展清单超过 1 MiB");
  const manifest = JSON.parse(await readFile(join(directory, "package.json"), "utf8")) as OfficialExtensionManifest;
  if (manifest.publisher?.toLowerCase() !== tool.publisher.toLowerCase() || manifest.name !== tool.name || typeof manifest.main !== "string") throw new Error("官方扩展清单身份或入口无效");
  validVersion(manifest.version);
  if (expectedVersion && manifest.version !== expectedVersion) throw new Error("官方扩展版本不匹配");
  const main = manifest.main.startsWith("./") ? manifest.main.slice(2) : manifest.main;
  safeRelativePath(main);
  await rejectSymlinkPath(directory, main);
  if (!(await stat(join(directory, main))).isFile()) throw new Error("官方扩展缺少入口文件");
  return manifest;
}

async function inspectTree(root: string): Promise<void> {
  let bytes = 0, count = 0;
  async function walk(directory: string): Promise<void> {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      count++;
      if (count > 100_000) throw new Error("官方扩展文件数量超过限制");
      const path = join(directory, entry.name);
      const metadata = await lstat(path);
      if (metadata.isSymbolicLink() || (!metadata.isFile() && !metadata.isDirectory())) throw new Error("官方扩展禁止符号链接或特殊文件");
      if (metadata.isDirectory()) await walk(path);
      else { bytes += metadata.size; if (bytes > maximumExtractedBytes) throw new Error("官方扩展资源超过 2 GiB"); }
    }
  }
  if ((await lstat(root)).isSymbolicLink()) throw new Error("官方扩展禁止符号链接");
  await walk(root);
}

async function treeDigest(root: string): Promise<string> {
  const digest = createHash("sha256");
  async function walk(directory: string): Promise<void> {
    for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await walk(path);
      else {
        const name = relative(root, path).split(sep).join("/");
        const metadata = await stat(path);
        digest.update(`${name}\0${metadata.size}\0${metadata.mode & 0o777}\0`);
        for await (const chunk of createReadStream(path)) digest.update(chunk);
      }
    }
  }
  await walk(root);
  return digest.digest("hex");
}

async function extractVsix(archive: string, directory: string): Promise<void> {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const zip = await new Promise<yauzl.ZipFile>((resolveZip, reject) => yauzl.open(archive, { lazyEntries: true, strictFileNames: true, validateEntrySizes: true }, (error, file) => error || !file ? reject(error ?? new Error("官方扩展包无效")) : resolveZip(file)));
  let bytes = 0, entries = 0;
  const paths = new Set<string>();
  try {
    await new Promise<void>((resolveExtraction, reject) => {
      zip.on("error", reject);
      zip.on("end", resolveExtraction);
      zip.on("entry", (entry: yauzl.Entry) => {
        void (async () => {
          entries++;
          if (entries > 100_000) throw new Error("官方扩展文件数量超过限制");
          const name = entry.fileName.endsWith("/") ? entry.fileName.slice(0, -1) : entry.fileName;
          safeRelativePath(name);
          const mode = entry.externalFileAttributes >>> 16;
          if ((mode & 0o170000) === 0o120000) throw new Error("官方扩展包禁止符号链接");
          if (entry.generalPurposeBitFlag & 1) throw new Error("官方扩展包不能加密");
          if (!name.startsWith("extension/")) { zip.readEntry(); return; }
          if (paths.has(name)) throw new Error("官方扩展包包含重复路径");
          paths.add(name);
          bytes += entry.uncompressedSize;
          if (bytes > maximumExtractedBytes) throw new Error("官方扩展资源超过 2 GiB");
          const destination = join(directory, name);
          if (entry.fileName.endsWith("/")) await mkdir(destination, { recursive: true, mode: 0o755 });
          else {
            await mkdir(resolve(destination, ".."), { recursive: true, mode: 0o755 });
            const input = await new Promise<NodeJS.ReadableStream>((resolveStream, rejectStream) => zip.openReadStream(entry, (error, stream) => error || !stream ? rejectStream(error ?? new Error("扩展资源读取失败")) : resolveStream(stream)));
            await pipeline(input, createWriteStream(destination, { flags: "wx", mode: mode & 0o111 ? 0o755 : 0o644 }));
            await chmod(destination, mode & 0o111 ? 0o755 : 0o644);
          }
          zip.readEntry();
        })().catch(reject);
      });
      zip.readEntry();
    });
  } finally { zip.close(); }
}

function mimeType(path: string): string {
  return ({ ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf", ".wasm": "application/wasm", ".mp3": "audio/mpeg", ".wav": "audio/wav" } as Record<string, string>)[extname(path).toLowerCase()] ?? "application/octet-stream";
}
