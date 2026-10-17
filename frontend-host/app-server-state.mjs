// A first connection describes the current backend. Only a later connection
// after a disconnect should trigger the official frontend's resume recovery.
export function createConnectionTracker() {
  let current;
  let connectedBefore = false;
  return {
    receive(message) {
      if (message.state === "connected" && current?.state === "connected") return null;
      current = {
        state: message.state,
        error: message.message ?? null,
        isSnapshot: message.state === "connected" && !connectedBefore,
      };
      if (message.state === "connected") connectedBefore = true;
      return current;
    },
    snapshot() { return current ? { ...current, isSnapshot: true } : null; },
  };
}

// Observe errors without changing the official request or response. A writer
// conflict cannot be resolved by pretending resume succeeded or taking over.
export function createThreadConflictObserver(onConflict) {
  const resumes = new Map();
  const warnedThreads = new Set();
  return {
    request(message) {
      if (message.method === "thread/resume" && message.id != null && typeof message.params?.threadId === "string") {
        resumes.set(message.id, message.params.threadId);
      }
    },
    response(message) {
      const threadId = resumes.get(message.id);
      if (threadId === undefined) return;
      resumes.delete(message.id);
      const detail = message.error?.message;
      if (!message.error) warnedThreads.delete(threadId);
      if (typeof detail === "string" && /already has (?:an active|a live local) writer/i.test(detail)) {
        if (!warnedThreads.has(threadId)) {
          warnedThreads.add(threadId);
          onConflict({ type: "thread-conflict", threadId, message: detail, retryable: true });
        }
      }
    },
  };
}
