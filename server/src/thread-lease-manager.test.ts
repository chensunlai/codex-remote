import { describe, expect, it } from "vitest";
import { ThreadLeaseManager, type ThreadLeaseTarget } from "./thread-lease-manager.js";

const TARGET: ThreadLeaseTarget = {
  ownerId: "owner",
  serviceId: "service",
  threadId: "thread",
};

describe("ThreadLeaseManager", () => {
  it("waits for an abandoned active turn to finish before releasing", async () => {
    let status = "active";
    let releases = 0;
    const changes: boolean[] = [];
    const manager = new ThreadLeaseManager(
      {
        status: async () => status,
        release: async () => { releases += 1; },
        changed: (_target, locked) => changes.push(locked),
      },
      { releaseDelayMs: 10, viewerTtlMs: 100, retryDelayMs: 10 },
    );

    manager.acquire(TARGET, "019fff0d-1c52-7042-9de0-9cc0eecf4095");
    manager.leave(TARGET, "019fff0d-1c52-7042-9de0-9cc0eecf4095");
    await wait(25);
    expect(releases).toBe(0);

    status = "idle";
    await eventually(() => releases === 1);
    expect(changes).toEqual([true, false]);
    manager.close();
  });

  it("cancels a pending release when the viewer returns", async () => {
    let releases = 0;
    const manager = new ThreadLeaseManager(
      {
        status: async () => "idle",
        release: async () => { releases += 1; },
        changed: () => {},
      },
      { releaseDelayMs: 15, viewerTtlMs: 100, retryDelayMs: 10 },
    );
    const clientId = "019fff0d-1c52-7042-9de0-9cc0eecf4095";
    manager.acquire(TARGET, clientId);
    manager.leave(TARGET, clientId);
    manager.acquire(TARGET, clientId);
    await wait(30);
    expect(releases).toBe(0);
    manager.close();
  });
});

async function eventually(predicate: () => boolean, timeoutMs = 250): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("condition was not met");
    await wait(5);
  }
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
