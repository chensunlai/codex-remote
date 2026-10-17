import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('./bridge.js', import.meta.url), 'utf8');
function renderer(initialState = 'null') {
  const sent = [], events = [], ready = [], stored = [];
  let state = initialState;
  const style = { setProperty() {} };
  const node = { style, setAttribute() {}, classList: { remove() {}, add() {} } };
  const context = vm.createContext({
    window: null,
    location: { origin: 'https://fixture.codex-remote.invalid' },
    __codexExtensionConfig: { documentId: 'document-1', theme: 'light', fontScale: 1, themeCss: ':root {}' },
    CodexExtensionBridge: {
      getState(id) { assert.equal(id, 'document-1'); return state; },
      setState(id, value) { assert.equal(id, 'document-1'); stored.push(value); state = value; },
      postMessage(id, value) { assert.equal(id, 'document-1'); sent.push(JSON.parse(value)); },
      ready(id) { ready.push(id); },
    },
    document: { documentElement: node, head: { appendChild() {} }, body: node,
      getElementById() { return node; }, readyState: 'complete' },
    MutationObserver: class { observe() {} disconnect() {} },
    MessageEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    dispatchEvent(event) { events.push(event); },
  });
  vm.runInContext('window = globalThis', context);
  vm.runInContext(source, context);
  return { context, sent, events, ready, stored, run: (code) => vm.runInContext(code, context) };
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
