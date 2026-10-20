import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { resolve } from "node:path";

assert.equal(process.platform, "win32", "Windows tests require a real Windows host");
const require = createRequire(import.meta.url);
const supervisor = process.env.CODEX_REMOTE_WINDOWS_SUPERVISOR;
assert.ok(supervisor, "Set CODEX_REMOTE_WINDOWS_SUPERVISOR to the packaged tray EXE");
await run(supervisor, ["--self-test"]);
await run(process.execPath, ["scripts/smoke-packaged-agent.mjs"]);
await run(process.execPath, [require.resolve("vitest/vitest.mjs"), "run",
  "agent/src/windows-platform.test.ts", "agent/src/windows-host-supervisor.test.ts",
  "agent/src/config.test.ts", "agent/src/protocol-client.test.ts",
  "server/src/remote-path.test.ts"]);

function run(executable, args) {
  return new Promise((done, reject) => {
    const child = spawn(executable, args, { cwd: resolve(import.meta.dirname, ".."), stdio: "inherit", windowsHide: true });
    child.once("error", reject);
    child.once("exit", code => code === 0 ? done() : reject(new Error(`${executable} exited ${code}`)));
  });
}
