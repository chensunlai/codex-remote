import { createAppServerInbox } from "./app-server-inbox.mjs";

const version = document.querySelector('meta[name="codex-extension-version"]')?.content;
const scope = window.__codexRemoteStorageKey || document.querySelector('meta[name="codex-remote-storage-key"]')?.content || location.host;
const prefix = `codex-remote:${scope}:`;
const nativePending = new Map();
const rpcPending = new Map();
const shared = new Map();
const sessions = new Map();
const settingSubscribers = new Map();
let initialized;
let pageReady = false;
let sequence = 0;
const appServerInbox = createAppServerInbox({ isReady: () => pageReady && !!initialized, dispatch: receiveRpc });

function send(message) {
  if (!window.CodexRemoteHost?.postMessage) throw new Error("Android host bridge is unavailable");
  window.CodexRemoteHost.postMessage(JSON.stringify(message));
}
function log(level, message) { send({ type: "log", level, message }); }
function deliver(message) { window.postMessage(message, location.origin); }
function load(key, fallback = undefined) {
  try { return JSON.parse(localStorage.getItem(prefix + key) ?? "null") ?? fallback; }
  catch { return fallback; }
}
function save(key, value) {
  if (value === undefined) localStorage.removeItem(prefix + key);
  else localStorage.setItem(prefix + key, JSON.stringify(value));
}
function nativeRequest(method, params = {}) {
  const id = `native-${++sequence}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { nativePending.delete(id); reject(new Error(`Host request timed out: ${method}`)); }, 30_000);
    nativePending.set(id, { resolve, reject, timer });
    send({ type: "host-request", id, method, params });
  });
}
function request(method, params = {}) {
  const id = `host-${++sequence}`;
  return new Promise((resolve, reject) => {
    rpcPending.set(id, { resolve, reject });
    send({ type: "rpc", request: { id, method, params } });
  });
}
function unsupported(name) { throw new Error(`This host does not support ${name}`); }
function project() {
  if (!initialized?.home) throw new Error("Remote workspace is not initialized");
  const root = load("workspace-root", initialized.workspace || initialized.home);
  return { id: "codex-remote-workspace", name: root.split("/").filter(Boolean).at(-1) || root, rootPaths: [root], createdAt: 1, updatedAt: 1 };
}
function initialMessages() {
  if (!pageReady || !initialized) return;
  deliver({ type: "codex-app-server-initialized", hostId: "local", transport: "stdio", appServerVersion: initialized.runningVersion, installedCodexVersion: initialized.runningVersion, isSnapshot: true });
  deliver({ type: "persisted-atom-sync", state: load("atoms", {}) });
  deliver({ type: "chat-font-settings", chatFontSize: null, chatCodeFontSize: null });
  deliver({ type: "request-user-input-auto-resolution-snapshot", hostId: "local", pendingRequests: [] });
  appServerInbox.flush();
}
function receiveRpc(message) {
  if (message.method) {
    if (message.id !== undefined) deliver({ type: "mcp-request", hostId: "local", request: message });
    else deliver({ type: "mcp-notification", hostId: "local", method: message.method, params: message.params });
  } else {
    const pending = rpcPending.get(message.id);
    if (pending) {
      rpcPending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
    } else deliver({ type: "mcp-response", hostId: "local", message });
  }
}
function receiveNative(message) {
  if (message.type === "initialized") {
    initialized = message;
    if (typeof message.workspace === "string" && message.workspace.startsWith("/")) {
      save("workspace-root", message.workspace);
      const current = project();
      save("global:local-projects", { [current.id]: current });
      save("global:selected-project", { type: "local", projectId: current.id });
    }
    initialMessages();
  }
  else if (message.type === "rpc-message") appServerInbox.receive(message.message);
  else if (message.type === "host-response") {
    const pending = nativePending.get(message.id);
    if (!pending) return;
    nativePending.delete(message.id);
    clearTimeout(pending.timer);
    if (message.error) pending.reject(new Error(message.error.message || String(message.error)));
    else pending.resolve(message.result);
  } else if (message.type === "connection") {
    if (message.state === "resync-required") { location.reload(); return; }
    deliver({ type: "codex-app-server-connection-changed", hostId: "local", transport: "websocket", state: message.state, progress: null, error: message.message ?? null });
    if (message.state === "connected") initialMessages();
    if (message.state === "error") deliver({ type: "codex-app-server-fatal-error", hostId: "local", errorMessage: message.message || "Connection failed" });
  }
}

async function privateFetch(route, params = {}) {
  log("debug", `fetch ${route}`);
  switch (route) {
    case "get-global-state":
      if (params.key === "local-projects") { const value = project(); return { value: load(`global:${params.key}`, { [value.id]: value }) }; }
      if (params.key === "selected-project") return { value: load(`global:${params.key}`, { type: "local", projectId: project().id }) };
      return { value: load(`global:${params.key}`) };
    case "set-global-state": save(`global:${params.key}`, params.value); deliver({ type: "global-state-updated", keys: [params.key] }); return { success: true };
    case "get-configuration": return { value: load(`configuration:${params.key}`) };
    case "set-configuration": save(`configuration:${params.key}`, params.value); return { success: true };
    case "set-vs-context": shared.set(`context:${params.key}`, params.value); return { success: true };
    case "get-settings": return settings.readAll();
    case "get-setting": return { value: (await settings.read(params.key)).effective };
    case "set-setting": await settings.write(params.key, params.value); return { success: true };
    case "list-pinned-threads": return { threadIds: [] }; // The official VS Code host does not provide desktop pin storage.
    case "set-thread-pinned": case "set-pinned-threads-order": return { success: false };
    case "list-automations": return { items: [] };
    case "inbox-items": return { items: [], unreadRunCounts: { total: 0, automationIds: [], unreadRuns: [] } };
    case "workspace-root-options": return { roots: project().rootPaths };
    case "home-directory": return { homeDirectory: initialized.home };
    case "codex-home": return { codexHome: initialized.result?.codexHome || `${initialized.home}/.codex`, workspacesRoot: null, worktreesSegment: null };
    case "locale-info": return { ideLocale: navigator.language, systemLocale: navigator.language };
    case "os-info": return { platform: "linux", osVersion: null, isVsCodeRunningInsideWsl: false };
    case "has-custom-cli-executable": return { hasCustomCliExecutable: true };
    case "is-copilot-api-available": return { available: false };
    case "get-copilot-api-proxy-info": return null;
    case "app-server-connection-state": return { state: null, progress: null, error: null };
    case "refresh-remote-connections": return { remoteConnections: [] };
    case "refresh-remote-control-connections": return { remoteControlConnections: [] };
    case "refresh-remote-wsl-connections": return { remoteWslConnections: [] };
    case "discover-remote-ssh-connections": return { sshAliases: [] };
    case "account-info": {
      const { account } = await request("account/read", { refreshToken: false });
      return { accountId: null, userId: null, plan: account?.planType ?? null, email: account?.email ?? null, computeResidency: null, hasChatGptToken: account?.type === "chatgpt" };
    }
    case "read-file": return { contents: (await nativeRequest("fs/read", { path: params.path, encoding: "utf8" })).content };
    case "read-file-binary": return { contentsBase64: (await nativeRequest("fs/read", { path: params.path, encoding: "base64" })).content };
    case "read-file-metadata": return nativeRequest("fs/stat", { path: params.path });
    case "write-file": await nativeRequest("fs/write", { path: params.path, content: params.contents, encoding: "utf8" }); return {};
    case "ensure-directory": await nativeRequest("fs/mkdir", { path: params.path, recursive: true }); return {};
    case "workspace-directory-entries": return nativeRequest("fs/list", { path: params.path || project().rootPaths[0] });
    case "mcp-codex-config": return { config: null }; // Optional VS Code LSP MCP injection, not the user's Codex configuration.
    case "worktree-shell-environment-config": return { shellEnvironment: null };
    case "third-party-notices": return { text: null };
    default: return unsupported(`vscode://codex/${route}`);
  }
}

