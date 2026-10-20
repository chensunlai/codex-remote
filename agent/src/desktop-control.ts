import { createInterface } from "node:readline";
import type { Readable, Writable } from "node:stream";

export type DesktopState = "connecting" | "connected" | "reconnecting" | "stopped" | "error";

/** Private stdio control channel used only by the Windows tray process. */
export class DesktopControl {
  readonly started: Promise<boolean>;
  private readonly input;
  private resolveStart!: (value: boolean) => void;
  private didStart = false;
  private stopping = false;

  constructor(onStop: () => void, private readonly stream: Readable = process.stdin, private readonly output: Writable = process.stdout) {
    this.started = new Promise(resolve => { this.resolveStart = resolve; });
    this.input = createInterface({ input: stream });
    const stop = (): void => {
      if (this.stopping) return;
      this.stopping = true;
      this.resolveStart(false);
      onStop();
    };
    this.input.on("line", line => {
      let type: unknown;
      try { type = (JSON.parse(line) as { type?: unknown }).type; } catch { return; }
      if (type === "start" && !this.didStart && !this.stopping) { this.didStart = true; this.resolveStart(true); }
      else if (type === "stop") stop();
    });
    this.input.on("close", stop);
    stream.on("error", stop);
  }

  status(state: DesktopState, message?: string): void {
    this.output.write(`${JSON.stringify({ type: "status", state, ...(message ? { message } : {}) })}\n`);
  }

  close(): void {
    this.stopping = true;
    this.resolveStart(false);
    this.input.close();
    this.stream.destroy();
  }
}

export function redactToken(message: string, token: string): string {
  return token ? message.split(token).join("[redacted]") : message;
}
