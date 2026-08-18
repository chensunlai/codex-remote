import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { CodexRuntime } from "./codex-runtime.js";

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
});
