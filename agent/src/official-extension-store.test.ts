import { createHash, randomBytes } from "node:crypto";
import { chmod, lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";
import { afterEach, describe, expect, it, vi } from "vitest";
import { maximumExtensionResourceBytes, OfficialExtensionStore, queryLatestOfficialExtension } from "./official-extension-store.js";

const roots: string[] = [];
afterEach(async () => { for (const root of roots.splice(0)) await rm(root, { recursive: true, force: true }); });

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "codex-official-package-"));
  roots.push(root);
  return root;
}

async function source(root: string, version = "26.1007.21434") {
  const directory = join(root, `source-${version}`);
  await mkdir(join(directory, "out"), { recursive: true });
  await mkdir(join(directory, "webview"), { recursive: true });
  await mkdir(join(directory, "bin"), { recursive: true });
  await writeFile(join(directory, "package.json"), JSON.stringify(manifest(version)));
  await writeFile(join(directory, "out", "extension.js"), "exports.activate = () => {};");
  await writeFile(join(directory, "webview", "index.html"), "<!doctype html><p>official</p>");
  await writeFile(join(directory, "webview", "picture.png"), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0xff, 0x00]));
  await writeFile(join(directory, "bin", "codex"), "#!/bin/sh\nexit 0\n");
  await chmod(join(directory, "bin", "codex"), 0o755);
  return directory;
}

const manifest = (version: string) => ({ publisher: "openai", name: "chatgpt", version, main: "./out/extension.js" });
const validArchive = (version: string) => zip([
  ["extension/package.json", Buffer.from(JSON.stringify(manifest(version)))],
  ["extension/out/extension.js", Buffer.from("exports.activate = () => {};")],
  ["extension/webview/index.html", Buffer.from("<!doctype html>official")],
  ["extension/bin/codex", Buffer.from("#!/bin/sh\nexit 0\n"), 0o100755],
]);

async function settled(store: OfficialExtensionStore) {
  await vi.waitFor(async () => expect((await store.status()).operation?.state).not.toBe("running"));
  return store.status();
}

