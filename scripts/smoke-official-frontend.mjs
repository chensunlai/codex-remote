import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { readFile, stat, readdir, mkdir, writeFile, mkdtemp, rm } from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import readline from "node:readline";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const option = (name, fallback) => { const i = args.indexOf(`--${name}`); return i < 0 ? fallback : args[i + 1]; };
const directory = path.resolve(option("frontend", path.join(repository, ".data/official-frontends/26.1007.21434")));
const executable = option("codex", "codex");
const version = execFileSync(executable, ["--version"], { encoding: "utf8" }).trim().replace(/^codex-cli\s+/, "");
const { chromium } = await import(process.env.CODEX_PLAYWRIGHT_MODULE || "playwright");
const child = spawn(executable, ["app-server"], { stdio: ["pipe", "pipe", "pipe"] });
const output = readline.createInterface({ input: child.stdout });
let page;
let nativeReady = false;
let initializeResult;
let report;
let reconnectChecks;
let conversationLayout;
const settingsReturnChecks = [];
const attachmentChecks = [];
const attachmentDirectory = args.includes("--attachments") ? await mkdtemp(path.join(os.tmpdir(), "codex-attachments-")) : null;
const pickerRequests = [];
let pickerFiles = [];
if (attachmentDirectory) {
  await writeFile(path.join(attachmentDirectory, "phone-photo.png"), Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWZkAAAAASUVORK5CYII=", "base64"));
  await writeFile(path.join(attachmentDirectory, "phone-notes.txt"), "Phone attachment fixture\n");
}
const early = [];
const logs = [];
const outbound = [];
const requestMethods = new Map();
const createdThreads = new Set();
const cleanupReplies = new Map();
let attachmentInput;
let fixtureTurn;
let fixtureThread;
const fixture = option("fixture", "");
assert.ok(["", "approval", "error", "stream", "resume-conflict"].includes(fixture), "--fixture must be approval, error, stream, or resume-conflict");
async function fixtureMessage(message) {
  await page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), message);
}
async function finishFixture() {
  const item = { id: "fixture-agent-message", type: "agentMessage", text: "CODEX_REMOTE_FIXTURE_OK" + (attachmentDirectory ? `\n\n[预览图片](${path.join(attachmentDirectory, "phone-photo.png")})` : ""), phase: "final_answer" };
  await fixtureMessage({ method: "item/started", params: { threadId: fixtureThread, turnId: fixtureTurn, item: { ...item, text: "" } } });
  await fixtureMessage({ method: "item/agentMessage/delta", params: { threadId: fixtureThread, turnId: fixtureTurn, itemId: item.id, delta: "CODEX_REMOTE_" } });
  await fixtureMessage({ method: "item/agentMessage/delta", params: { threadId: fixtureThread, turnId: fixtureTurn, itemId: item.id, delta: item.text.slice("CODEX_REMOTE_".length) } });
  await fixtureMessage({ method: "item/completed", params: { threadId: fixtureThread, turnId: fixtureTurn, item } });
  await fixtureMessage({ method: "turn/completed", params: { threadId: fixtureThread, turn: { id: fixtureTurn, status: "completed", items: [item], error: null } } });
}
function write(message) { child.stdin.write(JSON.stringify(message) + "\n"); }
output.on("line", line => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  if (cleanupReplies.has(message.id)) { cleanupReplies.get(message.id)(message); cleanupReplies.delete(message.id); return; }
  if (requestMethods.get(message.id) === "thread/start" && message.result?.thread?.id) createdThreads.add(message.result.thread.id);
  if (message.error) logs.push({ type: "rpc-error", id: message.id, error: message.error });
  if (message.id === "smoke-initialize") { initializeResult = message.result; write({ method: "initialized" }); return; }
  if (nativeReady) page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), message).catch(() => {});
  else early.push(message);
});
child.stderr.on("data", data => logs.push({ type: "app-server-stderr", message: data.toString().slice(0, 2000) }));
write({ id: "smoke-initialize", method: "initialize", params: { clientInfo: { name: "codex_vscode", title: "Codex Remote frontend smoke", version: "26.1007.21434" }, capabilities: { experimentalApi: true } } });
const mime = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".woff2": "font/woff2", ".wasm": "application/wasm" };
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://localhost");
  const host = url.pathname.startsWith("/host/");
  const root = host ? path.join(repository, "frontend-host") : directory;
  const relative = host ? url.pathname.slice(6) : url.pathname === "/" ? "index.html" : url.pathname.slice(1);
  const file = path.resolve(root, relative);
  if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
  try { response.writeHead(200, { "Content-Type": mime[path.extname(file)] || "application/octet-stream" }); response.end(await readFile(file)); }
  catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 412, height: Number(option("height", "915")) } });
  page = await context.newPage();
  page.on("pageerror", error => logs.push({ type: "pageerror", message: error.stack || error.message }));
  page.on("console", message => { if (message.type() === "error" || message.type() === "warning") logs.push({ type: "console", message: message.text() }); });
  await page.exposeBinding("codexSmokeNative", async (_, raw) => {
    const message = JSON.parse(raw);
    if (message.type === "settings-navigation") {
      await page.evaluate(value => { window.__smokeInSettings = value; }, message.inSettings);
      return;
    }
    if (message.type === "log" || message.type === "thread-conflict") { logs.push(message); return; }
    if (message.type === "ready") {
      for (let attempt = 0; !initializeResult && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
      if (!initializeResult) throw new Error("app-server initialization timed out");
      await page.evaluate(message => window.codexRemoteReceive(message), { type: "font-scale", scale: Number(option("font-scale", "1")) });
      await page.evaluate(message => window.codexRemoteReceive(message), { type: "initialized", result: initializeResult, runningVersion: version, home: os.homedir(), serviceName: "Local smoke" });
      nativeReady = true;
      for (const message of early.splice(0)) await page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), message);
      await page.evaluate(() => window.codexRemoteReceive({ type: "connection", state: "connected" }));
    } else if (["rpc", "notification", "response"].includes(message.type)) {
      const request = message.request || message.response;
      if (request.id && request.method) requestMethods.set(request.id, request.method);
      outbound.push({ method: request.method, id: request.id, threadId: request.params?.threadId });
      if (fixture === "resume-conflict" && request.method === "thread/resume" && request.params.threadId === fixtureThread) {
        await fixtureMessage({ id: request.id, error: { code: -32603, message: `Thread ${fixtureThread} already has an active writer` } });
        return;
      }
      if (fixture && request.method === "turn/start") {
        if (attachmentDirectory) attachmentInput = request.params.input;
        fixtureThread = request.params.threadId;
        fixtureTurn = "codex-remote-fixture-turn";
        const turn = { id: fixtureTurn, status: "inProgress", items: [], error: null };
        await fixtureMessage({ id: request.id, result: { turn } });
        await fixtureMessage({ method: "turn/started", params: { threadId: fixtureThread, turn } });
        if (fixture === "approval") {
          const item = { id: "fixture-command", type: "commandExecution", command: "printf codex-remote-fixture", cwd: os.homedir(), status: "inProgress", commandActions: [], aggregatedOutput: null, exitCode: null, durationMs: null };
          await fixtureMessage({ method: "item/started", params: { threadId: fixtureThread, turnId: fixtureTurn, item } });
          await fixtureMessage({ id: "codex-remote-fixture-approval", method: "item/commandExecution/requestApproval", params: { threadId: fixtureThread, turnId: fixtureTurn, itemId: item.id, command: item.command, cwd: item.cwd, reason: "Protocol fixture: no command will execute." } });
        } else if (fixture === "error") {
          const error = { message: "CODEX_REMOTE_FIXTURE_ERROR", codexErrorInfo: "other", additionalDetails: null };
          await fixtureMessage({ method: "error", params: { threadId: fixtureThread, turnId: fixtureTurn, error, willRetry: false } });
          await fixtureMessage({ method: "turn/completed", params: { threadId: fixtureThread, turn: { ...turn, status: "failed", error } } });
        } else await finishFixture();
        return;
      }
      if (fixture && request.id === "codex-remote-fixture-approval") {
        logs.push({ type: "fixture-approval-response", result: request.result });
        await fixtureMessage({ method: "serverRequest/resolved", params: { threadId: fixtureThread, requestId: request.id } });
        await finishFixture();
        return;
      }
      if (/^(config\/(?:value\/write|batchWrite)|account\/(?:login|logout))/.test(request.method || "")) {
        await page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), { id: request.id, error: { code: -32601, message: "This smoke test does not modify system configuration or login" } });
        return;
      }
      write(request);
    } else if (message.type === "host-request") {
      let result;
      let error;
      try {
        const params = message.params;
        switch (message.method) {
          case "files/pick": pickerRequests.push(params); result = { files: pickerFiles }; break;
          case "file/open": attachmentChecks.push({ opened: params.path }); result = {}; break;
          case "fs/read": result = { content: await readFile(params.path, params.encoding === "base64" ? "base64" : "utf8") }; break;
          case "fs/stat": { const info = await stat(params.path); result = { size: info.size, isDirectory: info.isDirectory(), mtimeMs: info.mtimeMs }; break; }
          case "fs/list": result = { entries: (await readdir(params.path, { withFileTypes: true })).map(entry => ({ name: entry.name, path: path.join(params.path, entry.name), isDirectory: entry.isDirectory() })) }; break;
          default: throw new Error(`Smoke host does not implement ${message.method}`);
        }
      } catch (cause) { error = { message: cause.message }; }
      await page.evaluate(message => window.codexRemoteReceive(message), { type: "host-response", id: message.id, result, error });
    }
  });
  await page.addInitScript(() => {
    window.__smokeDocumentId = crypto.randomUUID();
    window.CodexRemoteHost = { postMessage: message => window.codexSmokeNative(message) };
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(Number(option("wait", "12000")));
  await page.locator('[contenteditable="true"]').first().waitFor({ state: "visible", timeout: 15000 });
  if (args.includes("--attachments")) {
    const identity = await page.evaluate(() => window.__smokeDocumentId);
    const composer = page.locator('[contenteditable="true"]').first();
    await composer.fill("Unsent attachment draft");
    const turns = outbound.filter(message => message.method === "turn/start").length;
    const pick = async (name, files) => {
      pickerFiles = files.map(label => ({ label, path: path.join(attachmentDirectory, label), fsPath: path.join(attachmentDirectory, label) }));
      await page.getByRole("button", { name: "Add files and more", exact: true }).click();
      await page.getByText(name, { exact: true }).last().click();
      await page.waitForTimeout(1500);
    };
    await pick("Files and folders", []);
    assert.equal(pickerRequests.length, 1, "cancel must reach the native picker");
    assert.equal(await composer.innerText(), "Unsent attachment draft");
    await pick("Files and folders", ["phone-photo.png"]);
    await page.waitForFunction(() => [...document.images].some(image => image.src.startsWith("data:image/") && image.naturalWidth > 0));
    await pick("Files and folders", ["phone-notes.txt"]);
    await page.getByText("phone-notes.txt", { exact: true }).first().waitFor({ timeout: 5000 });
    assert.equal(pickerRequests.length, 3);
    assert.equal(await page.evaluate(() => window.__smokeDocumentId), identity);
    assert.equal(outbound.filter(message => message.method === "turn/start").length, turns, "selecting attachments must not send a turn");
    attachmentChecks.push({ cancelled: true, imageDecoded: true, fileAttached: true, noReload: true, noAutomaticSend: true });
    const artifactDirectory = path.resolve(option("artifacts", path.join(repository, ".data/frontend-smoke")));
    await mkdir(artifactDirectory, { recursive: true });
    await page.screenshot({ path: path.join(artifactDirectory, "attachments.png"), fullPage: true });
  }
  async function checkSettingsReturn() {
    const identity = await page.evaluate(() => window.__smokeDocumentId);
    const readyCount = logs.filter(message => message.message?.startsWith("startup ")).length;
    const turns = outbound.filter(message => ["turn/start", "turn/steer", "turn/interrupt"].includes(message.method)).length;
    await page.evaluate(async () => {
      const version = document.querySelector('meta[name="codex-extension-version"]').content;
      const { navigation } = (await (await fetch("/host/adapters.json")).json()).versions[version];
      const state = await import(new URL(navigation.state.bundle, document.baseURI).href);
      window.__smokeRoute = () => state[navigation.state.contextExport]().scope.get(state[navigation.state.locationExport]);
      // Simulate an unsaved settings section without writing remote config.
      const dirty = state[version === "26.1007.21434" ? "BZ" : "oZ"];
      const scope = state[navigation.state.contextExport]().scope;
      window.__smokeSetDirty = value => scope.set(dirty, value);
      window.__smokePreviousDirty = scope.get(dirty);
    });
    const composer = page.locator('[contenteditable="true"]').first();
    await composer.pressSequentially("Unsent settings return draft");
    await page.waitForTimeout(750);
    const source = await page.evaluate(() => { const { pathname, search, hash, state } = window.__smokeRoute(); return { pathname, search, hash, state }; });
    await page.evaluate(() => window.postMessage({ type: "navigate-to-route", path: "/settings/agent" }, location.origin));
    await page.waitForFunction(() => window.__smokeInSettings === true);
    await page.getByRole("button", { name: "General", exact: true }).click();
    await page.waitForFunction(() => window.__smokeRoute().pathname !== "/settings/agent");
    await page.evaluate(() => window.__smokeSetDirty(new Set(["smoke-unsaved-section"])));
    let confirmations = 0;
    const cancel = page.waitForEvent("dialog").then(async dialog => { confirmations++; await dialog.dismiss(); });
    await Promise.all([cancel, page.evaluate(() => window.codexRemoteReceive({ type: "exit-settings" }))]);
    assert.equal(await page.evaluate(() => window.__smokeInSettings), true, "Cancel must keep settings open");
    const accept = page.waitForEvent("dialog").then(async dialog => { confirmations++; await dialog.accept(); });
    await Promise.all([accept, page.evaluate(() => window.codexRemoteReceive({ type: "exit-settings" }))]);
    await page.waitForFunction(() => window.__smokeInSettings === false);
    await composer.waitFor();
    await page.waitForFunction(() => document.querySelector('[contenteditable="true"]')?.textContent?.includes("Unsent settings return draft"), null, { timeout: 5000 });
    assert.deepEqual(await page.evaluate(() => { const { pathname, search, hash, state } = window.__smokeRoute(); return { pathname, search, hash, state }; }), source);
    assert.equal(await composer.innerText(), "Unsent settings return draft");
    assert.equal(await page.evaluate(() => window.__smokeDocumentId), identity);
    assert.equal(logs.filter(message => message.message?.startsWith("startup ")).length, readyCount);
    assert.equal(outbound.filter(message => ["turn/start", "turn/steer", "turn/interrupt"].includes(message.method)).length, turns);
    assert.equal(confirmations, 2, "The official leave guard must run for cancel and accept");
    await page.evaluate(() => window.__smokeSetDirty(window.__smokePreviousDirty));
    await composer.fill("");
    settingsReturnChecks.push({ source, draftPreserved: true, documentPreserved: true, confirmations });
  }
  if (args.includes("--settings-return")) await checkSettingsReturn();
  if (args.includes("--model-settings")) {
    const openSettings = async () => {
      await page.evaluate(() => window.postMessage({ type: "navigate-to-route", path: "/settings/agent" }, location.origin));
      await page.getByText("Available reasoning efforts", { exact: true }).waitFor();
      await page.getByRole("button", { name: /^\d+ selected$/ }).click();
    };
    await openSettings();
    const max = page.getByRole("menuitemcheckbox", { name: "Max", exact: true });
    const ultra = page.getByRole("menuitemcheckbox", { name: "Ultra", exact: true });
    assert.equal(await ultra.getAttribute("aria-checked"), "true");
    assert.equal(await max.getAttribute("aria-checked"), "false");
    await max.click();
    await page.keyboard.press("Escape");
    const enabled = await page.evaluate(() => JSON.parse(Object.entries(localStorage).find(([key]) => key.endsWith("settings:enabled-reasoning-efforts"))[1]));
    assert.ok(enabled.includes("max") && enabled.includes("ultra"));
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.locator('[contenteditable="true"]').first().waitFor();
    await openSettings();
    assert.equal(await max.getAttribute("aria-checked"), "true", "Model preferences must survive reload");
    assert.equal(await ultra.getAttribute("aria-checked"), "true");
    await page.keyboard.press("Escape");
    await page.evaluate(() => window.postMessage({ type: "navigate-to-route", path: "/" }, location.origin));
    await page.locator('[contenteditable="true"]').first().waitFor();
  }
  if (option("open", "")) {
    await page.getByText(option("open"), { exact: true }).first().click();
    await page.waitForTimeout(5000);
  }
  if (option("send", "")) {
    const composer = page.locator('[contenteditable="true"]').first();
    await composer.fill(option("send"));
    await composer.press("Enter");
    if (fixture === "approval") await page.getByRole("button", { name: "Allow once", exact: true }).click({ timeout: 15000 });
    await page.waitForTimeout(Number(option("reply-wait", "15000")));
    conversationLayout = await page.evaluate(() => {
      const geometry = node => {
        const rect = node.getBoundingClientRect();
        return { top: rect.top, bottom: rect.bottom, height: rect.height, cssHeight: getComputedStyle(node).height };
      };
      const editor = document.querySelector('[contenteditable="true"]');
      return { viewportHeight: window.innerHeight, roots: [document.documentElement, document.body, document.getElementById("root")].map(geometry),
        editor: editor ? geometry(editor) : null,
        composer: editor ? geometry(editor.closest("[data-chatgpt-composer]") || editor) : null };
    });
    for (const root of conversationLayout.roots) assert.ok(Math.abs(root.height - conversationLayout.viewportHeight) <= 1, "Host root containers must fill the viewport without collapsing");
    assert.ok(conversationLayout.composer, "A conversation must have its official composer");
    assert.ok(conversationLayout.composer.bottom <= conversationLayout.viewportHeight + 1, "Conversation composer must stay inside the viewport");
    assert.ok(conversationLayout.viewportHeight - conversationLayout.composer.bottom <= 120, "Conversation composer must sit near the bottom, above the official footer");
    assert.ok(conversationLayout.composer.top >= conversationLayout.viewportHeight * 0.55, "A short conversation must leave its composer in the lower part of the viewport");
  }
  const artifactDirectory = path.resolve(option("artifacts", path.join(repository, ".data/frontend-smoke")));
  if (args.includes("--settings-return") && option("send", "")) await checkSettingsReturn();
  await mkdir(artifactDirectory, { recursive: true });
  const fontChecks = [];
  if (args.includes("--fonts")) {
    const composer = page.locator('[contenteditable="true"]').first();
    const initialSize = await composer.evaluate(node => parseFloat(getComputedStyle(node).fontSize));
    assert.ok(Math.abs(initialSize - 13 * Number(option("font-scale", "1"))) < 0.05, "Startup must preserve the font setting received before official UI readiness");
    const identity = await page.evaluate(() => window.__smokeDocumentId);
    const startups = logs.filter(message => message.message?.startsWith("startup ")).length;
    const operations = outbound.filter(message => ["thread/resume", "turn/start", "turn/steer", "turn/interrupt"].includes(message.method)).length;
    const previous = await composer.innerText();
    await composer.fill("Unsent font size draft");
    for (const scale of [0.85, 1, 1.3]) {
      await page.evaluate(scale => window.codexRemoteReceive({ type: "font-scale", scale }), scale);
      await page.waitForFunction(size => Math.abs(parseFloat(getComputedStyle(document.querySelector('[contenteditable="true"]')).fontSize) - size) < 0.05, 13 * scale);
      const actual = await page.evaluate(() => ({
        chat: getComputedStyle(document.querySelector('[contenteditable="true"]')).fontSize,
        code: document.documentElement.style.getPropertyValue("--codex-chat-code-font-size-override"),
        host: document.documentElement.style.getPropertyValue("--vscode-font-size"),
      }));
      assert.ok(Math.abs(parseFloat(actual.code) - 13 * scale) < 0.05);
      assert.ok(Math.abs(parseFloat(actual.host) - 13 * scale) < 0.05);
      assert.equal(await composer.innerText(), "Unsent font size draft");
      assert.equal(await page.evaluate(() => window.__smokeDocumentId), identity);
      fontChecks.push({ scale, ...actual });
    }
    assert.equal(logs.filter(message => message.message?.startsWith("startup ")).length, startups);
    assert.equal(outbound.filter(message => ["thread/resume", "turn/start", "turn/steer", "turn/interrupt"].includes(message.method)).length, operations);
    await page.evaluate(() => window.codexRemoteReceive({ type: "font-scale", scale: 1 }));
    await page.waitForFunction(() => getComputedStyle(document.querySelector('[contenteditable="true"]')).fontSize === "13px");
    // Let the official editor finish applying the new typography before the next edit.
    await page.waitForTimeout(250);
    await composer.fill(previous);
    await page.waitForTimeout(750);
  }
  const themeChecks = [];
  if (args.includes("--themes")) {
    const identity = await page.evaluate(() => window.__smokeDocumentId);
    const composer = page.locator('[contenteditable="true"]').first();
    const previousDraft = await composer.textContent() || "";
    await composer.fill("Unsent theme test draft");
    const turnsBefore = outbound.filter(message => message.method === "turn/start" || message.method === "turn/steer").length;
    for (const [theme, background, foreground] of [["light", "rgb(255, 255, 255)", "rgb(51, 51, 51)"], ["anthropic", "rgb(250, 249, 245)", "rgb(48, 47, 43)"], ["dark", "rgb(30, 30, 30)", "rgb(212, 212, 212)"]]) {
      await page.evaluate(theme => window.codexRemoteReceive({ type: "theme", theme }), theme);
      await page.waitForTimeout(250);
      const actual = await page.evaluate(() => {
        const root = getComputedStyle(document.documentElement);
        const body = getComputedStyle(document.body);
        return { theme: document.documentElement.dataset.codexTheme, officialTheme: document.documentElement.dataset.theme, kind: document.body.dataset.vscodeThemeKind, background: body.backgroundColor, color: body.color,
          tokens: Object.fromEntries(["input-background", "input-foreground", "button-background", "button-foreground", "menu-background", "menu-foreground", "input-border", "errorForeground"].map(name => [name, root.getPropertyValue(`--vscode-${name}`).trim()])) };
      });
      assert.equal(actual.background, background);
      assert.equal(actual.color, foreground);
      assert.equal(actual.officialTheme, theme === "dark" ? "dark" : "light");
      assert.equal(await composer.innerText(), "Unsent theme test draft");
      assert.equal(await page.evaluate(() => window.__smokeDocumentId), identity, "Theme changes must preserve the document");
      themeChecks.push(actual);
      await page.screenshot({ path: path.join(artifactDirectory, `official-${theme}.png`), fullPage: true });
    }
    await composer.fill(previousDraft);
    await page.waitForTimeout(250);
    assert.equal(outbound.filter(message => message.method === "turn/start" || message.method === "turn/steer").length, turnsBefore, "Theme changes must not send the draft");
  }
  if (args.includes("--reconnect")) {
    const previousResumes = outbound.filter(message => message.method === "thread/resume").length;
    const previousRecoveries = logs.filter(message => message.message === "websocket_reconnect_recovery_start").length;
    await page.evaluate(() => {
      window.codexRemoteReceive({ type: "connection", state: "connected" });
      window.codexRemoteReceive({ type: "connection", state: "connected" });
    });
    await page.waitForTimeout(500);
    assert.equal(outbound.filter(message => message.method === "thread/resume").length, previousResumes, "Duplicate connected events must not resume threads");
    await page.evaluate(() => window.codexRemoteReceive({ type: "connection", state: "disconnected" }));
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      window.codexRemoteReceive({ type: "connection", state: "connected" });
      window.codexRemoteReceive({ type: "connection", state: "connected" });
    });
    await page.waitForTimeout(4000);
    assert.equal(logs.filter(message => message.message === "websocket_reconnect_recovery_start").length, previousRecoveries + 1, "One reconnect must start official recovery once");
    assert.ok(outbound.filter(message => message.method === "thread/resume").length > previousResumes, "Official recovery must resume the current thread");
    reconnectChecks = { duplicateConnectedTriggeredResume: false, recoveryPasses: 1, resumeRequests: outbound.filter(message => message.method === "thread/resume").length - previousResumes };
  }
  if (attachmentDirectory && fixture) {
    assert.ok(attachmentInput.some(item => item.type === "localImage" || item.type === "image"), "Official turn must include the selected photo");
    assert.ok(JSON.stringify(attachmentInput).includes("phone-notes.txt"), "Official turn must reference the selected file");
    await page.getByText("预览图片", { exact: true }).click();
    await page.waitForTimeout(300);
    assert.ok(attachmentChecks.some(check => check.opened === path.join(attachmentDirectory, "phone-photo.png")), "Preview links must call the native binary-aware viewer");
    attachmentChecks.push({ sentImageAndFile: true, previewLinkOpened: true });
  }
  const text = (await page.locator("body").innerText()).slice(0, 12000);
  if (fixture) {
    assert.ok(outbound.some(message => message.method === "turn/start"), "Official UI must send turn/start");
    if (fixture !== "resume-conflict") assert.ok(text.includes(fixture === "error" ? "CODEX_REMOTE_FIXTURE_ERROR" : "CODEX_REMOTE_FIXTURE_OK"), "Official UI must render the fixture result");
  }
  if (fixture === "resume-conflict") {
    assert.deepEqual(logs.filter(message => message.type === "thread-conflict"), [{ type: "thread-conflict", threadId: fixtureThread, message: `Thread ${fixtureThread} already has an active writer`, retryable: true }]);
    assert.ok(text.includes("already has an active writer") || logs.some(message => message.type === "log" && message.message.includes("already has an active writer")), "Official frontend must still receive the original writer error");
  }
  if (fixture === "approval") {
    assert.deepEqual(logs.find(message => message.type === "fixture-approval-response")?.result, { decision: "accept" }, "Official approval must return its decision through the host");
  }
  if (option("expect", "")) await page.getByText(option("expect"), { exact: true }).first().waitFor({ state: "visible", timeout: 1000 });
  assert.deepEqual(logs.filter(message => message.type === "pageerror"), [], "Official UI must not have uncaught JavaScript errors");
  await page.screenshot({ path: path.join(artifactDirectory, "official-frontend.png"), fullPage: true });
  const theme = await page.evaluate(() => ({ className: document.body.className, color: getComputedStyle(document.body).color, background: getComputedStyle(document.body).backgroundColor, foregroundToken: getComputedStyle(document.body).getPropertyValue("--vscode-foreground") }));
  report = { url: page.url(), fixture: fixture || null, theme, themeChecks, fontChecks, reconnectChecks, conversationLayout, settingsReturnChecks, attachmentChecks, text, createdThreads: [...createdThreads], outbound, logs: logs.slice(-100) };
} catch (error) {
  const artifactDirectory = path.resolve(option("artifacts", path.join(repository, ".data/frontend-smoke")));
  await mkdir(artifactDirectory, { recursive: true });
  await writeFile(path.join(artifactDirectory, "failure.json"), JSON.stringify({ error: error.message, outbound, logs: logs.slice(-200), text: await page?.locator("body").innerText().catch(() => "") }, null, 2));
  throw error;
} finally {
  const archiveResults = [];
  for (const threadId of createdThreads) {
    const id = `smoke-archive-${threadId}`;
    const reply = new Promise(resolve => cleanupReplies.set(id, resolve));
    write({ id, method: "thread/archive", params: { threadId } });
    const result = await Promise.race([reply, new Promise(resolve => setTimeout(() => resolve({ error: { message: "Archive cleanup timed out" } }), 3000))]);
    archiveResults.push({ threadId, ...result });
  }
  if (report) report.cleanup = archiveResults;
  await browser.close();
  server.close();
  child.kill("SIGTERM");
  if (attachmentDirectory) await rm(attachmentDirectory, { recursive: true, force: true });
}
console.log(JSON.stringify(report, null, 2));