const settings = {
  async read(key) { const value = load(`settings:${key}`); return { configured: value, effective: value }; },
  async readAll() {
    const values = {};
    const stem = prefix + "settings:";
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key.startsWith(stem)) values[key.slice(stem.length)] = load(key.slice(prefix.length));
    }
    return { values, configuredValues: values };
  },
  async write(key, value) {
    save(`settings:${key}`, value);
    for (const callback of settingSubscribers.get(key) ?? []) await callback(await settings.read(key));
  },
};

function updateMemberships(records, onlyUnassigned = false) {
  const assignments = load("global:thread-project-assignments", {});
  const projectless = new Set(load("global:projectless-thread-ids", []));
  const changes = {};
  for (const record of records) {
    if (onlyUnassigned && (assignments[record.threadId] || projectless.has(record.threadId))) continue;
    if ("assignment" in record) {
      if (record.assignment == null) delete assignments[record.threadId];
      else assignments[record.threadId] = record.assignment;
    }
    if ("projectless" in record) {
      if (record.projectless) projectless.add(record.threadId);
      else projectless.delete(record.threadId);
    }
    changes[record.threadId] = { assignment: assignments[record.threadId] ?? null, projectless: projectless.has(record.threadId) };
  }
  save("global:thread-project-assignments", assignments);
  save("global:projectless-thread-ids", [...projectless]);
  deliver({ type: "thread-project-memberships-updated", memberships: changes });
}

