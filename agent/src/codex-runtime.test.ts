import { spawn } from "node:child_process";
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { CodexRuntime, stopThreadLockOwner } from "./codex-runtime.js";

describe("CodexRuntime takeover", () => {
  it("unsubscribes without requiring the managed daemon before resuming", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-runtime-test-"));
    const executable = join(directory, "fake-codex.mjs");
    const log = join(directory, "commands.log");
    const previousLog = process.env.CODEX_RUNTIME_TEST_LOG;
    await writeFile(executable, `#!/usr/bin/env node
import { appendFileSync } from "node:fs";
import { createInterface } from "node:readline";
const args = process.argv.slice(2);
appendFileSync(process.env.CODEX_RUNTIME_TEST_LOG, args.join(" ") + "\\n");
if (args.join(" ") !== "app-server proxy") process.exit(0);
createInterface({ input: process.stdin }).on("line", (line) => {
  const request = JSON.parse(line);
  if (request.id === undefined) return;
  const result = request.method === "thread/resume"
    ? { thread: { id: request.params.threadId } }
    : request.method === "thread/unsubscribe"
      ? { status: "unsubscribed" }
      : {};
  process.stdout.write(JSON.stringify({ id: request.id, result }) + "\\n");
});
`, "utf8");
    await chmod(executable, 0o755);
    process.env.CODEX_RUNTIME_TEST_LOG = log;
    const runtime = new CodexRuntime(executable, {
      notification: () => {},
      request: () => {},
      stderr: () => {},
      closed: () => {},
    });

    try {
      await expect(runtime.takeoverThread("thread-occupied")).resolves.toEqual({
        thread: { id: "thread-occupied" },
      });
      expect((await readFile(log, "utf8")).trim().split("\n")).toEqual([
        "app-server daemon bootstrap --remote-control",
        "app-server proxy",
        "app-server daemon bootstrap --remote-control",
        "app-server proxy",
      ]);
    } finally {
      runtime.close();
      if (previousLog === undefined) delete process.env.CODEX_RUNTIME_TEST_LOG;
      else process.env.CODEX_RUNTIME_TEST_LOG = previousLog;
      await rm(directory, { recursive: true, force: true });
    }
  });

  it.skipIf(process.platform !== "linux")(
    "stops the process holding the requested thread lock",
    async () => {
      const directory = await mkdtemp(join(tmpdir(), "codex-lock-owner-test-"));
      const lockDirectory = join(directory, "thread-writer-locks");
      const threadId = "019fff0d-1c52-7042-9de0-9cc0eecf4095";
      const lockPath = join(lockDirectory, `${threadId}.lock`);
      const readyPath = join(directory, "ready");
      const previousCodexHome = process.env.CODEX_HOME;
      await mkdir(lockDirectory, { recursive: true });
      const holder = spawn(
        process.execPath,
        [
          "-e",
          `const fs = require("node:fs");
const descriptor = fs.openSync(process.argv[1], "w");
fs.writeFileSync(process.argv[2], String(process.pid));
setInterval(() => fs.fstatSync(descriptor), 1_000);`,
          lockPath,
          readyPath,
          "codex",
        ],
        { stdio: "ignore" },
      );
      process.env.CODEX_HOME = directory;

      try {
        await eventually(async () => {
          try {
            return (await readFile(readyPath, "utf8")).trim() === String(holder.pid);
          } catch {
            return false;
          }
        });
        await stopThreadLockOwner(threadId);
        await eventually(async () => !processExists(holder.pid));
      } finally {
        if (holder.pid && processExists(holder.pid)) holder.kill("SIGKILL");
        if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
        else process.env.CODEX_HOME = previousCodexHome;
        await rm(directory, { recursive: true, force: true });
      }
    },
  );
});

async function eventually(
  predicate: () => Promise<boolean>,
  timeoutMs = 5_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await predicate())) {
    if (Date.now() >= deadline) throw new Error("condition was not met");
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

function processExists(pid: number | undefined): boolean {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code !== "ESRCH";
  }
}
