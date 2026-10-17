import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import test from "node:test";
import { createAppServerInbox } from "./app-server-inbox.mjs";
import { createConnectionTracker, createThreadConflictObserver } from "./app-server-state.mjs";
import { applyTheme } from "./theme.mjs";

test("theme changes update official theme metadata without replacing page state", () => {
  const classes = new Set(["vscode-dark", "existing-view"]);
  const page = { documentElement: { dataset: {} }, body: { dataset: {}, classList: {
    remove: (...names) => names.forEach(name => classes.delete(name)), add: name => classes.add(name),
  } } };
  for (const theme of ["light", "anthropic", "dark"]) {
    assert.equal(applyTheme(theme, page), true);
    const mode = theme === "dark" ? "dark" : "light";
    assert.equal(page.documentElement.dataset.codexTheme, theme);
    assert.equal(page.documentElement.dataset.theme, mode);
    assert.equal(page.body.dataset.vscodeThemeKind, `vscode-${mode}`);
    assert.deepEqual([...classes].sort(), ["existing-view", `vscode-${mode}`].sort());
  }
  assert.equal(applyTheme("unknown", page), false);
  assert.equal(page.documentElement.dataset.codexTheme, "dark");
});

test("initial and duplicate connected events do not trigger thread recovery", () => {
  const connection = createConnectionTracker();
  assert.equal(connection.snapshot(), null);
  assert.deepEqual(connection.receive({ state: "connected" }), { state: "connected", error: null, isSnapshot: true });
  assert.equal(connection.receive({ state: "connected" }), null);
  assert.equal(connection.snapshot().isSnapshot, true, "a late frontend listener receives state without recovery");
  connection.receive({ state: "disconnected", message: "Network lost" });
  assert.deepEqual(connection.receive({ state: "connected" }), { state: "connected", error: null, isSnapshot: false });
  assert.equal(connection.receive({ state: "connected" }), null, "one reconnect must only resume once");
});

test("a failed cold connection followed by its first success is still a snapshot", () => {
  const connection = createConnectionTracker();
  connection.receive({ state: "disconnected" });
  assert.equal(connection.receive({ state: "connected" }).isSnapshot, true);
});

test("writer conflicts report the affected resume without modifying or retrying its response", () => {
  const notices = [];
  const observer = createThreadConflictObserver(notice => notices.push(notice));
  const request = { id: "resume-1", method: "thread/resume", params: { threadId: "owned-thread" } };
  const response = { id: "resume-1", error: { code: -32603, message: "Thread owned-thread already has an active writer" } };
  const original = structuredClone(response);
  observer.request(request);
  observer.response(response);
  observer.response(response);
  assert.deepEqual(response, original);
  assert.deepEqual(notices, [{ type: "thread-conflict", threadId: "owned-thread", message: response.error.message, retryable: true }]);
  observer.request({ ...request, id: "history-resume" });
  observer.response({ ...response, id: "history-resume" });
  assert.equal(notices.length, 1, "official history recovery must not produce repeated Native dialogs");
  observer.request({ ...request, id: "successful-resume" });
  observer.response({ id: "successful-resume", result: { thread: { id: "owned-thread" } } });
  observer.request({ ...request, id: "new-conflict" });
  observer.response({ ...response, id: "new-conflict" });
  assert.equal(notices.length, 2, "a later conflict after a successful resume should be reported");
  observer.request({ id: 2, method: "thread/resume", params: { threadId: "other-thread" } });
  observer.response({ id: 2, error: { code: -32603, message: "Thread already has a live local writer" } });
  assert.equal(notices[2].threadId, "other-thread");
});

test("unrelated RPC errors and successful resumes do not produce writer warnings", () => {
  const notices = [];
  const observer = createThreadConflictObserver(notice => notices.push(notice));
  observer.request({ id: 1, method: "thread/start", params: {} });
  observer.response({ id: 1, error: { message: "already has an active writer" } });
  observer.request({ id: 2, method: "thread/resume", params: { threadId: "t" } });
  observer.response({ id: 2, result: { thread: { id: "t" } } });
  observer.request({ id: 3, method: "thread/resume", params: { threadId: "t" } });
  observer.response({ id: 3, error: { message: "Thread not found" } });
  assert.deepEqual(notices, []);
});

test("bootstrap responses are immediate and replayed server events follow initialization", () => {
  let pageReady = false;
  let initialized = false;
  const delivered = [];
  const inbox = createAppServerInbox({ isReady: () => pageReady && initialized, dispatch: message => delivered.push(message) });
  const approval = { id: 42, method: "item/commandExecution/requestApproval", params: { itemId: "pending-command" } };
  const notification = { method: "turn/started", params: { threadId: "thread" } };
  const bootstrapResponse = { id: "config-read", result: { config: {} } };
  inbox.receive(approval);
  inbox.receive(notification);
  inbox.receive(bootstrapResponse);
  assert.deepEqual(delivered, [bootstrapResponse]);
  initialized = true;
  inbox.flush();
  assert.deepEqual(delivered, [bootstrapResponse]);
  pageReady = true;
  delivered.push({ type: "codex-app-server-initialized" });
  inbox.flush();
  assert.deepEqual(delivered, [bootstrapResponse, { type: "codex-app-server-initialized" }, approval, notification]);
  inbox.flush();
  assert.equal(delivered.length, 4, "replayed events must not be duplicated");
});

const shim = await readFile(new URL("./shim.js", import.meta.url), "utf8");
function createView(scope, storage = new Map()) {
  const context = vm.createContext({
    document: { querySelector: () => ({ content: scope }) },
    location: { host: "same-origin.test" },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
  });
  context.window = context;
  vm.runInContext(shim, context);
  return context;
}

test("shim buffers both directions until the host is installed and permits one API acquisition", () => {
  const view = createView("service-a");
  const api = view.acquireVsCodeApi();
  assert.throws(() => view.acquireVsCodeApi(), /already been acquired/);
  const upstream = [];
  const downstream = [];
  api.postMessage({ type: "vscode-capn-rpc-connect" });
  view.codexRemoteReceive({ type: "initialized" });
  view.__codexRemoteInstallHost(message => upstream.push(message));
  view.__codexRemoteInstallReceiver(message => downstream.push(message));
  api.postMessage({ type: "ready" });
  view.codexRemoteReceive({ type: "connection", state: "connected" });
  assert.deepEqual(upstream, [{ type: "vscode-capn-rpc-connect" }, { type: "ready" }]);
  assert.deepEqual(downstream, [{ type: "initialized" }, { type: "connection", state: "connected" }]);
});

test("WebView state survives reload and stays separate for each remote service", () => {
  const storage = new Map();
  createView("service-a", storage).acquireVsCodeApi().setState({ threadId: "a" });
  createView("service-b", storage).acquireVsCodeApi().setState({ threadId: "b" });
  assert.equal(createView("service-a", storage).acquireVsCodeApi().getState().threadId, "a");
  assert.equal(createView("service-b", storage).acquireVsCodeApi().getState().threadId, "b");
});
