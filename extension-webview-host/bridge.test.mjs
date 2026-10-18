import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('./bridge.js', import.meta.url), 'utf8');
function renderer(initialState = 'null', { styleSheets = [], mouseMode = false } = {}) {
  const sent = [], events = [], ready = [], stored = [];
  const observers = [], documentListeners = new Map(), windowListeners = new Map();
  let state = initialState;
  const style = { setProperty() {} };
  const node = { style, setAttribute() {}, classList: { remove() {}, add() {} } };
  const listen = (listeners, type, callback) => {
    if (!listeners.has(type)) listeners.set(type, new Set());
    listeners.get(type).add(callback);
  };
  const emit = (listeners, type, event = {}) => {
    for (const callback of listeners.get(type) || []) callback({ type, ...event });
  };
  const document = { documentElement: node, head: { appendChild() {} }, body: node,
    getElementById() { return node; }, readyState: 'complete', visibilityState: 'visible', styleSheets,
    addEventListener(type, callback) { listen(documentListeners, type, callback); },
    removeEventListener(type, callback) { documentListeners.get(type)?.delete(callback); },
  };
  const context = vm.createContext({
    window: null,
    location: { origin: 'https://fixture.codex-remote.invalid' },
    __codexExtensionConfig: { documentId: 'document-1', theme: 'light', fontScale: 1, themeCss: ':root {}', mouseMode },
    CodexExtensionBridge: {
      getState(id) { assert.equal(id, 'document-1'); return state; },
      setState(id, value) { assert.equal(id, 'document-1'); stored.push(value); state = value; },
      postMessage(id, value) { assert.equal(id, 'document-1'); sent.push(JSON.parse(value)); },
      ready(id) { ready.push(id); },
    },
    document,
    MutationObserver: class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe() { this.active = true; }
      disconnect() { this.active = false; }
    },
    MessageEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    addEventListener(type, callback) { listen(windowListeners, type, callback); },
    dispatchEvent(event) { events.push(event); emit(windowListeners, event.type, event); },
  });
  vm.runInContext('window = globalThis', context);
  vm.runInContext(source, context);
  return { context, sent, events, ready, stored, document, observers,
    emitDocument: (type, event) => emit(documentListeners, type, event),
    emitWindow: (type, event) => emit(windowListeners, type, event),
    mutate: records => { for (const observer of observers) if (observer.active) observer.callback(records); },
    run: (code) => vm.runInContext(code, context),
  };
}

test('official messages remain opaque and binary bytes survive both directions', () => {
  const r = renderer();
  r.run(`const vscode = acquireVsCodeApi(); vscode.postMessage({ arbitrary: ['method', { type: 'not-a-host-command', blob: new Uint8Array([0, 128, 255]) }], optional: undefined });`);
  assert.deepEqual(r.sent, [['object', [['arbitrary', ['array', [['string', 'method'], ['object', [
    ['type', ['string', 'not-a-host-command']], ['blob', ['bytes', 'AID/']],
  ]]]]]]]]);
  r.run(`__codexExtensionHost.receive(['object', [['constructor', ['string', 'literal-key']], ['bytes', ['bytes', 'AID/']]]]);`);
  assert.equal(r.events[0].data.constructor, 'literal-key');
  assert.deepEqual([...r.events[0].data.bytes], [0, 128, 255]);
  assert.equal(r.events[0].data.bytes.constructor.name, 'Uint8Array');
  assert.equal(r.events[0].origin, 'https://fixture.codex-remote.invalid');
  assert.equal(r.events[0].source, vm.runInContext('window', r.context));
});

test('public state is stored without interpreting official fields and acquire is single-use', () => {
  const r = renderer('{"unknown":{"draft":"retained"}}');
  assert.equal(r.run('const vscode = acquireVsCodeApi(); vscode.getState().unknown.draft'), 'retained');
  r.run('vscode.setState({other: [1, null, "draft"]})');
  assert.deepEqual(r.stored, ['{"other":[1,null,"draft"]}']);
  assert.throws(() => r.run('acquireVsCodeApi()'), /already been acquired/);
  assert.deepEqual(r.ready, ['document-1']);
});

test('array nulls and scalar values match JSON message semantics', () => {
  const r = renderer();
  r.run('acquireVsCodeApi().postMessage([undefined, true, 1.5, "test", null, NaN])');
  assert.deepEqual(r.sent[0], ['array', [['null', null], ['bool', true], ['number', 1.5], ['string', 'test'], ['null', null], ['null', null]]]);
});

