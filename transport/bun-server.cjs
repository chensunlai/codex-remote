'use strict';
// Bun's ws compatibility layer omits native backlog from bufferedAmount and
// retries messages already accepted by send() === -1. Use public Bun socket
// APIs for writes; retain ws/Fastify authentication, upgrade and receive events.
const { AsyncLocalStorage } = require('node:async_hooks');
const upgrades = new AsyncLocalStorage();
const KEY = 'codexRemoteTransport';
const HIGH_WATER = 3 * 1024 * 1024;
function configureBunServer(bun, wireLimit) {
  const serve = bun.serve;
  bun.serve = function (options) {
    if (!options.websocket) return serve.call(this, options);
    const handlers = options.websocket;
    const server = serve.call(this, { ...options, websocket: {
      ...handlers,
      maxPayloadLength: wireLimit,
      backpressureLimit: wireLimit + HIGH_WATER,
      open(raw) {
        const state = raw.data[KEY];
        if (state) { state.raw = raw; state.resolve(raw); }
        handlers.open?.(raw);
      },
      close(raw, code, reason) {
        const state = raw.data[KEY];
        if (state) { state.closed = true; state.reject(new Error('WebSocket disconnected')); }
        handlers.close?.(raw, code, reason);
      },
    } });
    const upgrade = server.upgrade;
    server.upgrade = function (request, options) {
      return upgrade.call(this, request, { ...options, data: { ...options?.data, [KEY]: upgrades.getStore() } });
    };
    return server;
  };
}
function configureBunWebSocketServer(server) {
  if (!process.versions.bun) return;
  const handleUpgrade = server.handleUpgrade;
  server.handleUpgrade = function (request, socket, head, callback) {
    const state = {};
    state.ready = new Promise((resolve, reject) => { state.resolve = resolve; state.reject = reject; });
    state.ready.catch(() => {});
    return upgrades.run(state, () => handleUpgrade.call(this, request, socket, head, (websocket, ...args) => {
      bindBunSocket(websocket, state);
      callback(websocket, ...args);
    }));
  };
}
function bindBunSocket(socket, state) {
  let tail = Promise.resolve();
  Object.defineProperty(socket, 'bufferedAmount', { get: () => state.raw?.getBufferedAmount() ?? 0 });
  socket.send = (data, options, callback) => {
    if (typeof options === 'function') { callback = options; options = undefined; }
    const sent = tail.then(async () => {
      const raw = await state.ready;
      let changed = Date.now(), previous = raw.getBufferedAmount();
      async function drain(force = false) {
        while (force || raw.getBufferedAmount() > HIGH_WATER) {
          force = false;
          if (state.closed || raw.readyState !== 1) throw new Error('WebSocket disconnected');
          const buffered = raw.getBufferedAmount();
          if (buffered < previous) changed = Date.now();
          if (Date.now() - changed >= 60_000) throw new Error('WebSocket send stalled');
          previous = buffered;
          await new Promise(resolve => setTimeout(resolve, 10));
        }
      }
      await drain();
      if (state.closed || raw.readyState !== 1) throw new Error('WebSocket disconnected');
      while (raw.send(data, options?.compress) === 0) await drain(true);
      // -1 means accepted into the native buffer: wait, never send it again.
      await drain();
    });
    tail = sent.catch(() => {});
    void sent.then(() => callback?.(), error => { callback?.(error); socket.terminate(); });
  };
  socket.once('close', () => { state.closed = true; state.reject(new Error('WebSocket disconnected')); });
}
module.exports = { configureBunServer, configureBunWebSocketServer };
