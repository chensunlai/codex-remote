import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import test from "node:test";
import { createAppServerInbox } from "./app-server-inbox.mjs";

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
