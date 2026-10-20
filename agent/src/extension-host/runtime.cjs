'use strict';

const { registerHooks } = require('node:module');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const diagnostics = require('node:diagnostics_channel');
const { execFile } = require('node:child_process');
const { createHost } = require('./vscode.cjs');
const { encode, decode } = require('./codec.cjs');
require('node:http').setGlobalProxyFromEnv?.();
let host, extension, stopping = false;
const children = new Set();
const emit = value => { if (process.connected) process.send(value); };

process.on('message', async message => {
  try {
    if (message.type === 'start') {
      if (host) throw new Error('Extension host is already started');
      host = createHost(message.options, event => {
        if (event.type === 'message') emit({ type: 'message', surfaceId: event.surfaceId, payload: encode(event.message) }); else emit(event);
      });
      const publicApi = host.api;
      // Only the published vscode module is supplied. All Node APIs remain real.
      globalThis.__codexRemoteVsCode = publicApi;
      registerHooks({ resolve(specifier, context, nextResolve) {
        if (specifier === 'vscode') return { url: pathToFileURL(path.join(__dirname, 'vscode-module.cjs')).href, shortCircuit: true };
        return nextResolve(specifier, context);
      } });
      diagnostics.subscribe('child_process', ({ process: child }) => {
        children.add(child);
        child.once('exit', () => { children.delete(child); emit({ type: 'process-exit', pid: child.pid }); });
        child.once('spawn', () => {
          if (child.spawnargs?.includes('app-server')) {
            emit({ type: 'process-start', pid: child.pid, version: null });
            execFile(message.options.cliExecutable, ['--version'], { timeout: 10_000, windowsHide: true }, (error, stdout) => {
              if (children.has(child) && child.exitCode === null && child.signalCode === null)
                emit({ type: 'process-start', pid: child.pid, version: error ? null : String(stdout).trim().replace(/^codex(?:-cli)?\s+/, '') });
            });
          }
        });
      });
      extension = require(path.join(message.options.extensionDirectory, message.options.main || 'out/extension.js'));
      host.extension.exports = await extension.activate(host.context);
      await host.resolveView();
      emit({ type: 'started' });
    } else if (message.type === 'message') host?.receive(decode(message.payload), message.surfaceId || 'main');
    else if (message.type === 'close-surface') host?.closeSurface(message.surfaceId);
    else if (message.type === 'visibility') host?.visibility(message.visible);
    else if (message.type === 'host-response') host?.respond(message);
    else if (message.type === 'stop') await stop();
  } catch (error) {
    emit({ type: 'error', message: error.stack || error.message || String(error), fatal: message.type === 'start' });
    if (message.type === 'start') await stop();
  }
});

async function stop() {
  if (stopping) return;
  stopping = true;
  try { await extension?.deactivate?.(); } catch (error) { console.error(error); }
  host?.dispose();
  for (const child of children) child.kill('SIGTERM');
  setTimeout(() => process.exit(0), 100).unref();
  process.disconnect?.();
}
process.on('disconnect', stop);
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
process.on('unhandledRejection', error => emit({ type: 'error', message: String(error), fatal: false }));
