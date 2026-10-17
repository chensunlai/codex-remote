/* Host implementation of VS Code's public WebView API. Official messages remain opaque. */
(() => {
  'use strict';
  const native = window.CodexExtensionBridge;
  const config = window.__codexExtensionConfig;
  delete window.__codexExtensionConfig;
  let acquired = false;
  let state = JSON.parse(native.getState(config.documentId));

  function encode(value) {
    if (value == null) return ['null', null];
    if (typeof value === 'boolean') return ['bool', value];
    if (typeof value === 'number') return Number.isFinite(value) ? ['number', value] : ['null', null];
    if (typeof value === 'string') return ['string', value];
    if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) {
      const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      let binary = '';
      for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return ['bytes', btoa(binary)];
    }
    if (Array.isArray(value)) return ['array', value.map(encode)];
    if (typeof value === 'object') return ['object', Object.entries(value).filter(([, v]) => v !== undefined).map(([k, v]) => [k, encode(v)])];
    if (typeof value === 'undefined') return ['null', null];
    throw new TypeError('WebView messages must be serializable');
  }

  function decode(encoded) {
    if (!Array.isArray(encoded) || encoded.length !== 2) throw new TypeError('Invalid host message');
    const [kind, value] = encoded;
    switch (kind) {
      case 'null': return null;
      case 'bool': case 'number': case 'string': return value;
      case 'array': return value.map(decode);
      case 'object': return Object.fromEntries(value.map(([key, child]) => [key, decode(child)]));
      case 'bytes': return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
      default: throw new TypeError('Invalid host message type');
    }
  }

  window.acquireVsCodeApi = () => {
    if (acquired) throw new Error('An instance of the VS Code API has already been acquired');
    acquired = true;
    return Object.freeze({
      postMessage(message) { native.postMessage(config.documentId, JSON.stringify(encode(message))); },
      getState() { return state; },
      setState(value) {
        // The public state API stores JSON-serializable values. Preserve the complete value.
        const json = JSON.stringify(value);
        native.setState(config.documentId, json === undefined ? 'null' : json);
        state = json === undefined ? undefined : JSON.parse(json);
        return value;
      },
    });
  };

  let theme = config.theme;
  let fontScale = config.fontScale;
  function applyTheme() {
    const root = document.documentElement;
    if (!root) return;
    root.setAttribute('data-codex-theme', theme);
    root.style.setProperty('--vscode-font-size', `${13 * fontScale}px`);
    root.style.setProperty('--vscode-editor-font-size', `${13 * fontScale}px`);
    let style = document.getElementById('codex-extension-host-theme');
    if (!style && document.head) {
      style = document.createElement('style');
      style.id = 'codex-extension-host-theme';
      style.textContent = config.themeCss;
      document.head.appendChild(style);
    }
    if (document.body) {
      document.body.classList.remove('vscode-dark', 'vscode-light', 'vscode-high-contrast');
      document.body.classList.add(theme === 'dark' ? 'vscode-dark' : 'vscode-light');
      document.body.setAttribute('data-vscode-theme-kind', theme === 'dark' ? 'vscode-dark' : 'vscode-light');
      document.body.setAttribute('data-vscode-theme-name', theme === 'anthropic' ? 'Anthropic' : theme === 'dark' ? 'Dark' : 'Light');
    }
  }
  const observer = new MutationObserver(() => {
    applyTheme();
    if (document.body && document.head) observer.disconnect();
  });
  observer.observe(document, { childList: true, subtree: true });
  applyTheme();
  window.__codexExtensionHost = Object.freeze({
    receive(encoded) { window.dispatchEvent(new MessageEvent('message', { data: decode(encoded), source: window, origin: location.origin })); },
    setTheme(nextTheme, nextScale) { theme = nextTheme; fontScale = nextScale; applyTheme(); },
  });
  const ready = () => { applyTheme(); observer.disconnect(); native.ready(config.documentId); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true });
  else ready();
})();