function createServices(RpcTarget) {
  class Subscription extends RpcTarget {
    constructor(cleanup) { super(); this.cleanup = cleanup; }
    unsubscribe() { this.cleanup?.(); this.cleanup = undefined; }
    [Symbol.dispose]() { this.unsubscribe(); }
  }
  settings.subscribe = async (key, listener) => {
    const callback = listener.dup();
    const listeners = settingSubscribers.get(key) ?? new Set();
    settingSubscribers.set(key, listeners);
    listeners.add(callback);
    const subscription = new Subscription(() => { listeners.delete(callback); callback[Symbol.dispose](); });
    await callback(await settings.read(key));
    return subscription;
  };
  return {
    appInfo: { async get() { return { version, buildNumber: null, buildFlavor: "prod", osName: "Android", systemVersion: null, appName: "Codex", editor: { name: "VS Code", vscodeApiVersion: "1.96.2" }, appIconMedium: null, dockIconPreviews: null }; } },
    startup: { async reach(phase) { log("info", `startup ${JSON.stringify(phase)}`); } },
    settings,
    threadProjectAssignments: {
      async setAssignment(record) { updateMemberships([record]); },
      async setProjectless(record) { updateMemberships([record]); },
      async setMembership(record) { updateMemberships([record]); },
      async restoreMemberships(records) { updateMemberships(records); },
      async assignIfUnassigned(records) { updateMemberships(records, true); },
    },
    clipboard: {
      async readText() { return (await nativeRequest("clipboard/read")).text; },
      async writeText(text) { await nativeRequest("clipboard/write", { text }); },
    },
    openIn: {
      async detectTarget() { return { available: false }; },
      async getTargets() { return { preferredTarget: null, availableTargets: [], targets: [] }; },
      async loadTargetIcon() { return unsupported("external editor icons"); },
      async open({ path, line, cwd, target }) {
        if (target === "fileManager") return unsupported("remote file manager");
        const cleaned = path.replace(/^([ab])[\\/]/, "");
        const absolute = cleaned.startsWith("/") ? cleaned : `${cwd || project().rootPaths[0]}/${cleaned}`;
        await nativeRequest("file/open", { path: absolute, line });
        return { success: true };
      },
    },
    workspaceFiles: {
      async getMetadata({ path, includeCanonicalPath }) { const value = await nativeRequest("fs/stat", { path }); return { kind: value.isDirectory ? "directory" : "file", canonicalPath: includeCanonicalPath ? path : undefined }; },
      async read({ path, representation }) { const blob = representation === "blob"; const value = await nativeRequest("fs/read", { path, encoding: blob ? "base64" : "utf8" }); return blob ? { blob: value.content } : { text: value.content }; },
      async write({ path, text }) { return nativeRequest("fs/write", { path, content: text, encoding: "utf8" }); },
    },
    clientCoordination: {
      async setThreadOwnership() {},
      async findThreadOwner() { return null; },
      async getIdeContext() { return { activeFile: null, openFiles: [], workspaceFolders: project().rootPaths }; },
      async invalidateQueryCache() {},
      async threadArchived() {},
      async threadUnarchived() {},
      async threadQueuedFollowUpsChanged() {},
      async threadStreamStateChanged() {},
      async threadStreamFollowingChanged() {},
      async threadStreamFollowingStatusRequested() {},
      async threadReadStateChanged() {},
      async requestThreadFollower() { throw new Error("No other frontend is attached to this host"); },
    },
  };
}

function makeTransport(sessionId) {
  const inbox = [];
  let waiter;
  let failure;
  return {
    encodingLevel: "jsonCompatibleWithBytes",
    send(message) { deliver({ type: "vscode-capn-rpc-message", sessionId, message }); },
    receive() {
      if (failure) return Promise.reject(failure);
      if (inbox.length) return Promise.resolve(inbox.shift());
      return new Promise((resolve, reject) => { waiter = { resolve, reject }; });
    },
    accept(message) {
      if (failure) return;
      if (waiter) { const current = waiter; waiter = undefined; current.resolve(message); }
      else inbox.push(message);
    },
    abort(error) { failure = error; waiter?.reject(error); waiter = undefined; },
  };
}

