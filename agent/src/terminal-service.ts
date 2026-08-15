import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { platform } from "node:os";

interface TerminalEntry {
  process: ChildProcessWithoutNullStreams;
  cols: number;
  rows: number;
}

interface TerminalCallbacks {
  data: (terminalId: string, data: string) => void;
  exit: (terminalId: string, exitCode: number) => void;
}

export class TerminalService {
  private readonly terminals = new Map<string, TerminalEntry>();

  constructor(private readonly callbacks: TerminalCallbacks) {}

  open(cols: number, rows: number, cwd?: string): { terminalId: string } {
    const terminalId = randomUUID();
    const child = spawnTerminal(cols, rows, cwd);
    this.terminals.set(terminalId, { process: child, cols, rows });
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (data: string) => this.callbacks.data(terminalId, data));
    child.stderr.on("data", (data: string) => this.callbacks.data(terminalId, data));
    child.on("error", (error) => this.callbacks.data(terminalId, `\r\n${error.message}\r\n`));
    child.on("exit", (code) => {
      this.terminals.delete(terminalId);
      this.callbacks.exit(terminalId, code ?? 0);
    });
    return { terminalId };
  }

  write(terminalId: string, data: string): void {
    const terminal = this.terminals.get(terminalId);
    if (!terminal) throw new Error("终端会话不存在");
    terminal.process.stdin.write(data);
  }

  resize(terminalId: string, cols: number, rows: number): void {
    const terminal = this.terminals.get(terminalId);
    if (!terminal) return;
    terminal.cols = cols;
    terminal.rows = rows;
    if (terminal.process.pid) {
      try {
        process.kill(terminal.process.pid, "SIGWINCH");
      } catch {
        // The process may have exited between lookup and signal.
      }
    }
  }

  close(terminalId: string): void {
    const terminal = this.terminals.get(terminalId);
    if (!terminal) return;
    this.terminals.delete(terminalId);
    terminal.process.kill("SIGTERM");
  }

  closeAll(): void {
    for (const terminalId of this.terminals.keys()) this.close(terminalId);
  }
}

function spawnTerminal(
  cols: number,
  rows: number,
  cwd?: string,
): ChildProcessWithoutNullStreams {
  const shell = process.env.SHELL || (platform() === "win32" ? "powershell.exe" : "/bin/bash");
  if (platform() !== "win32") {
    const script = ["/usr/bin/script", "/bin/script"].find(existsSync);
    if (script) {
      const command = `stty cols ${cols} rows ${rows} 2>/dev/null; exec ${shellQuote(shell)} -l`;
      return spawn(script, ["-qfec", command, "/dev/null"], {
        cwd,
        env: {
          ...process.env,
          TERM: "xterm-256color",
          COLORTERM: "truecolor",
          COLUMNS: String(cols),
          LINES: String(rows),
        },
        stdio: ["pipe", "pipe", "pipe"],
      });
    }
  }
  return spawn(shell, platform() === "win32" ? [] : ["-l"], {
    cwd,
    env: {
      ...process.env,
      TERM: "xterm-256color",
      COLUMNS: String(cols),
      LINES: String(rows),
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'"'"'`)}'`;
}
