import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { access, chmod, cp, mkdir, mkdtemp, rm, stat, writeFile } from "node:fs/promises";
import { constants } from "node:fs";
import { basename, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn } from "node:child_process";
import http from "node:http";

// Respect the same HTTPS_PROXY/NO_PROXY settings used by the installer.
http.setGlobalProxyFromEnv?.();

const root = resolve(import.meta.dirname, "..");
const targets = {
  "linux-x64": "bun-linux-x64-baseline",
  "linux-arm64": "bun-linux-arm64",
};
const components = {
  agent: "agent/src/index.ts",
  gateway: "scripts/gateway-standalone.mjs",
};
const maxBinaryBytes = 100_000_000;
// Pin both official Node archives and digests; installing the Agent needs no system Node.
const nodeVersion = "24.18.0";
const nodeArchives = {
  "linux-x64": "783130984963db7ba9cbd01089eaf2c2efb055c7c1693c943174b967b3050cb8",
  "linux-arm64": "6b4484c2190274175df9aa8f28e2d758a819cb1c1fe6ab481e2f95b463ab8508",
};

const options = parseArgs(process.argv.slice(2));
const outputDirectory = resolve(root, options.outputDirectory);
const bun = process.env.BUN_BIN
  ? resolve(process.env.BUN_BIN)
  : resolve(root, "node_modules/.bin", process.platform === "win32" ? "bun.cmd" : "bun");

await access(bun, constants.X_OK).catch(() => {
  throw new Error("未找到固定版本的 Bun 编译器，请先运行 npm ci");
});
await mkdir(outputDirectory, { recursive: true });

const outputs = [];
for (const targetName of options.targets) {
  const bunTarget = targets[targetName];
  for (const componentName of options.components) {
    const output = resolve(
      outputDirectory,
      `codex-remote-${componentName}-${targetName}`,
    );
    await run(bun, [
      "build",
      resolve(root, components[componentName]),
      "--compile",
      `--target=${bunTarget}`,
      "--minify",
      "--no-compile-autoload-dotenv",
      "--no-compile-autoload-bunfig",
      "--outfile",
      output,
    ]);
    await chmod(output, 0o755);
    const metadata = await stat(output);
    if (metadata.size > maxBinaryBytes) {
      throw new Error(
        `${output} 为 ${metadata.size} bytes，超过单文件 100MB 限制`,
      );
    }
    outputs.push(output);
    process.stdout.write(`${output} (${metadata.size} bytes)\n`);
    if (componentName === "agent") {
      const runtime = await packageAgentRuntime(targetName);
      outputs.push(runtime);
      process.stdout.write(`${runtime} (${(await stat(runtime)).size} bytes)\n`);
    }
  }
}

const checksums = [];
for (const output of outputs) {
  checksums.push(`${await sha256(output)}  ${basename(output)}`);
}
await writeFile(resolve(outputDirectory, "SHA256SUMS"), `${checksums.join("\n")}\n`, {
  mode: 0o644,
});

async function packageAgentRuntime(targetName) {
  const archiveName = `node-v${nodeVersion}-${targetName}.tar.gz`;
  const cacheDirectory = resolve(root, ".data", "node-runtime-downloads");
  const archive = resolve(cacheDirectory, archiveName);
  await mkdir(cacheDirectory, { recursive: true });
  const expected = nodeArchives[targetName];
  const cached = await sha256(archive).catch(() => null);
  if (cached !== expected) {
    process.stdout.write(`Downloading verified Node.js ${nodeVersion} runtime (${targetName})...\n`);
    const response = await fetch(`https://nodejs.org/dist/v${nodeVersion}/${archiveName}`, {
      signal: AbortSignal.timeout(5 * 60_000),
    });
    if (!response.ok) throw new Error(`Node runtime download failed: HTTP ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length > 150_000_000 || createHash("sha256").update(bytes).digest("hex") !== expected) {
      throw new Error(`Node runtime checksum mismatch: ${archiveName}`);
    }
    await writeFile(archive, bytes, { mode: 0o600 });
  }
  const temporary = await mkdtemp(join(tmpdir(), "codex-remote-runtime-"));
  try {
    const nodeDirectory = `node-v${nodeVersion}-${targetName}`;
    await run("tar", ["-xzf", archive, "-C", temporary, `${nodeDirectory}/bin/node`, `${nodeDirectory}/LICENSE`]);
    const runtime = join(temporary, "runtime");
    await mkdir(runtime);
    await cp(join(temporary, nodeDirectory, "bin", "node"), join(runtime, "node"));
    await chmod(join(runtime, "node"), 0o755);
    await cp(join(temporary, nodeDirectory, "LICENSE"), join(runtime, "NODE-LICENSE"));
    const hostDirectory = resolve(root, "agent/src/extension-host");
    await access(join(hostDirectory, "runtime.cjs"));
    await cp(hostDirectory, join(runtime, "extension-host"), { recursive: true, filter: (source) => !source.endsWith(".test.cjs") });
    await writeFile(join(runtime, "version.json"), `${JSON.stringify({ node: nodeVersion })}\n`);
    const output = resolve(outputDirectory, `codex-remote-agent-runtime-${targetName}.tar.gz`);
    await run("tar", ["-czf", output, "-C", temporary, "runtime"]);
    return output;
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

function parseArgs(args) {
  let requestedTargets;
  let requestedComponents;
  let outputDirectory = "release";
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--targets" && args[index + 1]) {
      requestedTargets = args[++index];
    } else if (argument === "--components" && args[index + 1]) {
      requestedComponents = args[++index];
    } else if (argument === "--output" && args[index + 1]) {
      outputDirectory = args[++index];
    } else if (argument === "--help" || argument === "-h") {
      printUsage();
      process.exit(0);
    } else {
      throw new Error(`未知参数: ${argument}`);
    }
  }

  const selectedTargets = list(requestedTargets ?? currentTarget());
  const selectedComponents = list(requestedComponents ?? Object.keys(components).join(","));
  validate(selectedTargets, targets, "目标");
  validate(selectedComponents, components, "组件");
  return { targets: selectedTargets, components: selectedComponents, outputDirectory };
}

function currentTarget() {
  if (process.platform !== "linux") {
    throw new Error("standalone release 当前仅支持 Linux；请显式传入 --targets");
  }
  if (process.arch === "x64") return "linux-x64";
  if (process.arch === "arm64") return "linux-arm64";
  throw new Error(`不支持当前架构: ${process.arch}`);
}

function list(value) {
  return [...new Set(value.split(",").map((item) => item.trim()).filter(Boolean))];
}

function validate(values, supported, label) {
  for (const value of values) {
    if (!(value in supported)) {
      throw new Error(`不支持的${label}: ${value}；可选值: ${Object.keys(supported).join(", ")}`);
    }
  }
}

function run(command, args) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { cwd: root, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0) resolvePromise();
      else reject(new Error(`${command} 退出: code=${String(code)}, signal=${String(signal)}`));
    });
  });
}

async function sha256(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

function printUsage() {
  process.stdout.write(
    [
      "node scripts/package-binaries.mjs [options]",
      "",
      "  --targets linux-x64,linux-arm64  目标平台，默认当前 Linux 平台",
      "  --components agent,gateway       构建组件，默认全部",
      "  --output <directory>              输出目录，默认 release",
      "",
    ].join("\n"),
  );
}
