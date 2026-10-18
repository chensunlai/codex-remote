import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
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

async function maintenanceFixture({ corrupt = false, unhealthy = false, repository = "chensunlai/codex-remote", currentVersion = "2.0.1", interrupt = false } = {}) {
  const directory = await mkdtemp(join(tmpdir(), "gateway-maintenance-test-"));
  temporaryDirectories.push(directory);
  const install = join(directory, "install"), control = join(directory, "control"), mocks = join(directory, "mocks");
  await Promise.all([mkdir(install), mkdir(join(control, "requests"), { recursive: true }), mkdir(mocks)]);
  const definitions = join(directory, "installer-definitions.sh");
  const installer = await readFile(resolve("install.sh"), "utf8");
  await writeFile(definitions, installer.replace(/\nmain "\$@"\s*$/, "\n"));
  const generated = await execute("bash", ["-c", 'source "$1"; INSTALL_DIRECTORY="$2"; REPOSITORY="$3"; write_gateway_maintenance_helper', "bash", definitions, install, repository]);
  const helper = join(directory, "maintenance.sh");
  // Only test copies override the production fixed control directory and tool PATH.
  const source = generated.stdout
    .replace("CONTROL_DIRECTORY=/var/lib/codex-remote-control", `CONTROL_DIRECTORY='${control}'`)
    .replace("PATH=/usr/sbin:/usr/bin:/sbin:/bin", `PATH='${mocks}:/usr/bin:/bin'`);
  await writeFile(helper, source);
  await execute("bash", ["-n", helper]);
  const binary = join(install, "codex-remote-gateway");
  await writeFile(binary, `version:${currentVersion}\n`);
  const next = "version:2.1.0\n";
  const checksum = createHash("sha256").update(next).digest("hex");
  const asset = `codex-remote-gateway-linux-${process.arch}`;
  await writeFile(join(mocks, "sleep"), "#!/bin/sh\nexit 0\n");
  await writeFile(join(mocks, "systemctl"), `#!/bin/sh
printf '%s\\n' "$*" >> "$TEST_CONTROL/systemctl.log"
case "$1" in
  show) printf '1234\\n' ;;
  restart) if [ "$TEST_INTERRUPT" = true ] && grep -q 'version:2.1.0' "$TEST_BINARY"; then kill -TERM "$PPID"; fi ;;
esac
exit 0
`);
  await writeFile(join(mocks, "curl"), `#!${process.execPath}
const fs = require('node:fs');
const args = process.argv.slice(2), url = args.at(-1), output = args[args.indexOf('--output') + 1];
fs.appendFileSync(process.env.TEST_CONTROL + '/curl.log', url + '\\n');
if (url.endsWith('/healthz')) {
  const value = fs.readFileSync(process.env.TEST_BINARY, 'utf8').trim().replace('version:', '');
  process.stdout.write(JSON.stringify({status:'ok', version:process.env.TEST_UNHEALTHY === 'true' && value === '2.1.0' ? 'wrong' : value}));
} else if (url.endsWith('/releases/latest')) {
  process.stdout.write('https://github.com/chensunlai/codex-remote/releases/tag/v2.1.0');
} else if (url.endsWith('/SHA256SUMS')) {
  fs.writeFileSync(output, ${JSON.stringify(`${checksum}  ${asset}\n`)});
} else if (url.endsWith('/${asset}')) {
  fs.writeFileSync(output, process.env.TEST_CORRUPT === 'true' ? 'corrupt' : ${JSON.stringify(next)});
} else { process.exit(42); }
`);
  await Promise.all(["sleep", "systemctl", "curl"].map((name) => chmod(join(mocks, name), 0o755)));
  const id = "10101010-2020-3030-4040-505050505050";
  const enqueue = (action) => writeFile(join(control, "requests", `${action}-${id}`), "ignored request contents");
  const run = () => execute("bash", [helper], { env: { ...process.env, TEST_CONTROL: control, TEST_BINARY: binary, TEST_CORRUPT: String(corrupt), TEST_UNHEALTHY: String(unhealthy), TEST_INTERRUPT: String(interrupt), HOST: "127.0.0.1", PORT: "6767" } });
  const status = async () => JSON.parse(await readFile(join(control, "operation.json"), "utf8"));
  return { directory, install, control, binary, helper, enqueue, run, status };
}

