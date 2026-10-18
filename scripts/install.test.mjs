import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { promisify } from "node:util";
import { afterEach, test } from "node:test";
import assert from "node:assert/strict";

const execute = promisify(execFile);
const temporaryDirectories = [];
afterEach(async () => { for (const path of temporaryDirectories.splice(0)) await rm(path, { recursive: true, force: true }); });

async function fixture({ runtime = true } = {}) {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-installer-test-"));
  temporaryDirectories.push(directory);
  const release = join(directory, "release"), install = join(directory, "install");
  await mkdir(release);
  await mkdir(install);
  const platform = `linux-${process.arch}`;
  const binary = `codex-remote-agent-${platform}`;
  await writeFile(join(release, binary), "#!/bin/sh\nprintf '%s\\n' 'fixture-agent'\n");
  const assets = [binary];
  if (runtime) {
    const staging = join(directory, "staging");
    await mkdir(join(staging, "runtime", "extension-host"), { recursive: true });
    // Installer smoke fixture; the runtime resolver separately validates Node versions.
    await writeFile(join(staging, "runtime", "node"), "#!/bin/sh\nprintf '%s\\n' 'v24.18.0'\n");
    await chmod(join(staging, "runtime", "node"), 0o755);
    await writeFile(join(staging, "runtime", "extension-host", "runtime.cjs"), "module.exports = {};\n");
    assets.push(`codex-remote-agent-runtime-${platform}.tar.gz`);
    await execute("tar", ["-czf", join(release, assets[1]), "-C", staging, "runtime"]);
  }
  const checksums = await Promise.all(assets.map(async (asset) => `${createHash("sha256").update(await readFile(join(release, asset))).digest("hex")}  ${asset}`));
  await writeFile(join(release, "SHA256SUMS"), `${checksums.join("\n")}\n`);
  const run = () => execute("bash", [resolve("install.sh"), "agent", "--from", release, "--install-dir", install], { env: process.env });
  return { directory, release, install, binary, run };
}

test("Agent installer verifies and installs the matching runtime without launching the Agent", { skip: process.platform !== "linux" }, async () => {
  const { install, run } = await fixture();
  const result = await run();
  assert.match(result.stdout, /Node.js/);
  assert.match(await readFile(join(install, "codex-remote-agent"), "utf8"), /fixture-agent/);
  assert.equal((await execute(join(install, "runtime", "node"), ["--version"])).stdout.trim(), "v24.18.0");
  assert.equal(await readFile(join(install, "runtime", "extension-host", "runtime.cjs"), "utf8"), "module.exports = {};\n");
  await run();
  assert.match(await readFile(join(install, "runtime.previous", "node"), "utf8"), /v24.18.0/);
});

test("Agent installer defaults to the user's ~/.local/codex-remote rather than the current directory", { skip: process.platform !== "linux" }, async () => {
  const { directory, release } = await fixture();
  const userDirectory = join(directory, "user home");
  await execute("bash", [resolve("install.sh"), "agent", "--from", release], {
    cwd: directory,
    env: { ...process.env, HOME: userDirectory, CODEX_REMOTE_INSTALL_DIR: "" },
  });
  const installedDirectory = join(userDirectory, ".local", "codex-remote");
  assert.match(await readFile(join(installedDirectory, "codex-remote-agent"), "utf8"), /fixture-agent/);
  assert.equal((await execute(join(installedDirectory, "runtime", "node"), ["--version"])).stdout.trim(), "v24.18.0");
  await assert.rejects(readFile(join(directory, "codex-remote-agent")), { code: "ENOENT" });
});

test("runtime checksum failure leaves the existing Agent and runtime untouched", { skip: process.platform !== "linux" }, async () => {
  const { install, release, run } = await fixture();
  await run();
  await writeFile(join(install, "codex-remote-agent"), "previous-agent");
  await writeFile(join(install, "runtime", "node"), "previous-runtime");
  await writeFile(join(release, `codex-remote-agent-runtime-linux-${process.arch}.tar.gz`), "corrupt");
  await assert.rejects(run, (error) => /SHA-256/.test(error.stderr));
  assert.equal(await readFile(join(install, "codex-remote-agent"), "utf8"), "previous-agent");
  assert.equal(await readFile(join(install, "runtime", "node"), "utf8"), "previous-runtime");
});

test("older release manifests remain installable without a runtime asset", { skip: process.platform !== "linux" }, async () => {
  const { install, run } = await fixture({ runtime: false });
  await run();
  assert.match(await readFile(join(install, "codex-remote-agent"), "utf8"), /fixture-agent/);
});
