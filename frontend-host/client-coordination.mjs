// Host-side adapter for the official local IPC protocol. The official WebView
// retains thread roles, follower operations, approvals and stream reconciliation.
const versions = {
  "thread-stream-state-changed": 11, "thread-stream-following-changed": 1,
  "thread-stream-following-status-requested": 1, "ipc-connection-reset": 1,
  "thread-archived": 2, "thread-unarchived": 1, "thread-owner-discovery": 1,
  "thread-follower-start-turn": 2, "thread-follower-load-complete-history": 1,
  "thread-follower-compact-thread": 1, "thread-follower-steer-turn": 1,
  "thread-follower-interrupt-turn": 4, "thread-follower-update-thread-settings": 2,
  "thread-follower-update-daybreak": 1, "thread-follower-read-model-settings": 1,
  "thread-follower-edit-last-user-turn": 2, "thread-follower-command-approval-decision": 1,
  "thread-follower-file-approval-decision": 1, "thread-follower-permissions-request-approval-response": 1,
  "thread-follower-submit-user-input": 1, "thread-follower-submit-mcp-server-elicitation-response": 1,
  "thread-follower-set-queued-follow-ups-state": 1, "thread-follower-remove-queued-message": 1,
  "thread-follower-clear-queued-messages": 1, "thread-queued-followups-changed": 2,
};
const broadcasts = {
  "client-status-changed": "clientStatusChanged", "ipc-connection-reset": "ipcConnectionReset",
  "thread-stream-state-changed": "threadStreamStateChanged",
  "thread-stream-following-changed": "threadStreamFollowingChanged",
  "thread-stream-following-status-requested": "threadStreamFollowingStatusRequested",
  "thread-archived": "threadArchived", "thread-unarchived": "threadUnarchived",
  "thread-queued-followups-changed": "threadQueuedFollowUpsChanged", "query-cache-invalidate": "invalidateQueryCache",
};
const followerMethods = new Set(Object.keys(versions).filter(method => method.startsWith("thread-follower-")));

async function timed(promise, ms, message) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), ms);
    })]);
  } finally { clearTimeout(timer); }
}
function local(hostId = "local") { if (hostId !== "local") throw new Error("Unsupported coordination host"); }

