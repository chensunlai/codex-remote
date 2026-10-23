import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => { vi.doUnmock("node:os"); vi.doUnmock("node:child_process"); vi.doUnmock("node:fs"); vi.unstubAllEnvs(); vi.resetModules(); });

describe("native terminal launch", () => {
  it.each(["linux", "darwin"])("uses the native script command on %s with a safely quoted shell path", async platform => {
    vi.resetModules();
    vi.doMock("node:os", () => ({ platform: () => platform }));
    vi.doMock("node:fs", () => ({ existsSync: () => true }));
    const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: vi.fn() });
    const spawn = vi.fn(() => child);
    vi.doMock("node:child_process", () => ({ spawn }));
    vi.stubEnv("SHELL", "/Users/测试 dir/it's shell");
    const { TerminalService } = await import("./terminal-service.js");
    const terminal = new TerminalService({ data: vi.fn(), exit: vi.fn() });
    terminal.open(100, 40, "/Users/测试 dir");
    const [binary, args, options] = spawn.mock.calls[0] as unknown as [string, string[], { cwd: string; env: NodeJS.ProcessEnv }];
    expect(binary).toBe(platform === "darwin" ? "/bin/bash" : "/usr/bin/script");
    if (platform === "darwin") expect(args.slice(0, 8)).toEqual(["-c", 'exec "$@" < <(cat)', "codex-remote-terminal", "/usr/bin/script", "-q", "/dev/null", "/bin/sh", "-c"]);
    else { expect(args[0]).toBe("-qfec"); expect(args.at(-1)).toBe("/dev/null"); }
    const command = platform === "darwin" ? args[8] : args[1];
    expect(command).toContain("stty cols 100 rows 40");
    expect(command).toContain("exec '/Users/测试 dir/it'\"'\"'s shell' -l");
    expect(options.cwd).toBe("/Users/测试 dir");
    expect(options.env.TERM).toBe("xterm-256color");
    terminal.closeAll();
  });

  it.skipIf(process.platform === "win32")("starts a real terminal and preserves UTF-8 output", async () => {
    vi.stubEnv("SHELL", "/bin/sh");
    const { TerminalService } = await import("./terminal-service.js");
    let output = "";
    const terminal = new TerminalService({ data: (_id, text) => { output += text; }, exit: () => {} });
    try {
      const { terminalId } = terminal.open(80, 24);
      // BSD script/login-shell setup can flush input while initializing the PTY.
      // Send only once the interactive shell is ready, as a terminal user does.
      await vi.waitFor(() => expect(output).toMatch(/[$#] $/), { timeout: 10000 });
      terminal.write(terminalId, "printf '\\344\\270\\255\\346\\226\\207\\360\\237\\231\\202'; exit\n");
      await vi.waitFor(() => expect(output).toContain("中文🙂"), { timeout: 5000 });
    } finally { terminal.closeAll(); }
  }, 20000);
});
