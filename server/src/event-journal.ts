import { EventEmitter } from "node:events";
import { appendFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import type { GatewayEvent } from "./domain.js";

export interface EventPage {
  events: Omit<GatewayEvent, "ownerId">[];
  latestSequence: number;
  truncated: boolean;
}

export class EventJournal extends EventEmitter {
  private events: GatewayEvent[] = [];
  private sequence = 0;
  private persistedEntries = 0;
  private writeQueue: Promise<void> = Promise.resolve();

  private constructor(
    private readonly path: string,
    private readonly maxEntries: number,
  ) {
    super();
  }

  static async open(path: string, maxEntries = 5000): Promise<EventJournal> {
    const journal = new EventJournal(path, maxEntries);
    await journal.load();
    return journal;
  }

  publish(ownerId: string, type: string, payload: unknown, serviceId?: string): GatewayEvent {
    const event: GatewayEvent = {
      sequence: ++this.sequence,
      timestamp: new Date().toISOString(),
      ownerId,
      ...(serviceId ? { serviceId } : {}),
      type,
      payload,
    };
    this.events.push(event);
    if (this.events.length > this.maxEntries) this.events.shift();
    const shouldCompact = ++this.persistedEntries >= this.maxEntries * 2;
    const compactSnapshot = shouldCompact ? structuredClone(this.events) : undefined;
    if (compactSnapshot) this.persistedEntries = compactSnapshot.length;
    this.writeQueue = this.writeQueue
      .then(async () => {
        await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
        await appendFile(this.path, `${JSON.stringify(event)}\n`, { mode: 0o600 });
        if (compactSnapshot) await this.compact(compactSnapshot);
      })
      .catch((error: unknown) => {
        this.emit("journalError", error);
      });
    this.emit("event", event);
    return event;
  }

  since(sequence: number, ownerId: string, serviceId?: string): EventPage {
    const visible = this.events.filter((event) => event.ownerId === ownerId);
    const oldest = visible[0]?.sequence ?? this.sequence;
    const events = visible
      .filter(
        (event) => event.sequence > sequence && (!serviceId || event.serviceId === serviceId),
      )
      .map(({ ownerId: _ownerId, ...event }) => structuredClone(event));
    return {
      events,
      latestSequence: this.sequence,
      truncated: sequence > 0 && sequence < oldest - 1,
    };
  }

  async flush(): Promise<void> {
    await this.writeQueue;
  }

  private async load(): Promise<void> {
    try {
      const content = await readFile(this.path, "utf8");
      for (const line of content.split("\n")) {
        if (!line.trim()) continue;
        this.persistedEntries += 1;
        try {
          const event = JSON.parse(line) as GatewayEvent;
          if (Number.isSafeInteger(event.sequence) && typeof event.ownerId === "string") {
            this.events.push(event);
          }
        } catch {
          // Ignore a partial final line left by an interrupted append.
        }
      }
      this.events = this.events.slice(-this.maxEntries);
      this.sequence = this.events.at(-1)?.sequence ?? 0;
      if (this.persistedEntries >= this.maxEntries * 2) {
        await this.compact(this.events);
        this.persistedEntries = this.events.length;
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  private async compact(events: GatewayEvent[]): Promise<void> {
    await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
    const temporary = `${this.path}.${process.pid}.tmp`;
    const content = events.map((event) => JSON.stringify(event)).join("\n");
    await writeFile(temporary, content ? `${content}\n` : "", { mode: 0o600 });
    await rename(temporary, this.path);
  }
}
