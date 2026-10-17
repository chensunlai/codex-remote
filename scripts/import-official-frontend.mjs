import { createHash, randomUUID } from "node:crypto";
import { cp, mkdir, mkdtemp, readFile, readdir, rename, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const options = Object.fromEntries(process.argv.slice(2).reduce((result, value, index, args) => {
  if (value.startsWith("--")) result.push([value.slice(2), args[index + 1]]);
  return result;
}, []));
if (!options.source) {
  console.error("Usage: node scripts/import-official-frontend.mjs --source <extension-directory|VSIX> [--output <directory>]");
  process.exit(1);
}
let temporary;
let stage;
try {
  let source = path.resolve(options.source);
  if (!(await stat(source)).isDirectory()) {
    temporary = await mkdtemp(path.join(tmpdir(), "codex-frontend-import-"));
    await run("python3", ["-c", `import gzip,pathlib,shutil,sys,zipfile
archivePath=pathlib.Path(sys.argv[1])
root=pathlib.Path(sys.argv[2])
with archivePath.open('rb') as f: magic=f.read(2)
if magic == b'\\x1f\\x8b':
    target=root/'download.vsix'
    with gzip.open(archivePath,'rb') as source,target.open('wb') as dest: shutil.copyfileobj(source,dest)
    archivePath=target
archive=zipfile.ZipFile(archivePath)
for item in archive.infolist():
    name=pathlib.PurePosixPath(item.filename)
    if name.is_absolute() or '..' in name.parts: raise ValueError('Unsafe archive path')
    if (item.external_attr >> 16) & 0o170000 == 0o120000: raise ValueError('Archive links are not supported')
    if item.filename == 'extension/package.json' or item.filename.startswith('extension/webview/'):
        archive.extract(item,root)
`, source, temporary]);
    source = path.join(temporary, "extension");
  }
  const manifest = JSON.parse(await readFile(path.join(source, "package.json"), "utf8"));
  if (manifest.publisher?.toLowerCase() !== "openai" || manifest.name !== "chatgpt") {
    throw new Error("Expected the official OpenAI.chatgpt extension");
  }
  const adapters = JSON.parse(await readFile(path.join(repository, "frontend-host/adapters.json"), "utf8"));
  const binding = adapters.versions[manifest.version];
  if (!binding) throw new Error(`No verified host binding for plugin ${manifest.version}`);
  const actual = createHash("sha256").update(await readFile(path.join(source, "webview", binding.bundle))).digest("hex");
  if (actual !== binding.sha256) throw new Error("Official RPC bundle hash does not match the verified binding");
  const destination = path.resolve(options.output || path.join(repository, ".data/official-frontends", manifest.version));
  try {
    await stat(destination);
    throw new Error(`Output already exists: ${destination}. Choose a new --output directory.`);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  await mkdir(path.dirname(destination), { recursive: true });
  stage = await mkdtemp(path.join(path.dirname(destination), ".frontend-stage-"));
  await cp(path.join(source, "webview"), path.join(stage, "webview"), { recursive: true });
  await cp(path.join(repository, "frontend-host"), path.join(stage, "host"), { recursive: true });
  const html = await readFile(path.join(source, "webview/index.html"), "utf8");
  const injected = `<meta name="codex-extension-version" content="${manifest.version}">\n<meta name="codex-session-id" content="${randomUUID()}">\n<meta name="codex-build-flavor" content="prod">\n<link rel="stylesheet" href="../host/theme.css">\n<script src="../host/shim.js"></script>\n<script type="module" src="../host/host.mjs"></script>\n`;
  const csp = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; media-src 'self' blob: data:; object-src 'none'; frame-src 'self' blob:";
  await writeFile(path.join(stage, "index.html"), html
    .replace("<!-- PROD_BASE_TAG_HERE -->", '<base href="./webview/">')
    .replace("<!-- PROD_CSP_TAG_HERE -->", `<meta http-equiv="Content-Security-Policy" content="${csp}">`)
    .replace(/(<script type="module")/, `${injected}$1`)
    .replace("<body ", '<body class="vscode-dark" '));
  let bytes = 0;
  let files = 0;
  async function count(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const item = path.join(directory, entry.name);
      if (entry.isDirectory()) await count(item);
      else if (entry.isFile()) { bytes += (await stat(item)).size; files += 1; }
      else throw new Error("Frontend resources must contain regular files only");
    }
  }
  await count(path.join(stage, "webview"));
  const metadata = { pluginId: "OpenAI.chatgpt", version: manifest.version, binding, bytes, files, entrypoint: "index.html" };
  await writeFile(path.join(stage, "metadata.json"), JSON.stringify(metadata, null, 2) + "\n");
  await rename(stage, destination);
  stage = undefined;
  console.log(JSON.stringify({ ...metadata, directory: destination }, null, 2));
} finally {
  if (stage) await rm(stage, { recursive: true, force: true });
  if (temporary) await rm(temporary, { recursive: true, force: true });
}
