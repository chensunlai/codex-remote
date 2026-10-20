import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { access, cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import { pipeline } from "node:stream/promises";
import yauzl from "yauzl";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(import.meta.url);
const output = resolve(root, process.argv[2] ?? "release/windows");
const stage = join(output, "CodexRemote");
const nodeVersion = "24.18.0";
const archiveName = `node-v${nodeVersion}-win-x64.zip`;
const expectedNodeSha256 = "0ae68406b42d7725661da979b1403ec9926da205c6770827f33aac9d8f26e821";
const { version } = JSON.parse(await readFile(join(root, "package.json"), "utf8"));
const dotnet = process.env.CODEX_REMOTE_DOTNET || "dotnet";

await rm(stage, { recursive: true, force: true });
await mkdir(join(stage, "runtime"), { recursive: true });
await mkdir(join(stage, "app"));
await run(dotnet, ["publish", "windows/CodexRemote.Windows.csproj", "-c", "Release", "-r", "win-x64",
  "--self-contained", "true", `-p:Version=${version}`, "-o", stage]);
await run(require.resolve("bun/bin/bun.exe"), ["build", "agent/src/index.ts", "--target=node", "--format=esm",
  "--packages=bundle", "--outfile", join(stage, "app", "agent.mjs")]);
await cp(join(root, "agent/src/extension-host"), join(stage, "runtime/extension-host"), {
  recursive: true, filter: source => !source.endsWith(".test.cjs"),
});

const cache = join(root, ".data/node-runtime-downloads", archiveName);
await mkdir(dirname(cache), { recursive: true });
let bytes = await readFile(cache).catch(() => null);
if (!bytes || digest(bytes) !== expectedNodeSha256) {
  const response = await fetch(`https://nodejs.org/dist/v${nodeVersion}/${archiveName}`, { signal: AbortSignal.timeout(300_000) });
  if (!response.ok) throw new Error(`Node download failed: HTTP ${response.status}`);
  bytes = Buffer.from(await response.arrayBuffer());
  if (digest(bytes) !== expectedNodeSha256) throw new Error("Windows Node runtime checksum mismatch");
  await writeFile(cache, bytes);
}
await extractRuntime(cache);
await writeFile(join(stage, "runtime/version.json"), JSON.stringify({ node: nodeVersion }) + "\n");
await cp(join(root, "docs/windows.md"), join(stage, "README.md"));
const zip = join(output, "codex-remote-windows-x64.zip");
await rm(zip, { force: true });
if (process.platform === "win32") {
  await run("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command",
    "Compress-Archive -LiteralPath $env:CR_PACKAGE_STAGE -DestinationPath $env:CR_PACKAGE_ZIP -CompressionLevel Optimal"],
  { CR_PACKAGE_STAGE: stage, CR_PACKAGE_ZIP: zip });
} else {
  await run("python3", ["-c", "import pathlib,sys,zipfile\np=pathlib.Path(sys.argv[1])\nwith zipfile.ZipFile(sys.argv[2],'w',zipfile.ZIP_DEFLATED) as z:\n for f in sorted(p.rglob('*')):\n  if f.is_file(): z.write(f,f.relative_to(p.parent))", stage, zip]);
}
const iscc = process.env.CODEX_REMOTE_ISCC || join(process.env["ProgramFiles(x86)"] || "C:/Program Files (x86)", "Inno Setup 6/ISCC.exe");
await run(iscc, [`/DAppVersion=${version}`, `/DStageDir=${stage}`, `/DOutputDir=${output}`, "windows/installer.iss"]);
const installer = join(output, "codex-remote-windows-x64-setup.exe");
await writeFile(join(output, "SHA256SUMS"),
  `${digest(await readFile(zip))}  codex-remote-windows-x64.zip\n` +
  `${digest(await readFile(installer))}  codex-remote-windows-x64-setup.exe\n`);
console.log(zip);
console.log(installer);

function digest(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function run(command, args, extraEnv = {}) {
  return new Promise((done, reject) => {
    const child = spawn(command, args, { cwd: root, stdio: "inherit", env: { ...process.env, ...extraEnv }, windowsHide: true });
    child.once("error", reject);
    child.once("exit", code => code === 0 ? done() : reject(new Error(`${command} exited ${code}`)));
  });
}
async function extractRuntime(archive) {
  const wanted = new Map([[`node-v${nodeVersion}-win-x64/node.exe`, "node.exe"], [`node-v${nodeVersion}-win-x64/LICENSE`, "NODE-LICENSE"]]);
  await new Promise((done, reject) => yauzl.open(archive, { lazyEntries: true }, (error, zip) => {
    if (error) { reject(error); return; }
    zip.on("error", reject);
    zip.on("end", () => wanted.size === 0 ? done() : reject(new Error("Incomplete Windows Node archive")));
    zip.on("entry", entry => {
      const name = wanted.get(entry.fileName);
      if (!name) { zip.readEntry(); return; }
      zip.openReadStream(entry, (error, stream) => {
        if (error) { zip.close(); reject(error); return; }
        pipeline(stream, createWriteStream(join(stage, "runtime", name))).then(() => {
          wanted.delete(entry.fileName); zip.readEntry();
        }, error => { zip.close(); reject(error); });
      });
    });
    zip.readEntry();
  }));
  await access(join(stage, "runtime/node.exe"));
}
