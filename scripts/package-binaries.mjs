import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { access, chmod, mkdir, stat, writeFile } from "node:fs/promises";
import { constants } from "node:fs";
import { basename, resolve } from "node:path";
import { spawn } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const targets = {
  "linux-x64": "bun-linux-x64-baseline",
  "linux-arm64": "bun-linux-arm64",
};
const components = {
  agent: "agent/src/index.ts",
  gateway: "server/src/cli.ts",
};
const maxBinaryBytes = 100_000_000;

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
  }
}

const checksums = [];
for (const output of outputs) {
  checksums.push(`${await sha256(output)}  ${basename(output)}`);
}
await writeFile(resolve(outputDirectory, "SHA256SUMS"), `${checksums.join("\n")}\n`, {
  mode: 0o644,
});

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