describe("complete official extension packages", () => {
  it("does not promote a candidate that changed after the host verified it", async () => {
    const root = await fixture();
    const store = new OfficialExtensionStore(join(root, "store"));
    await store.importDirectory(await source(root, "1.0.0")); await store.activate();
    const verified = await store.importDirectory(await source(root, "2.0.0"));
    await store.importDirectory(await source(root, "3.0.0"));
    await expect(store.activate(verified.sha256)).rejects.toThrow("插件包已变化");
    expect((await store.active())?.version).toBe("1.0.0");
  });

  it("copies all official bytes and executable modes, activates explicitly and persists without fetching", async () => {
    const root = await fixture();
    const directory = await source(root);
    const fetch = vi.fn<typeof globalThis.fetch>();
    const store = new OfficialExtensionStore(join(root, "store"), { fetch });
    const imported = await store.importDirectory(directory);
    expect((await store.status()).active).toBeNull();
    expect((await store.status()).candidate?.version).toBe("26.1007.21434");
    expect(await readFile(join(imported.directory, "out/extension.js"))).toEqual(await readFile(join(directory, "out/extension.js")));
    expect((await lstat(join(imported.directory, "bin/codex"))).mode & 0o111).toBe(0o111);
    const bytes = await store.resource(imported.version, "webview/picture.png");
    expect(Buffer.from(bytes.content, "base64")).toEqual(await readFile(join(directory, "webview/picture.png")));
    expect(bytes.mime).toBe("image/png");
    await store.activate();
    const resumed = new OfficialExtensionStore(join(root, "store"), { fetch });
    expect((await resumed.active())?.sha256).toBe(imported.sha256);
    expect((await resumed.status()).candidate).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("installs the complete verified VSIX in the background once, with progress and no automatic activation", async () => {
    const root = await fixture();
    const archive = validArchive("26.1010.1");
    let finish!: (value: Response) => void;
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise<Response>((done) => { finish = done; }));
    const latest = vi.fn(async () => ({ version: "26.1010.1", downloadUrl: "https://openai.gallery.vsassets.io/plugin.vsix" }));
    const store = new OfficialExtensionStore(join(root, "store"), { fetch, latest });
    expect((await store.update()).operation?.state).toBe("running");
    await store.update();
    expect(latest).toHaveBeenCalledOnce();
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    finish(new Response(archive, { headers: { "content-length": String(archive.length) } }));
    const status = await settled(store);
    expect(status.operation).toMatchObject({ state: "succeeded", stage: "ready", downloadedBytes: archive.length, totalBytes: archive.length });
    expect(status.active).toBeNull();
    expect(status.candidate?.sha256).toBe(createHash("sha256").update(archive).digest("hex"));
    expect((await readFile(join(status.candidate!.directory, "bin/codex"), "utf8"))).toContain("#!/bin/sh");
    expect((await lstat(join(status.candidate!.directory, "bin/codex"))).mode & 0o111).toBe(0o111);
    await store.activate();
    await store.update();
    await settled(store);
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("compresses large resources only when requested while retaining original bytes, checksum and package identity", async () => {
    const root = await fixture();
    const directory = await source(root);
    const original = Buffer.from("export const official = 'unchanged';\n".repeat(8192));
    await writeFile(join(directory, "webview", "app.js"), original);
    const store = new OfficialExtensionStore(join(root, "store"));
    const imported = await store.importDirectory(directory);
    const plain = await store.resource(imported.version, "webview/app.js");
    expect(plain.encoding).toBeUndefined();
    expect(Buffer.from(plain.content, "base64")).toEqual(original);
    const compressed = await store.resource(imported.version, "webview/app.js", { acceptEncoding: "gzip", packageSha256: imported.sha256 });
    expect(compressed.encoding).toBe("gzip");
    expect(compressed.content.length).toBeLessThan(plain.content.length / 10);
    expect(gunzipSync(Buffer.from(compressed.content, "base64"))).toEqual(original);
    expect(compressed.sha256).toBe(createHash("sha256").update(original).digest("hex"));
    expect(compressed.packageSha256).toBe(imported.sha256);
    const tiny = await store.resource(imported.version, "webview/picture.png", { acceptEncoding: "gzip" });
    expect(tiny.encoding).toBeUndefined();
  });

  it("merges concurrent resource compression and reuses only content-addressed results", async () => {
    const root = await fixture();
    const directory = await source(root);
    const original = Buffer.from("official unchanged bytes\n".repeat(8192));
    await writeFile(join(directory, "webview", "app.js"), original);
    const store = new OfficialExtensionStore(join(root, "store"));
    const installed = await store.importDirectory(directory);
    const requests = await Promise.all(Array.from({ length: 8 }, () => store.resource(installed.version,
      "webview/app.js", { acceptEncoding: "gzip", packageSha256: installed.sha256 })));
    for (const response of requests) expect(gunzipSync(Buffer.from(response.content, "base64")).equals(original)).toBe(true);
    expect(store.resourceDiagnostics().compressions).toBe(1);
    const hits = store.resourceDiagnostics().compressionHits;
    await store.resource(installed.version, "webview/app.js", { acceptEncoding: "gzip" });
    expect(store.resourceDiagnostics()).toMatchObject({ compressions: 1, compressionHits: hits + 1, cachedEntries: 1 });

    const changed = Buffer.from("different actual bytes\n".repeat(8192));
    await writeFile(join(installed.directory, "webview", "app.js"), changed);
    const response = await store.resource(installed.version, "webview/app.js", { acceptEncoding: "gzip" });
    expect(gunzipSync(Buffer.from(response.content, "base64")).equals(changed)).toBe(true);
    expect(response.sha256).toBe(createHash("sha256").update(changed).digest("hex"));
    expect(store.resourceDiagnostics()).toMatchObject({ compressions: 2, cachedEntries: 2 });
  });

  it("still validates installed files and paths after gzip cache hits", async () => {
    const root = await fixture();
    const directory = await source(root);
    await writeFile(join(directory, "webview", "app.js"), "unchanged".repeat(1000));
    const store = new OfficialExtensionStore(join(root, "store"));
    const installed = await store.importDirectory(directory);
    await store.resource(installed.version, "webview/app.js", { acceptEncoding: "gzip" });
    await rm(join(installed.directory, "webview", "app.js"));
    await symlink(join(directory, "webview", "app.js"), join(installed.directory, "webview", "app.js"));
    await expect(store.resource(installed.version, "webview/app.js", { acceptEncoding: "gzip" })).rejects.toThrow("符号链接");
    await expect(store.resource(installed.version, "../app.js", { acceptEncoding: "gzip" })).rejects.toThrow("路径");
    await expect(store.resource(installed.version, "webview/app.js", { acceptEncoding: "gzip", packageSha256: "0".repeat(64) })).rejects.toThrow("未安装");
  });

  it("bounds gzip results to 64 MiB and evicts the least recently used result", async () => {
    const root = await fixture();
    const directory = await source(root);
    // A mostly incompressible resource with a compressible tail occupies about 10 MiB
    // in gzip form; seven distinct paths exercise the production byte limit.
    const resource = Buffer.concat([randomBytes(10 * 1024 * 1024), Buffer.alloc(1024 * 1024)]);
    for (let index = 0; index < 7; index++) await writeFile(join(directory, "webview", `${index}.bin`), resource);
    const store = new OfficialExtensionStore(join(root, "store"));
    const installed = await store.importDirectory(directory);
    for (let index = 0; index < 7; index++) await store.resource(installed.version, `webview/${index}.bin`, { acceptEncoding: "gzip" });
    expect(store.resourceDiagnostics()).toMatchObject({ compressions: 7, cachedEntries: 6 });
    expect(store.resourceDiagnostics().cachedBytes).toBeLessThanOrEqual(64 * 1024 * 1024);
    const recent = await store.resource(installed.version, "webview/6.bin", { acceptEncoding: "gzip" });
    expect(gunzipSync(Buffer.from(recent.content, "base64")).equals(resource)).toBe(true);
    expect(store.resourceDiagnostics().compressions).toBe(7);
    await store.resource(installed.version, "webview/0.bin", { acceptEncoding: "gzip" });
    expect(store.resourceDiagnostics().compressions).toBe(8);
    expect(store.resourceDiagnostics().cachedBytes).toBeLessThanOrEqual(64 * 1024 * 1024);
  }, 20_000);

  it("selects the exact installed package when two packages share a version", async () => {
    const root = await fixture();
    const directory = await source(root);
    const store = new OfficialExtensionStore(join(root, "store"));
    const first = await store.importDirectory(directory);
    await store.activate();
    await writeFile(join(directory, "webview", "index.html"), "replacement package");
    const second = await store.importDirectory(directory);
    expect(second.sha256).not.toBe(first.sha256);
    for (const imported of [first, second]) {
      const resource = await store.resource(imported.version, "webview/index.html", { packageSha256: imported.sha256 });
      expect(resource.packageSha256).toBe(imported.sha256);
      expect(Buffer.from(resource.content, "base64")).toEqual(await readFile(join(imported.directory, "webview/index.html")));
    }
    await expect(store.resource(first.version, "webview/index.html", { packageSha256: "f".repeat(64) })).rejects.toThrow("未安装");
  });

  it("keeps the active package and pending candidate intact when an update fails", async () => {
    const root = await fixture();
    const store = new OfficialExtensionStore(join(root, "store"), {
      latest: async () => ({ version: "26.1011.1", downloadUrl: "https://openai.gallery.vsassets.io/plugin.vsix" }),
      fetch: vi.fn<typeof globalThis.fetch>(async () => new Response("broken archive")),
    });
    const current = await store.importDirectory(await source(root));
    await store.activate();
    const pending = await store.importDirectory(await source(root, "26.1009.1"));
    await store.update();
    const result = await settled(store);
    expect(result.operation?.state).toBe("failed");
    expect(result.active?.sha256).toBe(current.sha256);
    expect(result.candidate?.sha256).toBe(pending.sha256);
  });

  it.each([
    ["version mismatch", validArchive("26.1010.2")],
    ["traversal", zip([["extension/../../escape", Buffer.from("escape")]])],
    ["symlink", zip([["extension/link", Buffer.from("/etc/passwd"), 0o120777]])],
    ["duplicate", zip([["extension/package.json", Buffer.from("{}")], ["extension/package.json", Buffer.from("{}")]])],
  ])("rejects %s without activating", async (_label, archive) => {
    const root = await fixture();
    const store = new OfficialExtensionStore(join(root, "store"), {
      latest: async () => ({ version: "26.1010.1", downloadUrl: "https://openai.gallery.vsassets.io/plugin.vsix" }),
      fetch: vi.fn<typeof globalThis.fetch>(async () => new Response(archive)),
    });
    await store.update();
    const status = await settled(store);
    expect(status.operation?.state).toBe("failed");
    expect(status.active).toBeNull();
    expect(status.candidate).toBeNull();
  });

  it("rejects resource traversal, uninstalled versions, symbolic links and oversized binary resources", async () => {
    const root = await fixture();
    const directory = await source(root);
    const store = new OfficialExtensionStore(join(root, "store"));
    const imported = await store.importDirectory(directory);
    for (const path of ["../out/extension.js", "/etc/passwd", "webview\\index.html", "webview/./index.html"]) {
      await expect(store.resource(imported.version, path)).rejects.toThrow("路径");
    }
    await expect(store.resource("26.1.1", "package.json")).rejects.toThrow("未安装");
    await symlink(join(root, "outside"), join(imported.directory, "link"));
    await expect(store.resource(imported.version, "link")).rejects.toThrow("符号链接");
    await writeFile(join(imported.directory, "large.dat"), Buffer.alloc(maximumExtensionResourceBytes + 1));
    await expect(store.resource(imported.version, "large.dat")).rejects.toThrow("12 MiB");
  });

  it("rejects imported symlinks and foreign publishers", async () => {
    const root = await fixture();
    const directory = await source(root);
    const store = new OfficialExtensionStore(join(root, "store"));
    await symlink("/etc/passwd", join(directory, "link"));
    await expect(store.importDirectory(directory)).rejects.toThrow("符号链接");
    await rm(join(directory, "link"));
    await writeFile(join(directory, "package.json"), JSON.stringify({ ...manifest("26.1.1"), publisher: "other" }));
    await expect(store.importDirectory(directory)).rejects.toThrow("身份");
  });
});

describe("official Marketplace source", () => {
  it("chooses the highest stable version for the Agent architecture, excluding prereleases", async () => {
    const version = (value: string, targetPlatform: string, pre = false) => ({ version: value, targetPlatform,
      properties: [{ key: "Microsoft.VisualStudio.Code.PreRelease", value: String(pre) }],
      files: [{ assetType: "Microsoft.VisualStudio.Services.VSIXPackage", source: `https://openai.gallery.vsassets.io/${value}` }] });
    const fetch = vi.fn<typeof globalThis.fetch>(async () => Response.json({ results: [{ extensions: [{ publisher: { publisherName: "OpenAI" }, extensionName: "chatgpt", versions: [
      version("26.1012.1", "linux-arm64", true), version("26.1009.1", "linux-arm64"), version("26.1011.1", "linux-x64"), version("26.1010.1", "linux-arm64"),
    ] }] }] }));
    expect(await queryLatestOfficialExtension(fetch, "linux-arm64")).toEqual({ version: "26.1010.1", downloadUrl: "https://openai.gallery.vsassets.io/26.1010.1" });
    expect(JSON.parse(String(fetch.mock.calls[0]?.[1]?.body)).filters[0].criteria).toEqual([{ filterType: 7, value: "OpenAI.chatgpt" }]);
  });
});

/** Minimal stored ZIP writer, used only to produce independent malicious/archive fixtures. */
function zip(files: Array<[string, Buffer, number?]>): Buffer {
  const locals: Buffer[] = [], central: Buffer[] = [];
  let offset = 0;
  for (const [name, data, mode = 0o100644] of files) {
    const filename = Buffer.from(name);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc32(data), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(filename.length, 26);
    locals.push(local, filename, data);
    const header = Buffer.alloc(46);
    header.writeUInt32LE(0x02014b50); header.writeUInt16LE(0x0314, 4); header.writeUInt16LE(20, 6);
    header.writeUInt32LE(crc32(data), 16); header.writeUInt32LE(data.length, 20); header.writeUInt32LE(data.length, 24);
    header.writeUInt16LE(filename.length, 28); header.writeUInt32LE((mode << 16) >>> 0, 38); header.writeUInt32LE(offset, 42);
    central.push(header, filename); offset += local.length + filename.length + data.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

function crc32(data: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
