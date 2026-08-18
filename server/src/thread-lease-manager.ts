interface ThreadLeaseTarget {
  ownerId: string;
  serviceId: string;
  threadId: string;
}

interface ThreadLeaseCallbacks {
  status: (target: ThreadLeaseTarget) => Promise<string>;
  release: (target: ThreadLeaseTarget) => Promise<void>;
  changed: (target: ThreadLeaseTarget, locked: boolean) => void;
}

interface ThreadLeaseOptions {
  releaseDelayMs?: number;
  viewerTtlMs?: number;
  retryDelayMs?: number;
}

interface ThreadLease extends ThreadLeaseTarget {
  viewers: Map<string, number>;
  staleTimer?: NodeJS.Timeout;
  releaseTimer?: NodeJS.Timeout;
}

const DEFAULT_RELEASE_DELAY_MS = 10 * 60_000;
const DEFAULT_VIEWER_TTL_MS = 90_000;
const DEFAULT_RETRY_DELAY_MS = 15_000;

export class ThreadLeaseManager {
  private readonly leases = new Map<string, ThreadLease>();
  private readonly releaseDelayMs: number;
  private readonly viewerTtlMs: number;
  private readonly retryDelayMs: number;
  private closed = false;

  constructor(
    private readonly callbacks: ThreadLeaseCallbacks,
    options: ThreadLeaseOptions = {},
  ) {
    this.releaseDelayMs = options.releaseDelayMs ?? DEFAULT_RELEASE_DELAY_MS;
    this.viewerTtlMs = options.viewerTtlMs ?? DEFAULT_VIEWER_TTL_MS;
    this.retryDelayMs = options.retryDelayMs ?? DEFAULT_RETRY_DELAY_MS;
  }

  acquire(target: ThreadLeaseTarget, clientId: string): void {
    if (this.closed) return;
    const key = this.key(target);
    let lease = this.leases.get(key);
    if (!lease) {
      lease = { ...target, viewers: new Map() };
      this.leases.set(key, lease);
      this.callbacks.changed(target, true);
    }
    if (lease.releaseTimer) {
      clearTimeout(lease.releaseTimer);
      delete lease.releaseTimer;
    }
    lease.viewers.set(clientId, Date.now() + this.viewerTtlMs);
    this.scheduleStaleCheck(lease);
  }

  leave(target: ThreadLeaseTarget, clientId: string): number | undefined {
    const lease = this.leases.get(this.key(target));
    if (!lease) return undefined;
    lease.viewers.delete(clientId);
    if (lease.viewers.size > 0) {
      this.scheduleStaleCheck(lease);
      return undefined;
    }
    if (lease.staleTimer) {
      clearTimeout(lease.staleTimer);
      delete lease.staleTimer;
    }
    return this.scheduleRelease(lease, this.releaseDelayMs);
  }

  async release(target: ThreadLeaseTarget): Promise<void> {
    await this.callbacks.release(target);
    this.remove(target, true);
  }

  close(): void {
    this.closed = true;
    for (const lease of this.leases.values()) {
      if (lease.staleTimer) clearTimeout(lease.staleTimer);
      if (lease.releaseTimer) clearTimeout(lease.releaseTimer);
    }
    this.leases.clear();
  }

  private scheduleStaleCheck(lease: ThreadLease): void {
    if (lease.staleTimer) clearTimeout(lease.staleTimer);
    const expiry = Math.min(...lease.viewers.values());
    const timer = setTimeout(() => this.pruneStaleViewers(lease), Math.max(0, expiry - Date.now()));
    timer.unref();
    lease.staleTimer = timer;
  }

  private pruneStaleViewers(lease: ThreadLease): void {
    delete lease.staleTimer;
    if (this.closed || this.leases.get(this.key(lease)) !== lease) return;
    const now = Date.now();
    for (const [clientId, expiry] of lease.viewers) {
      if (expiry <= now) lease.viewers.delete(clientId);
    }
    if (lease.viewers.size > 0) {
      this.scheduleStaleCheck(lease);
    } else {
      this.scheduleRelease(lease, this.releaseDelayMs);
    }
  }

  private scheduleRelease(lease: ThreadLease, delayMs: number): number {
    if (lease.releaseTimer) clearTimeout(lease.releaseTimer);
    const releaseAt = Date.now() + delayMs;
    const timer = setTimeout(() => void this.attemptRelease(lease), delayMs);
    timer.unref();
    lease.releaseTimer = timer;
    return releaseAt;
  }

  private async attemptRelease(lease: ThreadLease): Promise<void> {
    delete lease.releaseTimer;
    if (this.closed || this.leases.get(this.key(lease)) !== lease || lease.viewers.size > 0) {
      return;
    }
    try {
      if (await this.callbacks.status(lease) === "active") {
        this.scheduleRelease(lease, this.retryDelayMs);
        return;
      }
      await this.callbacks.release(lease);
      this.remove(lease, true);
    } catch {
      if (!this.closed && this.leases.get(this.key(lease)) === lease) {
        this.scheduleRelease(lease, this.retryDelayMs);
      }
    }
  }

  private remove(target: ThreadLeaseTarget, notify: boolean): void {
    const key = this.key(target);
    const lease = this.leases.get(key);
    if (!lease) {
      if (notify) this.callbacks.changed(target, false);
      return;
    }
    if (lease.staleTimer) clearTimeout(lease.staleTimer);
    if (lease.releaseTimer) clearTimeout(lease.releaseTimer);
    this.leases.delete(key);
    if (notify) this.callbacks.changed(target, false);
  }

  private key(target: ThreadLeaseTarget): string {
    return `${target.ownerId}\0${target.serviceId}\0${target.threadId}`;
  }
}

export type { ThreadLeaseTarget };
