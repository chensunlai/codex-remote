import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";
import { afterEach, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { createHost, Uri } = require("./extension-host/vscode.cjs");
const cleanups: Array<() => Promise<void>> = [];
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup(); });

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "public-host-test-"));
  const extensionDirectory = join(root, "extension"), stateDirectory = join(root, "state");
  await mkdir(extensionDirectory); await mkdir(stateDirectory);
  await writeFile(join(extensionDirectory, "package.json"), JSON.stringify({ publisher: "Anthropic", name: "claude-code", version: "1.0.0",
    contributes: { menus: { "editor/title": [
      { command: "claude-vscode.acceptProposedDiff", when: "claude-vscode.viewingProposedDiff" },
      { command: "claude-vscode.rejectProposedDiff", when: "claude-vscode.viewingProposedDiff" },
    ] } } }));
  const events: any[] = [];
  const host = createHost({ extensionDirectory, stateDirectory, workspace: root, origin: "https://test.invalid" }, (event: any) => events.push(event));
  cleanups.push(async () => { host.dispose(); await new Promise(resolve => setTimeout(resolve, 10)); await rm(root, { recursive: true, force: true }); });
  const api = host.api;
  const bytes = new Map<string, Uint8Array>();
  const changes = new api.EventEmitter();
  api.workspace.registerFileSystemProvider("test-proposed", {
    onDidChangeFile: changes.event,
    stat: (uri: any) => ({ type: 1, size: bytes.get(uri.toString())!.byteLength }),
    readFile: (uri: any) => bytes.get(uri.toString()),
  });
  api.window.onDidChangeVisibleTextEditors((editors: any[]) => {
    void api.commands.executeCommand("setContext", "claude-vscode.viewingProposedDiff", editors.some(e => e.document.uri.scheme === "test-proposed"));
  });
  const decisions: string[] = [];
  for (const action of ["accept", "reject"]) api.commands.registerCommand(`claude-vscode.${action}ProposedDiff`, () => {
    expect(api.window.tabGroups.activeTabGroup.activeTab.input).toBeInstanceOf(api.TabInputTextDiff);
    decisions.push(action);
  });
  const original = Uri.file(join(root, "原文件.txt"));
  const modified = Uri.from({ scheme: "test-proposed", path: "/原文件.txt" });
  await writeFile(original.fsPath, "before 中文🙂\n");
  bytes.set(modified.toString(), Buffer.from("after 中文🙂\n"));
  const open = () => api.commands.executeCommand("vscode.diff", original, modified, "Proposed edit", { preserveFocus: true });
  return { root, host, api, events, original, modified, bytes, changes, decisions, open };
}

it("opens a real public diff tab before resolving, approves through the registered command, and never writes the source", async () => {
  const f = await fixture();
  await f.open();
  const event = f.events.find(e => e.type === "surface" && e.payload.action === "open");
  expect(event.payload).toMatchObject({ kind: "diff", actions: ["accept", "reject"], pending: false,
    diff: { originalText: "before 中文🙂\n", modifiedText: "after 中文🙂\n" } });
  expect(f.api.window.tabGroups.all.flatMap((g: any) => g.tabs)).toHaveLength(1);
  await f.host.surfaceAction(event.surfaceId, "accept");
  expect(f.decisions).toEqual(["accept"]);
  expect(f.events.at(-1).payload).toMatchObject({ pending: true });
  expect(f.events.at(-1).payload.diff).toBeUndefined();
  expect(f.api.window.tabGroups.all[0].tabs).toHaveLength(1); // Official callback owns final close.
  await expect(f.host.surfaceAction(event.surfaceId, "accept")).rejects.toThrow(/no longer/);
  await f.api.window.tabGroups.close(f.api.window.tabGroups.activeTabGroup.activeTab);
  expect(f.events.at(-1).payload.action).toBe("close");
  expect(await readFile(f.original.fsPath, "utf8")).toBe("before 中文🙂\n");
});

it("native close invokes official rejection while backgrounding leaves approval pending", async () => {
  const f = await fixture(); await f.open();
  const id = f.events.find(e => e.payload?.kind === "diff").surfaceId;
  f.host.visibility(false);
  expect(f.decisions).toEqual([]);
  expect(f.api.window.tabGroups.all[0].tabs).toHaveLength(1);
  await f.host.closeSurface(id);
  expect(f.decisions).toEqual(["reject"]);
  expect(f.api.window.tabGroups.all[0].tabs).toHaveLength(1);
});

it("refreshes provider-backed documents and previews instead of reusing stale contents", async () => {
  const f = await fixture(); await f.open();
  const doc = await f.api.workspace.openTextDocument(f.modified);
  const updates: string[] = [];
  f.api.workspace.onDidChangeTextDocument((e: any) => updates.push(e.document.getText()));
  f.bytes.set(f.modified.toString(), Buffer.from("replacement\n"));
  f.changes.fire([{ type: 1, uri: f.modified }]);
  await new Promise(resolve => setTimeout(resolve, 10));
  expect(doc.getText()).toBe("replacement\n");
  expect(updates).toEqual(["replacement\n"]);
  expect(f.events.filter(e => e.payload?.action === "update").at(-1).payload.diff.modifiedText).toBe("replacement\n");
});

