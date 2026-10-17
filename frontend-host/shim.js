(() => {
  "use strict";
  const pending = [];
  let handler;
  let acquired = false;
  const scope = window.__codexRemoteStorageKey || document.querySelector('meta[name="codex-remote-storage-key"]')?.content || location.host;
  const storageKey = `codex-remote:${scope}:webview-state`;
  function readState() {
    try { return JSON.parse(localStorage.getItem(storageKey) || "null"); }
    catch { return null; }
  }
  const api = Object.freeze({
    postMessage(message) {
      if (handler) handler(message);
      else pending.push(message);
    },
    getState: readState,
    setState(value) {
      localStorage.setItem(storageKey, JSON.stringify(value));
      return value;
    },
  });
  window.acquireVsCodeApi = () => {
    if (acquired) throw new Error("An instance of the VS Code API has already been acquired");
    acquired = true;
    return api;
  };
  window.__codexRemoteInstallHost = (receive) => {
    if (handler) throw new Error("Codex host is already installed");
    handler = receive;
    for (const message of pending.splice(0)) handler(message);
  };
  const nativePending = [];
  window.codexRemoteReceive = (message) => nativePending.push(message);
  window.__codexRemoteInstallReceiver = (receive) => {
    window.codexRemoteReceive = receive;
    for (const message of nativePending.splice(0)) receive(message);
  };
})();