async function install() {
  const adapters = await (await fetch(new URL("./adapters.json", import.meta.url))).json();
  const binding = adapters.versions[version];
  if (!binding || binding.contractVersion !== 1) throw new Error(`Unsupported official frontend version: ${version}`);
  const runtime = await import(new URL(binding.bundle, document.baseURI).href);
  runtime[binding.initializeExport]();
  const RpcSession = runtime[binding.sessionExport];
  const RpcTarget = runtime[binding.targetExport];
  if (typeof RpcSession !== "function" || typeof RpcTarget !== "function") throw new Error("Official RPC binding is invalid");
  const services = createServices(RpcTarget);
  class HostRoot extends RpcTarget { get services() { return services; } }
  function handle(message) {
    if (message.type === "vscode-capn-rpc-connect") {
      if (sessions.has(message.sessionId)) return;
      const transport = makeTransport(message.sessionId);
      sessions.set(message.sessionId, { transport, session: new RpcSession(transport, new HostRoot()) });
      return;
    }
    if (message.type === "vscode-capn-rpc-message") { sessions.get(message.sessionId)?.transport.accept(message.message); return; }
    switch (message.type) {
      case "ready": pageReady = true; initialMessages(); break;
      case "mcp-request": case "thread-prewarm-start": send({ type: "rpc", request: message.request }); break;
      case "mcp-notification": send({ type: "notification", request: message.request }); break;
      case "mcp-response": send({ type: "response", response: message.response }); break;
      case "mcp-request-abandon": break; // Losing a renderer waiter must not cancel a remote turn.
      case "persisted-atom-sync-request": deliver({ type: "persisted-atom-sync", state: load("atoms", {}) }); break;
      case "persisted-atom-update": {
        const atoms = load("atoms", {});
        if (message.deleted) delete atoms[message.key]; else atoms[message.key] = message.value;
        save("atoms", atoms);
        deliver({ type: "persisted-atom-updated", key: message.key, value: message.value, deleted: !!message.deleted });
        break;
      }
      case "persisted-atom-reset": save("atoms", {}); deliver({ type: "persisted-atom-sync", state: {} }); break;
      case "fetch": {
        const route = message.url.startsWith("vscode://codex/") ? message.url.slice("vscode://codex/".length) : null;
        Promise.resolve().then(() => route === null ? unsupported("external HTTP") : privateFetch(route, message.body ? JSON.parse(message.body) : {})).then(
          result => deliver({ type: "fetch-response", responseType: "success", requestId: message.requestId, status: 200, headers: {}, bodyJsonString: JSON.stringify(result) }),
          error => { log("warning", error.message); deliver({ type: "fetch-response", responseType: "error", requestId: message.requestId, status: 501, error: error.message }); },
        );
        break;
      }
      case "shared-object-subscribe": deliver({ type: "shared-object-updated", key: message.key, value: shared.get(message.key) }); break;
      case "shared-object-set": shared.set(message.key, message.value); deliver({ type: "shared-object-updated", key: message.key, value: message.value }); break;
      case "shared-object-unsubscribe": case "cancel-fetch": case "view-focused": case "set-telemetry-user": break;
      case "open-in-browser": nativeRequest("open-url", { url: message.url }).catch(error => log("warning", error.message)); break;
      case "show-settings": deliver({ type: "navigate-to-route", path: `/settings/${message.section}`, state: { section: message.section } }); break;
      case "show-diff": deliver({ type: "navigate-to-route", path: "/diff", state: { unifiedDiff: message.unifiedDiff, conversationId: message.conversationId ?? null, cwd: message.cwd ?? null } }); break;
      case "show-plan-summary": deliver({ type: "navigate-to-route", path: "/plan-summary", state: { planContent: message.planContent, conversationId: message.conversationId, hostId: message.hostId } }); break;
      case "navigate-in-new-editor-tab": deliver({ type: "navigate-to-route", path: message.path }); break;
      case "open-config-toml": nativeRequest("file/open", { path: `${initialized.result?.codexHome || `${initialized.home}/.codex`}/config.toml` }).catch(error => log("warning", error.message)); break;
      case "log-message": {
        const error = message.tags?.sensitive?.error;
        const detail = message.tags?.sensitive?.errorMessage ?? (typeof error === "string" ? error : error?.message);
        log(message.level || "debug", `${message.message || ""}${detail ? `: ${detail}` : ""}`);
        break;
      }
      case "worker-request": {
        const method = message.request?.method;
        log("warning", `Unsupported worker request ${message.workerId}/${method}`);
        deliver({ type: "worker-response", workerId: message.workerId, response: { id: message.request.id, method, result: { type: "error", error: { message: `Unsupported worker request: ${method}` } } } });
        break;
      }
      default: log("debug", `Unhandled frontend message: ${message.type}`);
    }
  }
  window.__codexRemoteInstallReceiver(receiveNative);
  window.__codexRemoteInstallHost(handle);
  send({ type: "ready" });
}

install().catch(error => {
  log("error", `Host initialization failed: ${error.message}`);
  document.getElementById("root").textContent = `Codex frontend could not start: ${error.message}`;
});