test("generated Gateway maintenance helper restarts and verifies the service", async () => {
  const f = await maintenanceFixture();
  await f.enqueue("restart");
  await f.run();
  assert.equal((await f.status()).state, "succeeded");
  assert.match(await readFile(join(f.control, "systemctl.log"), "utf8"), /^restart codex-remote-gateway\.service\n/);
  assert.deepEqual(await readdir(join(f.control, "requests")), []);
});

test("Gateway updater verifies the release checksum and the running version", async () => {
  const f = await maintenanceFixture();
  await f.enqueue("update");
  await f.run();
  assert.equal((await f.status()).state, "succeeded");
  assert.equal(await readFile(f.binary, "utf8"), "version:2.1.0\n");
  assert.equal((await readFile(join(f.control, "systemctl.log"), "utf8")).match(/^restart /gm).length, 1);
  assert.ok(!(await readdir(f.install)).some((name) => name.startsWith(".gateway-maintenance.")));
});

test("checksum mismatch never replaces or restarts Gateway", async () => {
  const f = await maintenanceFixture({ corrupt: true });
  await f.enqueue("update");
  await f.run();
  assert.equal((await f.status()).state, "failed");
  assert.equal(await readFile(f.binary, "utf8"), "version:2.0.1\n");
  await assert.rejects(readFile(join(f.control, "systemctl.log")), { code: "ENOENT" });
});

test("incorrect running version rolls back and restarts the previous binary", async () => {
  const f = await maintenanceFixture({ unhealthy: true });
  await f.enqueue("update");
  await f.run();
  assert.match((await f.status()).error, /回滚/);
  assert.equal(await readFile(f.binary, "utf8"), "version:2.0.1\n");
  assert.equal((await readFile(join(f.control, "systemctl.log"), "utf8")).match(/^restart /gm).length, 2);
});

test("interrupted update restores the previous binary before cleanup", async () => {
  const f = await maintenanceFixture({ interrupt: true });
  await f.enqueue("update");
  await assert.rejects(f.run);
  assert.match((await f.status()).error, /中断.*回滚/);
  assert.equal(await readFile(f.binary, "utf8"), "version:2.0.1\n");
});

test("Gateway updates cannot downgrade a newer installed version or overwrite a custom fork", async () => {
  const newer = await maintenanceFixture({ currentVersion: "3.0.0" });
  await newer.enqueue("update");
  await newer.run();
  assert.equal(await readFile(newer.binary, "utf8"), "version:3.0.0\n");
  assert.match((await newer.status()).output, /未降级/);
  const fork = await maintenanceFixture({ repository: "owner/fork" });
  await fork.enqueue("update");
  await fork.run();
  assert.equal((await fork.status()).state, "failed");
  await assert.rejects(readFile(join(fork.control, "curl.log")), { code: "ENOENT" });
});

test("maintenance ignores request contents, removes invalid directories, and never follows symlinks", async () => {
  const f = await maintenanceFixture();
  const outside = join(f.directory, "outside");
  await writeFile(outside, "keep");
  await symlink(outside, join(f.control, "requests", "update-10101010-2020-3030-4040-505050505050"));
  await mkdir(join(f.control, "requests", "malformed"));
  await writeFile(join(f.control, "requests", "malformed", "child"), "keep");
  await writeFile(join(f.control, "requests", "arbitrary-command"), "rm -rf /");
  await writeFile(join(f.control, "requests", ".hidden"), "ignored");
  await f.run();
  assert.equal(await readFile(outside, "utf8"), "keep");
  assert.deepEqual(await readdir(join(f.control, "requests")), []);
  await assert.rejects(readFile(join(f.control, "systemctl.log")), { code: "ENOENT" });
});

test("Gateway updater rejects unidentifiable running versions and does not downgrade prereleases", async () => {
  const unknown = await maintenanceFixture({ currentVersion: "unknown" });
  await unknown.enqueue("update");
  await unknown.run();
  assert.equal((await unknown.status()).state, "failed");
  assert.equal(await readFile(unknown.binary, "utf8"), "version:unknown\n");
  const prerelease = await maintenanceFixture({ currentVersion: "3.0.0-beta.1" });
  await prerelease.enqueue("update");
  await prerelease.run();
  assert.equal(await readFile(prerelease.binary, "utf8"), "version:3.0.0-beta.1\n");
  assert.match((await prerelease.status()).output, /未降级/);
});