export function createClientCoordination(send, report = () => {}) {
  const pending = new Map();
  const ownedThreads = new Set();
  const waiters = new Set();
  let view;
  let clientId;
  let state = "unavailable";
  let generation = 0;

  function wire(message) { send({ type: "message", message }); }
  function settled(available) { for (const resolve of waiters) resolve(available); waiters.clear(); }
  function notify(method, sourceClientId, params) {
    if (!view) return;
    Promise.resolve(view[method]({ sourceClientId, params })).catch(error => report(error.message));
  }
  function reset(nextState, preserveWaiters = false) {
    if (clientId) notify("ipcConnectionReset", clientId, {});
    generation++;
    clientId = undefined;
    state = nextState;
    if (!preserveWaiters) settled(false);
    for (const entry of pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(new Error("Official client coordination disconnected; request outcome may be unknown"));
    }
    pending.clear();
  }
  async function available(timeoutMs = 5000) {
    if (clientId) return true;
    if (state !== "connecting") return false;
    let resolve;
    try { return await timed(new Promise(done => { resolve = done; waiters.add(done); }), timeoutMs, "coordination-initialization-timeout"); }
    finally { waiters.delete(resolve); }
  }
  function request(method, params, { targetClientId, timeoutMs = 5000 } = {}) {
    if (!clientId && method !== "initialize") return Promise.reject(new Error("coordination-not-connected"));
    let version = versions[method] ?? 0;
    if (method === "thread-follower-interrupt-turn" && params.expectedTurnId == null) version = 3;
    const requestId = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`${method}-timeout`)); }, timeoutMs);
      pending.set(requestId, { resolve, reject, timer });
      try {
        wire({ type: "request", requestId, sourceClientId: clientId ?? "initializing-client",
          method, params, version, targetClientId, timeoutMs });
      } catch (error) { clearTimeout(timer); pending.delete(requestId); reject(error); }
    });
  }
  async function broadcast(method, params, targetClientIds) {
    if (targetClientIds?.length === 0 || !await available()) return;
    wire({ type: "broadcast", sourceClientId: clientId, method, params, targetClientIds, version: versions[method] ?? 0 });
  }
  function supported(message) {
    if (message.hostId != null && message.hostId !== "local") return false;
    const version = message.version ?? 0;
    return (message.method === "thread-owner-discovery" || followerMethods.has(message.method))
      && (version === versions[message.method] || message.method === "thread-follower-interrupt-turn" && version === 3);
  }
  async function canHandle(message) {
    if (!view || !supported(message)) return false;
    const { conversationId, hostId } = message.params ?? {};
    if (message.method === "thread-owner-discovery") {
      if (hostId !== "local") return false;
      if (ownedThreads.has(conversationId)) return true;
    }
    return await timed(view.getThreadRole({ hostId: "local", conversationId }),
      message.method === "thread-owner-discovery" ? 100 : 5000, "thread-role-timeout") === "owner";
  }
  async function incoming(message) {
    const current = generation;
    if (message.type === "response") {
      const entry = pending.get(message.requestId);
      if (!entry) return;
      pending.delete(message.requestId);
      clearTimeout(entry.timer);
      entry.resolve(message);
    } else if (message.type === "broadcast") {
      if (message.targetClientIds != null && !message.targetClientIds.includes(clientId)) return;
      const method = broadcasts[message.method];
      if (method && (message.version ?? 0) === (versions[message.method] ?? 0)) notify(method, message.sourceClientId, message.params);
    } else if (message.type === "client-discovery-request") {
      let result = false;
      try { result = await canHandle(message.request); } catch { /* A missing or inactive renderer cannot own a request. */ }
      if (current === generation) wire({ type: "client-discovery-response", requestId: message.requestId, response: { canHandle: result } });
    } else if (message.type === "request") {
      let response;
      try {
        if (!supported(message)) throw new Error("request-version-mismatch");
        if (!await canHandle(message)) throw new Error("thread-owner-not-found");
        let result = { supportsUntrustedAppInput: true };
        if (message.method !== "thread-owner-discovery") {
          const value = await timed(view.requestThreadFollower({ hostId: "local", request: { method: message.method, params: message.params } }),
            message.method === "thread-follower-load-complete-history" ? 300_000 : 5000, `${message.method}-timeout`);
          try {
            if (value.method !== message.method) throw new Error("thread-follower-response-method-mismatch");
            result = value.result;
          } finally { value[Symbol.dispose]?.(); }
        }
        response = { type: "response", requestId: message.requestId, resultType: "success", method: message.method, handledByClientId: clientId, result };
      } catch (error) { response = { type: "response", requestId: message.requestId, resultType: "error", error: error.message }; }
      if (current === generation) wire(response);
    }
  }

  const service = {
    async setThreadOwnership({ hostId, conversationId, ownsThread }) {
      local(hostId);
      if (ownsThread) ownedThreads.add(conversationId); else ownedThreads.delete(conversationId);
    },
    async findThreadOwner(params) {
      local(params.hostId);
      if (!await available()) return null;
      const response = await request("thread-owner-discovery", params);
      if (response.resultType === "success") return response.handledByClientId;
      if (response.error === "no-client-found") return null;
      throw new Error(response.error);
    },
    async requestThreadFollower({ hostId, request: call, targetClientId, timeoutMs }) {
      local(hostId);
      if (!followerMethods.has(call.method)) throw new Error("Unsupported follower request");
      if (!await available(timeoutMs)) throw new Error("coordination-not-connected");
      if (call.method === "thread-follower-start-turn" && (call.params.turnStart?.context?.responseItems?.length ?? 0) > 0) {
        const owner = await request("thread-owner-discovery", { hostId, conversationId: call.params.conversationId }, { targetClientId, timeoutMs });
        if (owner.resultType !== "success" || owner.handledByClientId !== targetClientId || owner.result?.supportsUntrustedAppInput !== true) {
          throw new Error("Thread owner does not support untrusted app input");
        }
      }
      return request(call.method, call.params, { targetClientId, timeoutMs });
    },
    async getIdeContext({ workspaceRoot, targetClientId, timeoutMs }) {
      if (!await available(timeoutMs)) return { activeFile: null, openFiles: [], workspaceFolders: [workspaceRoot] };
      const response = await request("ide-context", { workspaceRoot }, { targetClientId, timeoutMs });
      if (response.resultType === "error") throw new Error(response.error);
      return response.result.ideContext;
    },
    async threadArchived(params) { await broadcast("thread-archived", params); },
    async threadUnarchived(params) { await broadcast("thread-unarchived", params); },
    async threadQueuedFollowUpsChanged(params) { await broadcast("thread-queued-followups-changed", params); },
    async invalidateQueryCache(params) { await broadcast("query-cache-invalidate", params); },
    async threadStreamStateChanged({ params, targetClientIds }) { await broadcast("thread-stream-state-changed", params, targetClientIds); },
    async threadStreamFollowingChanged({ params, targetClientIds }) { await broadcast("thread-stream-following-changed", params, targetClientIds); },
    async threadStreamFollowingStatusRequested(params) { await broadcast("thread-stream-following-status-requested", params); },
    // Read markers require the desktop account/execution-host identity context.
    // Keep them local until that context can be supplied, rather than leaking across accounts.
    async threadReadStateChanged() {},
  };
  return {
    service,
    setView(value) { view = value; },
    connect(enabled) { reset(enabled ? "connecting" : "unavailable"); if (enabled) send({ type: "connect" }); },
    disconnect() { reset("unavailable"); },
    receive(event) {
      if (event.type === "connected") {
        reset("connecting", true);
        const current = generation;
        request("initialize", { clientType: "vscode" }).then(response => {
          if (generation !== current) return;
          if (response.resultType !== "success" || typeof response.result?.clientId !== "string") throw new Error("IPC initialization failed");
          clientId = response.result.clientId;
          state = "connected";
          settled(true);
          notify("clientStatusChanged", clientId, { clientId, clientType: "vscode", isSelf: true, status: "connected" });
        }).catch(error => { if (generation === current) { reset("unavailable"); report(error.message); } });
      } else if (event.type === "message") { void incoming(event.message).catch(error => report(error.message)); }
      else if (event.type === "unavailable" || event.type === "disconnected") reset("unavailable");
    },
  };
}
