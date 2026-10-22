'use strict';

// This implements the documented extension-host boundary. Plugin messages and
// plugin-owned storage values are deliberately opaque here.
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { fileURLToPath, pathToFileURL } = require('node:url');
const { randomUUID } = require('node:crypto');
const { AsyncLocalStorage } = require('node:async_hooks');
const { createEditorHost } = require('./editor.cjs');

class Disposable {
  constructor(callback = () => {}) { this.callback = callback; }
  dispose() { const callback = this.callback; this.callback = () => {}; callback(); }
  static from(...items) { return new Disposable(() => items.forEach(item => item.dispose())); }
}
class EventEmitter {
  constructor() {
    this.listeners = new Set();
    this.event = (listener, thisArg, disposables) => {
      const callback = thisArg ? listener.bind(thisArg) : listener;
      this.listeners.add(callback);
      const disposable = new Disposable(() => this.listeners.delete(callback));
      disposables?.push(disposable);
      return disposable;
    };
  }
  fire(value) { for (const listener of [...this.listeners]) { try { listener(value); } catch (error) { console.error(error); } } }
  dispose() { this.listeners.clear(); }
}
class Uri {
  constructor(scheme, authority, pathname, query = '', fragment = '') {
    this.scheme = scheme; this.authority = authority; this.path = pathname; this.query = query; this.fragment = fragment;
  }
  static parse(value) {
    const url = new URL(value);
    return new Uri(url.protocol.slice(0, -1), url.host, decodeURIComponent(url.pathname), url.search.slice(1), url.hash.slice(1));
  }
  static file(value) { return Uri.parse(pathToFileURL(path.resolve(value)).href); }
  static from(value) { return new Uri(value.scheme, value.authority || '', value.path || '', value.query || '', value.fragment || ''); }
  static joinPath(base, ...parts) { return base.with({ path: path.posix.join(base.path, ...parts) }); }
  static revive(value) { return typeof value === 'string' ? Uri.parse(value) : Uri.from(value); }
  get fsPath() { return this.scheme === 'file' ? fileURLToPath(this.toString()) : this.path; }
  with(change) { return Uri.from({ ...this, ...change }); }
  toString(skipEncoding = false) {
    let pathname = skipEncoding ? this.path : this.path.split('/').map(encodeURIComponent).join('/');
    return this.scheme + ':' + (this.authority || this.scheme === 'file' ? '//' + this.authority : '') + pathname +
      (this.query ? '?' + this.query : '') + (this.fragment ? '#' + this.fragment : '');
  }
  toJSON() { return { scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment }; }
}
class Position { constructor(line, character) { this.line = line; this.character = character; } }
class Range {
  constructor(a, b, c, d) { this.start = typeof a === 'number' ? new Position(a, b) : a; this.end = typeof a === 'number' ? new Position(c, d) : b; }
}
class Selection extends Range { constructor(a, b, c, d) { super(a, b, c, d); this.anchor = this.start; this.active = this.end; this.isEmpty = this.start.line === this.end.line && this.start.character === this.end.character; } }
class RelativePattern { constructor(base, pattern) { this.baseUri = typeof base === 'string' ? Uri.file(base) : base.uri || base; this.pattern = pattern; } }
class CodeLens { constructor(range, command) { this.range = range; this.command = command; } }
class TabInputText { constructor(uri) { this.uri = uri; } }
class TabInputCustom { constructor(uri, viewType) { this.uri = uri; this.viewType = viewType; } }
class MarkdownString { constructor(value = '') { this.value = value; } appendText(value) { this.value += value; return this; } appendMarkdown(value) { this.value += value; return this; } }
class ThemeColor { constructor(id) { this.id = id; } }
class ThemeIcon { constructor(id, color) { this.id = id; this.color = color; } }