it("binds decisions to a tab and rejects stale or non-approval actions", async () => {
  const f = await fixture(); await f.open();
  const first = f.api.window.tabGroups.activeTabGroup.activeTab;
  await f.open();
  await f.host.surfaceAction(first.id, "reject");
  expect(f.api.window.tabGroups.activeTabGroup.activeTab).toBe(first);
  await f.api.window.tabGroups.close(first);
  await expect(f.host.surfaceAction(first.id, "accept")).rejects.toThrow(/no longer/);
  const second = f.api.window.tabGroups.activeTabGroup.activeTab;
  await expect(f.host.surfaceAction(second.id, "executeAnything")).rejects.toThrow(/Unsupported/);
  expect(f.decisions).toEqual(["reject"]);
});

it("resolves content providers as read-only text and refuses editor writes", async () => {
  const f = await fixture();
  f.api.workspace.registerTextDocumentContentProvider("read-only", { provideTextDocumentContent: () => "Preview 🙂" });
  const doc = await f.api.workspace.openTextDocument(Uri.parse("read-only:/proposal.md"));
  expect(doc.getText()).toBe("Preview 🙂");
  expect(doc.isDirty).toBe(false);
  expect(await f.api.workspace.applyEdit({})).toBe(false);
  await expect(f.api.workspace.fs.readFile(Uri.parse("unknown:/missing"))).rejects.toThrow(/provider/);
});

it.each(["download.apk", "download.exe", "图片.png"])("opens %s through the native file viewer without rejecting its binary content", async filename => {
  const f = await fixture();
  const uri = Uri.file(join(f.root, filename));
  const bytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0xfe, 0x80]);
  await writeFile(uri.fsPath, bytes);
  // This is the public API sequence used by Codex's file-link handler.
  const document = await f.api.workspace.openTextDocument(uri);
  const opened = f.api.window.showTextDocument(document);
  const request = f.events.at(-1).payload;
  expect(request).toMatchObject({ method: "openFile", params: { path: uri.fsPath } });
  expect(request.params).not.toHaveProperty("text");
  f.host.respond({ id: request.id, result: true });
  expect((await opened).document).toBe(document);
  expect(await readFile(uri.fsPath)).toEqual(bytes);
});

it("continues to reject non-text virtual documents used for diff previews", async () => {
  const f = await fixture();
  f.bytes.set(f.modified.toString(), Buffer.from([0xff, 0xfe, 0x80]));
  await expect(f.api.workspace.openTextDocument(f.modified)).rejects.toThrow(/encoded data/);
  expect(f.events.some(event => event.type === "host-request")).toBe(false);
});

it("maps quick pick indices to original objects and validates input on the host", async () => {
  const f = await fixture();
  const item = { label: "Workspace", privateValue: () => 1 };
  const picked = f.api.window.showQuickPick([item], { title: "Choose" });
  await Promise.resolve();
  let request = f.events.at(-1).payload;
  expect(request.params.items).toEqual([{ label: "Workspace", description: undefined, detail: undefined, picked: undefined, kind: undefined }]);
  f.host.respond({ id: request.id, result: 0 });
  expect(await picked).toBe(item);
  const input = f.api.window.showInputBox({ value: "", validateInput: (value: string) => value ? undefined : "Required" });
  request = f.events.at(-1).payload; f.host.respond({ id: request.id, result: "" });
  await new Promise(resolve => setTimeout(resolve, 0));
  request = f.events.at(-1).payload;
  expect(request.params.validationMessage).toBe("Required");
  f.host.respond({ id: request.id, result: "Valid" });
  expect(await input).toBe("Valid");
});

it("discards stale provider refreshes that finish after a newer proposal", async () => {
  const f = await fixture();
  const changed = new f.api.EventEmitter();
  const pending: Array<Promise<Buffer>> = [];
  f.api.workspace.registerFileSystemProvider("slow-preview", {
    onDidChangeFile: changed.event,
    stat: () => ({ type: 1, size: 100 }),
    readFile: () => pending.shift() ?? Buffer.from("initial\n"),
  });
  const uri = Uri.parse("slow-preview:/file.txt");
  const document = await f.api.workspace.openTextDocument(uri);
  let old!: (value: Buffer) => void, latest!: (value: Buffer) => void;
  pending.push(new Promise(resolve => old = resolve), new Promise(resolve => latest = resolve));
  changed.fire([{ type: 1, uri }]);
  changed.fire([{ type: 1, uri }]);
  await new Promise(resolve => setTimeout(resolve, 0));
  latest(Buffer.from("current proposal\n"));
  await new Promise(resolve => setTimeout(resolve, 0));
  old(Buffer.from("outdated proposal\n"));
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(document.getText()).toBe("current proposal\n");
});

it("merges concurrent initial opens and retries if the provider changes during their read", async () => {
  const f = await fixture();
  const changed = new f.api.EventEmitter();
  let resolveOld!: (value: Buffer) => void, reads = 0;
  f.api.workspace.registerFileSystemProvider("opening-preview", {
    onDidChangeFile: changed.event,
    stat: () => ({ type: 1, size: 100 }),
    readFile: () => ++reads === 1 ? new Promise<Buffer>(resolve => resolveOld = resolve) : Buffer.from("current\n"),
  });
  const uri = Uri.parse("opening-preview:/file.txt");
  const first = f.api.workspace.openTextDocument(uri), second = f.api.workspace.openTextDocument(uri);
  await new Promise(resolve => setTimeout(resolve, 0));
  changed.fire([{ type: 1, uri }]);
  resolveOld(Buffer.from("outdated\n"));
  const document = await first;
  expect(await second).toBe(document);
  expect(document.getText()).toBe("current\n");
  expect(await f.api.workspace.openTextDocument(uri)).toBe(document);
  expect(reads).toBe(2);
});
