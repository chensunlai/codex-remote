'use strict';

// Public VS Code document/provider/tab semantics for native read-only previews.
// Approval is dispatched through commands contributed by the extension. This
// module never edits project files or constructs an AI permission response.
const { randomUUID } = require('node:crypto');

function createEditorHost({ Uri, Position, Range, Selection, Disposable, EventEmitter, fileSystem, send, commands, contexts, manifest }) {
  class TabInputTextDiff { constructor(original, modified) { this.original = original; this.modified = modified; } }
  class TabInputWebview { constructor(viewType) { this.viewType = viewType; } }
  const fileProviders = new Map(), contentProviders = new Map(), documents = new Map(), openingDocuments = new Map(), previews = new Map();
  const opened = new EventEmitter(), closed = new EventEmitter(), changed = new EventEmitter();
  const willSave = new EventEmitter(), saved = new EventEmitter(), visibleChanged = new EventEmitter(), activeChanged = new EventEmitter();
  const tabsChanged = new EventEmitter(), groupsChanged = new EventEmitter();
  const group = { tabs: [], activeTab: undefined, isActive: true, viewColumn: 1 };
  let visibleTextEditors = [], activeTextEditor, disposed = false;
  const subscriptions = [];
  const maxDocumentBytes = 10 * 1024 * 1024;

  function provider(uri) {
    const value = fileProviders.get(uri.scheme)?.provider;
    if (!value) throw new Error('No file system provider for ' + uri.scheme);
    return value;
  }
  const fs = Object.fromEntries(Object.keys(fileSystem).map(method => [method, async (...args) => {
    const uri = args[0];
    if (uri.scheme === 'file') return fileSystem[method](...args);
    const entry = fileProviders.get(uri.scheme);
    if (!['stat', 'readFile', 'readDirectory'].includes(method) && entry?.options.isReadonly) throw new Error('File system is read-only');
    const target = provider(uri);
    if (typeof target[method] !== 'function') throw new Error('File operation is unavailable: ' + method);
    return target[method](...args);
  }]));
  async function readText(uri) {
    const contentProvider = contentProviders.get(uri.scheme)?.provider;
    if (contentProvider) {
      const value = await contentProvider.provideTextDocumentContent(uri, { isCancellationRequested: false });
      if (typeof value !== 'string' || Buffer.byteLength(value) > maxDocumentBytes) throw new Error('Text preview exceeds 10 MiB');
      return value;
    }
    const metadata = await fs.stat(uri);
    if (metadata.size > maxDocumentBytes) throw new Error('Text preview exceeds 10 MiB');
    const bytes = await fs.readFile(uri);
    if (bytes.byteLength > maxDocumentBytes) throw new Error('Text preview exceeds 10 MiB');
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  }
  function makeDocument(uri, initialText) {
    let text = initialText, lines, offsets;
    const index = () => {
      lines = text.split(/\r?\n/); offsets = [0];
      for (let i = 0; i < text.length; i++) if (text[i] === '\n') offsets.push(i + 1);
    };
    index();
    const offsetAt = p => Math.min(text.length, (offsets[Math.max(0, Math.min(p.line, offsets.length - 1))] || 0) + Math.max(0, p.character));
    const positionAt = value => {
      const offset = Math.max(0, Math.min(value, text.length));
      let line = 0; while (line + 1 < offsets.length && offsets[line + 1] <= offset) line++;
      return new Position(line, offset - offsets[line]);
    };
    const document = {
      uri, fileName: uri.fsPath, isUntitled: false, isDirty: false, isClosed: false, version: 1, languageId: 'plaintext',
      get eol() { return text.includes('\r\n') ? 2 : 1; }, get lineCount() { return lines.length; },
      getText: range => range ? text.slice(offsetAt(range.start), offsetAt(range.end)) : text,
      offsetAt, positionAt, validatePosition: p => positionAt(offsetAt(p)),
      validateRange: r => new Range(positionAt(offsetAt(r.start)), positionAt(offsetAt(r.end))),
      save: async () => !document.isClosed,
      lineAt(value) {
        const line = typeof value === 'number' ? value : value.line, content = lines[line];
        if (content === undefined) throw new RangeError('Invalid document line');
        return { lineNumber: line, text: content, range: new Range(line, 0, line, content.length),
          rangeIncludingLineBreak: line + 1 < lines.length ? new Range(line, 0, line + 1, 0) : new Range(line, 0, line, content.length),
          firstNonWhitespaceCharacterIndex: content.search(/\S|$/), isEmptyOrWhitespace: !content.trim() };
      },
    };
    return { document, refreshVersion: 0, update(next) {
      if (document.isClosed || next === text) return;
      const range = new Range(new Position(0, 0), positionAt(text.length)), rangeLength = text.length;
      text = next; index(); document.version++;
      changed.fire({ document, contentChanges: [{ range, rangeOffset: 0, rangeLength, text: next }] });
    } };
  }
  async function openTextDocument(input) {
    const uri = typeof input === 'string' ? Uri.file(input) : input;
    if (!(uri instanceof Uri)) throw new Error('A document URI is required');
    const key = uri.toString();
    // Like VS Code, reopening an already open document returns that document.
    // Provider change events own subsequent refreshes; overlapping opens share
    // their initial read so an older read cannot replace newer document text.
    if (documents.has(key)) return documents.get(key).document;
    if (openingDocuments.has(key)) return openingDocuments.get(key).promise;
    const pending = { version: 0 };
    openingDocuments.set(key, pending);
    pending.promise = Promise.resolve().then(async () => {
      for (;;) {
        const version = pending.version, text = await readText(uri);
        if (disposed) throw new Error('Extension host is closed');
        if (version !== pending.version) continue;
        const value = makeDocument(uri, text);
        documents.set(key, value); opened.fire(value.document); return value.document;
      }
    }).finally(() => { if (openingDocuments.get(key) === pending) openingDocuments.delete(key); });
    return pending.promise;
  }
  async function refresh(uri) {
    const entry = documents.get(uri.toString());
    if (!entry) {
      const pending = openingDocuments.get(uri.toString());
      if (pending) pending.version++;
      return;
    }
    const version = ++entry.refreshVersion;
    try {
      const text = await readText(uri);
      if (documents.get(uri.toString()) !== entry || entry.refreshVersion !== version) return;
      entry.update(text);
      for (const preview of previews.values()) if ([preview.originalUri, preview.modifiedUri].includes(uri.toString())) describe(preview, 'update');
    } catch (error) { console.warn('Unable to refresh read-only document: ' + error.message); }
  }
  function registerProvider(target, scheme, value, options = {}) {
    if (scheme === 'file' || target.has(scheme)) throw new Error('File system provider already exists: ' + scheme);
    const listener = (target === fileProviders ? value.onDidChangeFile : value.onDidChange)?.(event => {
      for (const uri of target === fileProviders ? event.map(item => item.uri) : [event]) void refresh(uri);
    });
    const entry = { provider: value, options }; target.set(scheme, entry);
    const registration = new Disposable(() => { listener?.dispose(); if (target.get(scheme) === entry) target.delete(scheme); });
    subscriptions.push(registration); return registration;
  }
  function makeEditor(document) {
    return { document, selection: new Selection(0, 0, 0, 0), selections: [], visibleRanges: [], options: {}, viewColumn: 1,
      edit: async () => false, insertSnippet: async () => false, revealRange() {}, setDecorations() {} };
  }
  function focus(tab) {
    if (!group.tabs.includes(tab)) throw new Error('Preview is no longer available');
    for (const value of group.tabs) value.isActive = value === tab;
    group.activeTab = tab;
    const preview = previews.get(tab.id);
    visibleTextEditors = preview?.editors ?? [];
    activeTextEditor = visibleTextEditors.at(-1);
    visibleChanged.fire(visibleTextEditors); activeChanged.fire(activeTextEditor);
    tabsChanged.fire({ opened: [], changed: [tab], closed: [] });
  }
  function actionCommand(action) {
    const suffix = action === 'accept' ? '.acceptProposedDiff' : '.rejectProposedDiff';
    const item = manifest.contributes?.menus?.['editor/title']?.find(entry =>
      typeof entry.command === 'string' && entry.command.endsWith(suffix) && commands.has(entry.command) &&
      (entry.when === undefined || contexts.get(entry.when) === true));
    return item?.command;
  }
  function describe(preview, action, includeDiff = true) {
    send({ type: 'surface', surfaceId: preview.id, payload: {
      action, id: preview.id, title: preview.tab.label, kind: 'diff', pending: preview.pending,
      actions: ['accept', 'reject'].filter(value => actionCommand(value)),
      ...(includeDiff ? { diff: { originalUri: preview.originalUri, modifiedUri: preview.modifiedUri,
        originalText: preview.editors[0].document.getText(), modifiedText: preview.editors[1].document.getText() } } : {}),
    } });
  }
  async function openDiff(original, modified, title = 'Diff') {
    const docs = await Promise.all([openTextDocument(original), openTextDocument(modified)]);
    if (disposed) throw new Error('Extension host is closed');
    const id = 'diff-' + randomUUID();
    const tab = { id, label: title, input: new TabInputTextDiff(original, modified), group, isActive: false, isDirty: false, isPinned: true, isPreview: false };
    const preview = { id, tab, originalUri: original.toString(), modifiedUri: modified.toString(), editors: docs.map(makeEditor), pending: false };
    previews.set(id, preview); group.tabs.push(tab); focus(tab);
    tabsChanged.fire({ opened: [tab], changed: [], closed: [] });
    describe(preview, 'open');
    // The extension locates the tab after this command resolves, then awaits its
    // own approval events. Resolving only on user input would deadlock that flow.
  }
  async function closeTabs(input) {
    for (const tab of Array.isArray(input) ? input : [input]) {
      if (!group.tabs.includes(tab)) continue;
      group.tabs.splice(group.tabs.indexOf(tab), 1);
      const preview = previews.get(tab.id);
      previews.delete(tab.id);
      tab.isActive = false;
      if (preview) send({ type: 'surface', surfaceId: preview.id, payload: { action: 'close', id: preview.id, title: tab.label, kind: 'diff' } });
      if (group.activeTab === tab) {
        group.activeTab = undefined; visibleTextEditors = []; activeTextEditor = undefined;
        const next = group.tabs.at(-1);
        if (next) focus(next); else { visibleChanged.fire([]); activeChanged.fire(undefined); }
      }
      tabsChanged.fire({ opened: [], changed: [], closed: [tab] });
      if (preview) for (const editor of preview.editors) {
        const uri = editor.document.uri;
        if ([...previews.values()].some(value => value.editors.some(item => item.document.uri.toString() === uri.toString()))) continue;
        const entry = documents.get(uri.toString());
        if (entry) { documents.delete(uri.toString()); entry.document.isClosed = true; closed.fire(entry.document); }
      }
    }
    return true;
  }
  async function action(id, name) {
    if (!['accept', 'reject'].includes(name)) throw new Error('Unsupported preview action');
    const preview = previews.get(id);
    if (!preview || preview.pending) throw new Error('Preview is no longer awaiting a decision');
    focus(preview.tab);
    const command = actionCommand(name);
    if (!command) {
      if (name === 'reject') return closeTabs(preview.tab);
      throw new Error('This preview does not offer an approval command');
    }
    preview.pending = true; describe(preview, 'update', false);
    try { await commands.get(command)(); }
    catch (error) { if (previews.has(id)) { preview.pending = false; describe(preview, 'update', false); } throw error; }
    // The official extension closes its own tab after consuming the command.
    // Do not remove it here: its initial 100 ms discovery poll may still run.
  }
  return {
    fs, openTextDocument, openDiff, action, hasPreview: id => previews.has(id),
    TabInputTextDiff, TabInputWebview,
    registerFileSystemProvider: (scheme, value, options) => registerProvider(fileProviders, scheme, value, options),
    registerTextDocumentContentProvider: (scheme, value) => registerProvider(contentProviders, scheme, value),
    get textDocuments() { return [...documents.values()].map(value => value.document); },
    get visibleTextEditors() { return visibleTextEditors; }, get activeTextEditor() { return activeTextEditor; },
    events: { opened, closed, changed, willSave, saved, visibleChanged, activeChanged },
    tabGroups: { all: [group], activeTabGroup: group, close: closeTabs, onDidChangeTabs: tabsChanged.event, onDidChangeTabGroups: groupsChanged.event },
    dispose() {
      disposed = true; void closeTabs([...group.tabs]);
      subscriptions.splice(0).forEach(value => value.dispose());
      for (const event of [opened, closed, changed, willSave, saved, visibleChanged, activeChanged, tabsChanged, groupsChanged]) event.dispose();
    },
  };
}

module.exports = { createEditorHost };