function memento(filename) {
  let values = {};
  let pending = Promise.resolve();
  try { values = JSON.parse(fs.readFileSync(filename, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  return {
    get(key, fallback) { return Object.hasOwn(values, key) ? values[key] : fallback; },
    keys() { return Object.keys(values); }, setKeysForSync() {},
    update(key, value) {
      if (value === undefined) delete values[key]; else values[key] = value;
      const operation = pending.then(async () => {
        const stage = filename + '.' + randomUUID() + '.tmp';
        await fsp.writeFile(stage, JSON.stringify(values), { mode: 0o600 });
        await fsp.rename(stage, filename);
      });
      pending = operation.catch(() => {});
      return operation;
    },
  };
}

function createHost(options, send) {
  const configChanged = new EventEmitter();
  const windowChanged = new EventEmitter();
  const views = new Map();
  const providers = new Map();
  const commands = new Map();
  const contexts = new Map();
  const panelSerializers = new Map();
  const hostRequests = new Map();
  const fileWatchers = new Set();
  const surfaceContext = new AsyncLocalStorage();
  const settingsPath = path.join(options.stateDirectory, 'configuration.json');
  const configuration = memento(settingsPath);
  const subscriptions = [];
  const manifest = JSON.parse(fs.readFileSync(path.join(options.extensionDirectory, 'package.json'), 'utf8'));
  const defaults = Object.fromEntries(Object.entries(manifest.contributes?.configuration?.properties || {}).map(([key, value]) => [key, value.default]));
  const extensionUri = Uri.file(options.extensionDirectory);
  const workspaceUri = Uri.file(options.workspace);
  const workspaceFolder = { uri: workspaceUri, name: path.basename(options.workspace), index: 0 };
  const extension = { id: manifest.publisher + '.' + manifest.name, extensionUri, extensionPath: options.extensionDirectory, packageJSON: manifest, isActive: true, exports: undefined, activate: async () => extension.exports };
  let windowVisible = true;

  function ask(method, params) {
    const id = randomUUID();
    const surfaceId = surfaceContext.getStore() || 'main';
    return new Promise((resolve, reject) => {
      hostRequests.set(id, { resolve, reject, surfaceId });
      send({ type: 'host-request', surfaceId, payload: { id, method, params } });
    });
  }
  function makeWebview(id, kind = 'sidebar') {
    const received = new EventEmitter(), disposed = new EventEmitter(), changed = new EventEmitter();
    const surfaceId = kind === 'sidebar' ? 'main' : 'panel-' + randomUUID();
    let html = '', title = manifest.displayName || 'Codex', isDisposed = false, surfaceVisible = true;
    const describe = action => send({ type: 'surface', surfaceId, payload: { action, id: surfaceId, title, kind: 'panel' } });
    const webview = {
      options: {}, cspSource: options.origin,
      asWebviewUri(uri) {
        const relative = path.relative(options.extensionDirectory, uri.fsPath);
        if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('WebView resource is outside the extension');
        return Uri.parse(options.origin + '/extension/' + encodeURIComponent(manifest.version) + '/' + relative.split(path.sep).map(encodeURIComponent).join('/'));
      },
      get html() { return html; }, set html(value) {
        if (isDisposed) throw new Error('WebView is disposed');
        html = value; send({ type: 'html', surfaceId, payload: { html: value, origin: options.origin, title, kind } });
      },
      postMessage(message) { if (isDisposed) return Promise.resolve(false); send({ type: 'message', surfaceId, message }); return Promise.resolve(true); },
      onDidReceiveMessage: received.event,
    };
    const view = {
      webview, viewType: id, surfaceId, description: '', badge: undefined,
      get title() { return title; }, set title(value) { title = value; if (kind === 'panel') describe('update'); },
      get visible() { return windowVisible && surfaceVisible; }, active: true,
      onDidDispose: disposed.event, onDidChangeVisibility: changed.event, onDidChangeViewState: changed.event,
      show() {
        if (kind === 'panel') for (const other of views.values()) if (other !== this && other.surfaceId !== 'main') other.setPanelVisible(false);
        surfaceVisible = true; this.active = true; this.windowVisibilityChanged(); if (kind === 'panel') describe('update');
      }, reveal() { this.show(); },
      dispose() {
        if (isDisposed) return;
        isDisposed = true;
        disposed.fire(undefined); received.dispose(); disposed.dispose(); changed.dispose(); views.delete(id);
        for (const [requestId, pending] of hostRequests) if (pending.surfaceId === surfaceId) { hostRequests.delete(requestId); pending.resolve(undefined); }
        if (kind === 'panel') {
          describe('close');
          const remaining = [...views.values()];
          (remaining.filter(item => item.surfaceId !== 'main').at(-1) || remaining.find(item => item.surfaceId === 'main'))?.show();
        }
      },
      receive(value) { surfaceContext.run(surfaceId, () => received.fire(value)); },
      setPanelVisible(value) { surfaceVisible = value; this.active = value; this.windowVisibilityChanged(); },
      windowVisibilityChanged() { changed.fire(kind === 'panel' ? { webviewPanel: this } : undefined); },
    };
    views.set(id, view);
    if (kind === 'panel') { for (const other of views.values()) if (other !== view && other.surfaceId !== 'main') other.setPanelVisible(false); describe('open'); }
    return view;
  }
  const fileSystem = {
    async readFile(uri) { return new Uint8Array(await fsp.readFile(uri.fsPath)); },
    async writeFile(uri, bytes) { await fsp.writeFile(uri.fsPath, bytes); },
    async stat(uri) { const value = await fsp.stat(uri.fsPath); return { type: value.isDirectory() ? 2 : value.isFile() ? 1 : 0, ctime: value.ctimeMs, mtime: value.mtimeMs, size: value.size }; },
    async readDirectory(uri) { return (await fsp.readdir(uri.fsPath, { withFileTypes: true })).map(entry => [entry.name, entry.isDirectory() ? 2 : entry.isFile() ? 1 : entry.isSymbolicLink() ? 64 : 0]); },
    async createDirectory(uri) { await fsp.mkdir(uri.fsPath, { recursive: true }); },
    async delete(uri, opts = {}) { await fsp.rm(uri.fsPath, { recursive: !!opts.recursive, force: false }); },
    async rename(source, target, opts = {}) { if (!opts.overwrite && fs.existsSync(target.fsPath)) throw new Error('Destination exists'); await fsp.rename(source.fsPath, target.fsPath); },
    async copy(source, target, opts = {}) { await fsp.cp(source.fsPath, target.fsPath, { recursive: true, force: !!opts.overwrite, errorOnExist: !opts.overwrite }); },
  };
  const editor = createEditorHost({ Uri, Position, Range, Selection, Disposable, EventEmitter, fileSystem, send, commands, contexts, manifest });
  const openTextDocument = editor.openTextDocument;
  function noEvent() { return new Disposable(); }
  async function message(method, text, ...items) {
    const actions = items.filter(item => typeof item === 'string' || item?.title);
    const chosen = await ask(method, { message: text, items: actions, options: items.find(item => typeof item === 'object' && !item?.title) });
    return actions.find(item => (typeof item === 'string' ? item : item.title) === chosen);
  }
  const dialogOptions = opts => ({ ...opts, ...(opts?.defaultUri ? { defaultUri: opts.defaultUri.toString() } : {}) });
  function outputChannel(name) {
    const log = (level, value) => send({ type: 'log', level, message: '[' + name + '] ' + String(value).slice(0, 8192) });
    return { name, append: value => log('info', value), appendLine: value => log('info', value), replace: value => log('info', value),
      clear() {}, show() {}, hide() {}, trace() {}, debug() {}, info: value => log('info', value), warn: value => log('warn', value), error: value => log('error', value), dispose() {} };
  }
  async function showInputBox(opts = {}, token) {
    if (token?.isCancellationRequested) return undefined;
    let validationMessage;
    for (;;) {
      const value = await ask('showInputBox', { title: opts.title, prompt: opts.prompt, placeHolder: opts.placeHolder,
        value: opts.value, password: !!opts.password, valueSelection: opts.valueSelection, validationMessage });
      if (typeof value !== 'string' || token?.isCancellationRequested) return undefined;
      const validation = await opts.validateInput?.(value);
      if (!validation || typeof validation === 'object' && validation.severity !== 3) return value;
      validationMessage = typeof validation === 'string' ? validation : validation.message;
      opts = { ...opts, value };
    }
  }
  async function showQuickPick(input, opts = {}, token) {
    const items = await input;
    if (token?.isCancellationRequested) return undefined;
    const result = await ask('showQuickPick', { title: opts.title, placeHolder: opts.placeHolder, canPickMany: !!opts.canPickMany,
      items: items.map(item => typeof item === 'string' ? { label: item } :
        { label: item.label, description: item.description, detail: item.detail, picked: item.picked, kind: item.kind }) });
    const at = index => Number.isSafeInteger(index) && index >= 0 && index < items.length ? items[index] : undefined;
    if (token?.isCancellationRequested) return undefined;
    return opts.canPickMany ? Array.isArray(result) ? result.map(at).filter(value => value !== undefined) : undefined : at(result);
  }
  const api = {
    version: '1.96.2', Disposable, EventEmitter, Uri, Position, Range, Selection, RelativePattern, CodeLens, TabInputText, TabInputCustom, MarkdownString, ThemeColor, ThemeIcon,
    TabInputTextDiff: editor.TabInputTextDiff, TabInputWebview: editor.TabInputWebview,
    ExtensionMode: { Production: 1, Development: 2, Test: 3 }, UIKind: { Desktop: 1, Web: 2 },
    StatusBarAlignment: { Left: 1, Right: 2 }, ProgressLocation: { SourceControl: 1, Window: 10, Notification: 15 },
    EndOfLine: { LF: 1, CRLF: 2 }, TextDocumentChangeReason: { Undo: 1, Redo: 2 },
    FileChangeType: { Changed: 1, Created: 2, Deleted: 3 },
    NotebookCellOutputItem: { error(error) { return { mime: 'application/vnd.code.notebook.error', data: new TextEncoder().encode(JSON.stringify({ name: error.name, message: error.message, stack: error.stack })) }; } },
    FileSystemError: Object.fromEntries(['FileNotFound', 'FileExists', 'FileNotADirectory', 'FileIsADirectory', 'NoPermissions', 'Unavailable'].map(code => [code, value => Object.assign(new Error(code + ': ' + String(value ?? '')), { code })])),
    CommentThreadState: { Unresolved: 0, Resolved: 1 }, CommentThreadCollapsibleState: { Collapsed: 0, Expanded: 1 },
    ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 }, FileType: { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2, Hint: 3 },
    ViewColumn: { Active: -1, Beside: -2, One: 1, Two: 2 }, TextEditorRevealType: { Default: 0 },
    SymbolKind: { File: 0, Module: 1, Namespace: 2, Package: 3, Class: 4, Method: 5, Property: 6, Field: 7, Constructor: 8, Function: 11, Variable: 12, Constant: 13 },
    l10n: { t(message, ...args) { const value = typeof message === 'string' ? message : message.message; return value.replace(/\{(\d+)\}/g, (_, n) => String(args[Number(n)] ?? '{' + n + '}')); } },
    env: {
      appName: 'Codex Remote', appRoot: path.dirname(options.extensionDirectory), language: options.language || 'zh-cn', remoteName: undefined,
      uriScheme: 'codex-remote', machineId: options.clientId, sessionId: options.hostId, isTelemetryEnabled: false,
      uiKind: 1, shell: process.env.SHELL || (process.platform === 'win32' ? 'cmd.exe' : '/bin/sh'),
      clipboard: { readText: () => ask('clipboard.read', {}), writeText: text => ask('clipboard.write', { text }).then(() => undefined) },
      openExternal: uri => ask('openExternal', { url: uri.toString() }), onDidChangeTelemetryEnabled: noEvent,
    },
    commands: {
      registerCommand(id, callback, thisArg) { commands.set(id, thisArg ? callback.bind(thisArg) : callback); return new Disposable(() => commands.delete(id)); },
      async executeCommand(id, ...args) {
        if (commands.has(id)) return commands.get(id)(...args);
        if (id === 'setContext') { contexts.set(args[0], args[1]); return undefined; }
        if (id === 'vscode.diff') return editor.openDiff(...args);
        if (id === 'vscode.open') return args[0]?.scheme === 'file' ? ask('openFile', { path: args[0].fsPath }) : ask('openExternal', { url: args[0].toString() });
        if (id === 'vscode.openFolder' || id === 'workbench.action.showCommands') throw new Error('This editor command is unavailable in Codex Remote');
        if (id.endsWith('.focus')) { for (const view of views.values()) view.show(); return undefined; }
        throw new Error('Command is not available: ' + id);
      },
      async getCommands() { return [...commands.keys()]; },
    },
    workspace: {
      name: workspaceFolder.name, rootPath: options.workspace, workspaceFolders: [workspaceFolder], isTrusted: true, fs: editor.fs,
      get textDocuments() { return editor.textDocuments; },
      registerFileSystemProvider: editor.registerFileSystemProvider,
      registerTextDocumentContentProvider: editor.registerTextDocumentContentProvider,
      applyEdit: async () => false,
      getWorkspaceFolder(uri) { const relative = path.relative(options.workspace, uri.fsPath); return !relative.startsWith('..') && !path.isAbsolute(relative) ? workspaceFolder : undefined; },
      asRelativePath(uri) { return path.relative(options.workspace, typeof uri === 'string' ? uri : uri.fsPath); },
      getConfiguration(section) {
        const key = name => section ? section + '.' + name : name;
        const get = (name, fallback) => key(name) === 'chatgpt.cliExecutable' ? options.cliExecutable : configuration.get(key(name), defaults[key(name)] ?? fallback);
        return { get, has: name => get(name) !== undefined,
          inspect: name => ({ key: key(name), defaultValue: defaults[key(name)], globalValue: configuration.get(key(name)) }),
          async update(name, value) { await configuration.update(key(name), value); configChanged.fire({ affectsConfiguration: check => check === key(name) || key(name).startsWith(check + '.') }); },
        };
      },
      onDidChangeConfiguration: configChanged.event, onDidChangeWorkspaceFolders: noEvent,
      onDidChangeTextDocument: editor.events.changed.event, onDidOpenTextDocument: editor.events.opened.event,
      onDidCloseTextDocument: editor.events.closed.event, onWillSaveTextDocument: editor.events.willSave.event,
      onDidSaveTextDocument: editor.events.saved.event,
      createFileSystemWatcher(pattern, ignoreCreateEvents = false, ignoreChangeEvents = false, ignoreDeleteEvents = false) {
        const created = new EventEmitter(), changed = new EventEmitter(), deleted = new EventEmitter();
        const base = pattern?.baseUri?.fsPath || options.workspace;
        let watcher, disposed = false;
        const result = { onDidCreate: created.event, onDidChange: changed.event, onDidDelete: deleted.event,
          dispose() {
            if (disposed) return;
            disposed = true;
            fileWatchers.delete(result);
            watcher?.close(); created.dispose(); changed.dispose(); deleted.dispose();
          } };
        const failed = error => {
          if (disposed) return;
          console.warn('File watcher: ' + error.message);
          result.dispose();
        };
        fileWatchers.add(result);
        try { watcher = fs.watch(base, { recursive: true }, (kind, filename) => {
          if (disposed) return;
          const glob = typeof pattern === 'string' ? pattern : pattern?.pattern;
          if (glob && !path.matchesGlob(String(filename || ''), glob)) return;
          const uri = Uri.file(path.join(base, String(filename || '')));
          if (kind === 'change') { if (!ignoreChangeEvents) changed.fire(uri); }
          else if (fs.existsSync(uri.fsPath)) { if (!ignoreCreateEvents) created.fire(uri); }
          else if (!ignoreDeleteEvents) deleted.fire(uri);
        }); watcher.on('error', failed); } catch (error) { failed(error); }
        return result;
      },
      async findFiles(include, exclude, maxResults, token) {
        const base = include?.baseUri?.fsPath || options.workspace;
        const pattern = typeof include === 'string' ? include : include.pattern;
        const result = [];
        const globOptions = { cwd: base, ...(exclude ? { exclude: [typeof exclude === 'string' ? exclude : exclude.pattern] } : {}) };
        for await (const name of fsp.glob(pattern, globOptions)) {
          if (token?.isCancellationRequested) break;
          result.push(Uri.file(path.join(base, name)));
          if (maxResults !== undefined && result.length >= maxResults) break;
        }
        return result;
      },
      openTextDocument,
    },
    window: {
      get activeTextEditor() { return editor.activeTextEditor; }, get visibleTextEditors() { return editor.visibleTextEditors; },
      state: { focused: true }, tabGroups: editor.tabGroups, terminals: [], activeTerminal: undefined,
      activeColorTheme: { kind: 2 }, onDidChangeActiveColorTheme: noEvent,
      onDidChangeWindowState: windowChanged.event, onDidChangeActiveTextEditor: editor.events.activeChanged.event,
      onDidChangeVisibleTextEditors: editor.events.visibleChanged.event, onDidChangeTextEditorSelection: noEvent,
      onDidChangeTerminalShellIntegration: noEvent, onDidCloseTerminal: noEvent,
      onDidStartTerminalShellExecution: noEvent, onDidEndTerminalShellExecution: noEvent,
      createOutputChannel: outputChannel,
      createStatusBarItem() { return { text: '', tooltip: '', command: undefined, show() {}, hide() {}, dispose() {} }; },
      setStatusBarMessage() { return new Disposable(); },
      registerWebviewViewProvider(id, provider) { providers.set(id, provider); return new Disposable(() => providers.delete(id)); },
      registerWebviewPanelSerializer(id, serializer) { panelSerializers.set(id, serializer); return new Disposable(() => panelSerializers.delete(id)); },
      registerCustomEditorProvider() { return new Disposable(); }, registerUriHandler() { return new Disposable(); },
      createWebviewPanel(id, title, showOptions, webviewOptions) {
        const panel = makeWebview(id + ':' + randomUUID(), 'panel');
        panel.title = title;
        panel.webview.options = webviewOptions || {};
        panel.viewColumn = typeof showOptions === 'number' ? showOptions : showOptions?.viewColumn || 1;
        return panel;
      },
      showOpenDialog: async opts => { const result = await ask('showOpenDialog', dialogOptions(opts || {})); return result?.length ? result.map(value => Uri.parse(value)) : undefined; },
      showSaveDialog: async opts => { const value = await ask('showSaveDialog', dialogOptions(opts || {})); return value ? Uri.parse(value) : undefined; },
      showInputBox, showQuickPick,
      showInformationMessage: (text, ...items) => message('showInformationMessage', text, ...items),
      showWarningMessage: (text, ...items) => message('showWarningMessage', text, ...items),
      showErrorMessage: (text, ...items) => message('showErrorMessage', text, ...items),
      async showTextDocument(input) {
        const document = input?.uri ? input : await openTextDocument(input);
        if (document.uri.scheme === 'file') await ask('openFile', { path: document.uri.fsPath });
        else await ask('openText', { title: path.basename(document.uri.path), text: document.getText() });
        // The native surface is a read-only preview; no editor context or line
        // navigation is exposed back to the extension.
        return { document, selection: new Selection(0, 0, 0, 0), selections: [], visibleRanges: [], options: {}, viewColumn: 1,
          edit: async () => false, insertSnippet: async () => false, revealRange() {}, setDecorations() {} };
      },
      createTextEditorDecorationType() { return new Disposable(); },
      createTerminal() { throw new Error('Extension-created terminals are unavailable; use the App terminal'); },
      async withProgress(_opts, callback) { return callback({ report() {} }, { isCancellationRequested: false, onCancellationRequested: noEvent }); },
    },
    extensions: { getExtension(id) { return id.toLowerCase() === extension.id.toLowerCase() ? extension : undefined; }, all: [extension], onDidChange: noEvent },
    languages: { getDiagnostics: () => [], onDidChangeDiagnostics: noEvent, registerCodeLensProvider: () => new Disposable() },
    comments: { createCommentController(id, label) {
      const threads = new Set();
      return { id, label, createCommentThread(uri, range, comments) {
        const thread = { uri, range, comments, dispose() { threads.delete(thread); } };
        threads.add(thread); return thread;
      }, dispose() { for (const thread of threads) thread.dispose(); } };
    } },
    tasks: { onDidEndTaskProcess: noEvent, executeTask: async () => { throw new Error('Editor tasks are unavailable'); } },
  };
  const context = {
    subscriptions, extension, extensionUri, extensionPath: options.extensionDirectory,
    globalStorageUri: Uri.file(path.join(options.stateDirectory, 'global')), storageUri: Uri.file(path.join(options.stateDirectory, 'workspace')),
    logUri: Uri.file(path.join(options.stateDirectory, 'logs')), extensionMode: 1,
    globalState: memento(path.join(options.stateDirectory, 'global-state.json')), workspaceState: memento(path.join(options.stateDirectory, 'workspace-state.json')),
    asAbsolutePath: relative => path.join(options.extensionDirectory, relative),
    environmentVariableCollection: { persistent: false, replace() {}, append() {}, prepend() {}, delete() {}, clear() {}, forEach() {}, get() { return undefined; }, [Symbol.iterator]: function* () {} },
  };
  for (const uri of [context.globalStorageUri, context.storageUri, context.logUri]) fs.mkdirSync(uri.fsPath, { recursive: true, mode: 0o700 });
  return {
    api, context, extension,
    async resolveView() {
      const id = options.sidebarViewId || (providers.has('chatgpt.sidebarView') ? 'chatgpt.sidebarView' : providers.keys().next().value);
      if (!id) throw new Error('Official extension did not register a WebView provider');
      const view = makeWebview(id);
      await providers.get(id).resolveWebviewView(view, { state: undefined }, { isCancellationRequested: false, onCancellationRequested: noEvent });
      return view;
    },
    receive(message, surfaceId = 'main') { const view = [...views.values()].find(item => item.surfaceId === surfaceId); if (!view) throw new Error('WebView surface is not available'); view.receive(message); },
    closeSurface(surfaceId) { if (surfaceId === 'main') throw new Error('The primary chat view cannot be closed'); if (editor.hasPreview(surfaceId)) return editor.action(surfaceId, 'reject'); const view = [...views.values()].find(item => item.surfaceId === surfaceId); view?.dispose(); },
    surfaceAction(surfaceId, action) { return editor.action(surfaceId, action); },
    visibility(value) { windowVisible = value; for (const view of views.values()) view.windowVisibilityChanged(); api.window.state.focused = value; windowChanged.fire({ focused: value }); },
    respond(value) {
      const pending = hostRequests.get(value.id);
      if (!pending) return;
      hostRequests.delete(value.id);
      if (value.error) pending.reject(new Error(typeof value.error === 'string' ? value.error : value.error.message)); else pending.resolve(value.result);
    },
    dispose() { for (const view of views.values()) view.dispose(); editor.dispose(); for (const item of subscriptions.splice(0).reverse()) { try { item.dispose(); } catch {} } for (const watcher of fileWatchers) watcher.dispose(); for (const pending of hostRequests.values()) pending.resolve(undefined); },
  };
}

module.exports = { createHost, Uri, Disposable, EventEmitter };