test('mouse mode adapts nested media conditions and imports without changing other query terms', () => {
  const media = mediaText => ({ mediaText });
  const sheetMedia = media('(any-hover: none)');
  const nested = media('(hover: hover) and (min-width: 300px), not (any-hover)');
  const unknown = media('(unknown: "(hover: hover)") or (pointer: fine)');
  const imported = media('screen and (any-hover: hover)');
  const boolean = media('(hover) and (any-hover: none)');
  const readonly = { get mediaText() { return '(hover: hover)'; }, set mediaText(_value) { throw new Error('readonly'); } };
  const sheet = { media: sheetMedia, cssRules: [
    { cssRules: [{ media: nested, cssRules: [{ media: unknown }] }] },
    { media: imported, styleSheet: { cssRules: [{ media: boolean }, { media: readonly }] } },
  ] };
  const inaccessible = { get cssRules() { throw new Error('SecurityError'); } };
  const r = renderer('null', { styleSheets: [sheet, inaccessible] });
  r.run('__codexExtensionHost.setMouseMode(true)');
  assert.equal(sheetMedia.mediaText, '(max-width: -1px)');
  assert.equal(nested.mediaText, '(min-width: 0px) and (min-width: 300px), not (min-width: 0px)');
  assert.equal(imported.mediaText, 'screen and (min-width: 0px)');
  assert.equal(boolean.mediaText, '(min-width: 0px) and (max-width: -1px)');
  assert.equal(unknown.mediaText, '(unknown: "(hover: hover)") or (pointer: fine)');
  r.run('__codexExtensionHost.setMouseMode(false)');
  assert.equal(sheetMedia.mediaText, '(any-hover: none)');
  assert.equal(nested.mediaText, '(hover: hover) and (min-width: 300px), not (any-hover)');
  assert.equal(imported.mediaText, 'screen and (any-hover: hover)');
  assert.equal(boolean.mediaText, '(hover) and (any-hover: none)');
  assert.deepEqual(r.sent, []);
});

test('visibility and page lifecycle restore styles and retain the latest native mode request', () => {
  const media = { mediaText: '(hover: hover)' };
  const r = renderer('null', { styleSheets: [{ cssRules: [{ media }] }], mouseMode: true });
  assert.equal(media.mediaText, '(min-width: 0px)');
  r.document.visibilityState = 'hidden';
  r.emitDocument('visibilitychange');
  assert.equal(media.mediaText, '(hover: hover)');
  r.run('__codexExtensionHost.setMouseMode(false)');
  r.document.visibilityState = 'visible';
  r.emitDocument('visibilitychange');
  assert.equal(media.mediaText, '(hover: hover)', 'becoming visible must not override a native disable');
  r.run('__codexExtensionHost.setMouseMode(true)');
  r.emitWindow('pagehide');
  assert.equal(media.mediaText, '(hover: hover)');
  assert.equal(r.observers[1].active, false);
  r.emitWindow('pageshow');
  assert.equal(media.mediaText, '(min-width: 0px)');
  r.run('__codexExtensionHost.setMouseMode(false)');
  assert.equal(media.mediaText, '(hover: hover)');
});

test('late stylesheet changes are batched, unrelated mutations do not scan, and source changes survive restore', async () => {
  let reads = 0;
  const media = { mediaText: '(hover: hover)' };
  const sheets = [{ get cssRules() { reads++; return [{ media }]; } }];
  const r = renderer('null', { styleSheets: sheets, mouseMode: true });
  const initialReads = reads;
  r.mutate([{ target: { nodeType: 1, tagName: 'DIV' }, addedNodes: [], removedNodes: [] }]);
  await Promise.resolve();
  assert.equal(reads, initialReads, 'ordinary page updates must not rescan styles');
  const late = { mediaText: '(any-hover: hover)' };
  sheets.push({ cssRules: [{ media: late }] });
  const styleNode = { nodeType: 1, tagName: 'STYLE' };
  const record = { target: styleNode, addedNodes: [], removedNodes: [] };
  r.mutate([record]);
  r.mutate([record]);
  await Promise.resolve();
  assert.equal(reads, initialReads + 1, 'multiple style changes in one turn must share one scan');
  assert.equal(late.mediaText, '(min-width: 0px)');
  media.mediaText = '(hover: none) and (max-width: 500px)';
  r.emitDocument('load', { target: { nodeType: 1, tagName: 'LINK', rel: 'stylesheet' } });
  await Promise.resolve();
  assert.equal(media.mediaText, '(max-width: -1px) and (max-width: 500px)');
  r.run('__codexExtensionHost.setMouseMode(false)');
  assert.equal(media.mediaText, '(hover: none) and (max-width: 500px)');
  assert.equal(late.mediaText, '(any-hover: hover)');
  const disabledReads = reads;
  r.emitDocument('load', { target: styleNode });
  r.mutate([record]);
  await Promise.resolve();
  assert.equal(reads, disabledReads);
});
