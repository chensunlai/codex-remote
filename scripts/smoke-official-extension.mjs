#!/usr/bin/env node
import assert from "node:assert/strict";
import { fork, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { appendFileSync, readFileSync } from "node:fs";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath, pathToFileURL } from "node:url";

const script = fileURLToPath(import.meta.url);
const repository = path.resolve(path.dirname(script), "..");
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  if (index < 0) return fallback;
  const value = args[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`--${name} requires a value`);
  return value;
};

if (args.includes("--fixture-cli")) await runFixture();
else if (args.includes("--proxy-cli")) await runProxy();
else if (args.includes("--help")) {
  console.log("Usage: node scripts/smoke-official-extension.mjs [--extension DIR] [--artifacts DIR] [--send TEXT] [--expect TEXT] [--attachments] [--mouse-input] [--approval] [--settings] [--reload] [--timeout MS] [--codex EXECUTABLE]\n\nThe default backend is a safe local App Server fixture. --codex uses the logged-in real CLI; --send then makes a real model request. For real replies, request CODEX_REMOTE_EXTENSION_SMOKE_OK or set --expect. --mouse-input requires --attachments and checks reversible mobile mouse-mode hover followed by a pointer-position touch, without Tab navigation. Playwright can be supplied with CODEX_PLAYWRIGHT_MODULE.");
} else await runSmoke();

async function runSmoke() {
  const extensionDirectory = await realpath(option("extension", path.join(os.homedir(), ".vscode-server/extensions/openai.chatgpt-26.1007.21434-linux-x64")));
  const artifactDirectory = path.resolve(option("artifacts", path.join(repository, ".data/extension-smoke")));
  const manifest = JSON.parse(await readFile(path.join(extensionDirectory, "package.json"), "utf8"));
  assert.equal(`${manifest.publisher}.${manifest.name}`.toLowerCase(), "openai.chatgpt");
  const realCodex = option("codex", "");
  const sendText = option("send", "");
  const approval = args.includes("--approval");
  const mouseInput = args.includes("--mouse-input");
  assert.ok(!approval || (!realCodex && sendText), "--approval requires --send and the safe fixture backend");
  assert.ok(!mouseInput || args.includes("--attachments"), "--mouse-input requires --attachments");
  const timeout = Number(option("timeout", "30000"));
  assert.ok(Number.isFinite(timeout) && timeout > 0, "--timeout must be positive milliseconds");
  const temporary = await mkdtemp(path.join(os.tmpdir(), "codex-extension-smoke-"));
  const workspace = path.join(temporary, "workspace");
  const stateDirectory = path.join(temporary, "state");
  const fixtureLog = path.join(temporary, "cli-requests.jsonl");
  const executable = path.join(temporary, "codex-smoke-cli");
  await Promise.all([mkdir(workspace), mkdir(stateDirectory), mkdir(artifactDirectory, { recursive: true }), mkdir(path.join(temporary, "codex-home"))]);
  const cliMode = realCodex ? ['--proxy-cli', realCodex] : ['--fixture-cli', ...(approval ? ['--fixture-approval'] : [])];
  await writeFile(executable, `#!/usr/bin/env node\nprocess.argv.splice(2, 0, ...${JSON.stringify([...cliMode, '--fixture-log', fixtureLog, '--workspace', workspace])});\nawait import(${JSON.stringify(import.meta.url)});\n`);
  await chmod(executable, 0o755);

  const events = [];
  const browserErrors = [];
  const hostRequests = [];
  const messages = [];
  const resources = new Map();
  const panels = new Map();
  let pickerFiles;
  let html = "";
  let page;
  let pageReady = false;
  let browser;
  let context;
  let runtime;
  let started = false;
  let discardMessages = false;
  let report;
  let resolveHtml, rejectHtml;
  const initialHtml = new Promise((resolve, reject) => { resolveHtml = resolve; rejectHtml = reject; });
  initialHtml.catch(() => {});
  const mime = {
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png",
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".woff2": "font/woff2", ".wasm": "application/wasm",
  };
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname === "/") {
        response.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }).end(html);
        return;
      }
      if (url.pathname.startsWith("/panel/")) {
        const panel = panels.get(decodeURIComponent(url.pathname.slice(7)));
        if (!panel) { response.writeHead(404).end(); return; }
        response.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }).end(panel.html);
        return;
      }
      const prefix = `/extension/${encodeURIComponent(manifest.version)}/`;
      if (!url.pathname.startsWith(prefix)) { response.writeHead(404).end(); return; }
      const relative = decodeURIComponent(url.pathname.slice(prefix.length));
      const filename = await realpath(path.resolve(extensionDirectory, relative));
      if (!filename.startsWith(extensionDirectory + path.sep)) { response.writeHead(403).end(); return; }
      const content = await readFile(filename);
      resources.set(relative, createHash("sha256").update(content).digest("hex"));
      response.writeHead(200, { "content-type": mime[path.extname(filename)] || "application/octet-stream", "cache-control": "no-store" }).end(content);
    } catch (error) {
      response.writeHead(error.code === "ENOENT" ? 404 : 500).end();
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const deliver = async (payload, surfaceId = "main") => {
    if (surfaceId !== "main") {
      const panel = panelState(surfaceId);
      if (!panel.ready) { panel.messages.push(payload); return; }
      await panel.page.evaluate(value => window.__extensionSmokeReceive(value), payload);
      return;
    }
    if (!pageReady) { if (!discardMessages) messages.push(payload); return; }
    await page.evaluate(value => window.__extensionSmokeReceive(value), payload);
  };
  function panelState(id) {
    if (!panels.has(id)) panels.set(id, { html: "", page: undefined, opening: undefined, ready: false, messages: [], closed: false });
    return panels.get(id);
  }
  function pageErrors(target, surfaceId) {
    target.on("pageerror", error => browserErrors.push({ type: "pageerror", surfaceId, message: error.stack || error.message }));
    target.on("console", message => {
      if (["error", "warning"].includes(message.type())) browserErrors.push({ type: message.type(), surfaceId, message: redactText(message.text()) });
    });
  }
  async function openPanel(id) {
    const panel = panelState(id);
    panel.page = await context.newPage();
    pageErrors(panel.page, id);
    await panel.page.goto(`${origin}/panel/${encodeURIComponent(id)}`, { waitUntil: "domcontentloaded" });
    return panel.page;
  }

  try {
    runtime = fork(path.join(repository, "agent/src/extension-host/runtime.cjs"), [], {
      stdio: ["ignore", "pipe", "pipe", "ipc"],
      env: { ...process.env, ...(realCodex ? {} : { CODEX_HOME: path.join(temporary, "codex-home") }) },
    });
    runtime.stdout.on("data", data => events.push({ type: "stdout", message: redactText(data.toString()) }));
    runtime.stderr.on("data", data => events.push({ type: "stderr", message: redactText(data.toString()) }));
    runtime.on("exit", (code, signal) => {
      events.push({ type: "exit", code, signal });
      if (!html) rejectHtml(new Error(`Extension host exited before providing HTML (${code ?? signal})`));
    });
    runtime.on("message", event => {
      if (event.type === "html") {
        if (!event.surfaceId || event.surfaceId === "main") {
          html = event.payload.html;
          resolveHtml();
        } else {
          const panel = panelState(event.surfaceId);
          panel.html = event.payload.html;
          if (context && !panel.opening) panel.opening = openPanel(event.surfaceId);
        }
      } else if (event.type === "message") {
        void deliver(event.payload, event.surfaceId).catch(error => browserErrors.push({ type: "delivery", message: error.message }));
      } else if (event.type === "surface") {
        events.push(redact(event));
        if (event.payload.action === "close") {
          const panel = panelState(event.surfaceId);
          panel.closed = true;
          void panel.page?.close();
        }
      } else if (event.type === "host-request") {
        const request = event.payload;
        hostRequests.push(redact(request));
        const result = request.method === "clipboard.read" ? "" : request.method === "openExternal" ? false : request.method === "showOpenDialog" ? pickerFiles : undefined;
        runtime.send({ type: "host-response", id: request.id, result });
      } else {
        events.push(redact(event));
        if (event.type === "started") started = true;
        if (event.type === "error" && event.fatal) rejectHtml(new Error(event.message));
      }
    });
    runtime.send({ type: "start", options: {
      extensionDirectory, stateDirectory, workspace, clientId: randomUUID(), hostId: randomUUID(),
      origin, cliExecutable: executable, language: "en", main: manifest.main,
    } });
    await withTimeout(initialHtml, timeout, "Official extension did not provide WebView HTML");
    const { chromium } = await import(process.env.CODEX_PLAYWRIGHT_MODULE || "playwright");
    browser = await chromium.launch({ headless: true });
    context = await browser.newContext({ viewport: { width: 412, height: 915 }, hasTouch: true, ...(mouseInput ? { isMobile: true } : {}) });
    await context.exposeBinding("extensionSmokePostMessage", async (_source, surfaceId, payload) => {
      if (!runtime.connected) throw new Error("Extension host disconnected");
      runtime.send({ type: "message", surfaceId, payload });
    });
    await context.exposeBinding("extensionSmokeReady", async (_source, surfaceId) => {
      if (surfaceId !== "main") {
        const panel = panelState(surfaceId);
        panel.ready = true;
        for (const payload of panel.messages.splice(0)) await deliver(payload, surfaceId);
        return;
      }
      pageReady = true;
      discardMessages = false;
      for (const payload of messages.splice(0)) await deliver(payload);
    });
    const bridgeSource = await readFile(path.join(repository, "extension-webview-host/bridge.js"), "utf8");
    const themeCss = await readFile(path.join(repository, "extension-webview-host/theme.css"), "utf8");
    await context.addInitScript({ content: `
      (() => {
        const documentId = crypto.randomUUID();
        const surfaceId = location.pathname.startsWith('/panel/') ? decodeURIComponent(location.pathname.slice(7)) : 'main';
        const stateKey = 'extension-smoke-webview-state:' + surfaceId;
        window.__extensionSmokeDocumentId = documentId;
        window.CodexExtensionBridge = {
          getState: () => localStorage.getItem(stateKey) || 'null',
          setState: (_documentId, json) => localStorage.setItem(stateKey, json),
          postMessage: (_documentId, json) => { void window.extensionSmokePostMessage(surfaceId, JSON.parse(json)); },
          ready: () => { void window.extensionSmokeReady(surfaceId); },
        };
        window.__codexExtensionConfig = { documentId, theme: 'dark', fontScale: 1, mouseMode: false, themeCss: ${JSON.stringify(themeCss)} };
      })();
      ${bridgeSource}
      (() => {
        const acquire = window.acquireVsCodeApi;
        window.acquireVsCodeApi = () => { window.__extensionSmokeAcquired = true; return acquire(); };
        window.__extensionSmokeReceive = payload => window.__codexExtensionHost.receive(payload);
      })();
    ` });
    page = await context.newPage();
    pageErrors(page, "main");
    await page.goto(origin, { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => window.__extensionSmokeAcquired, null, { timeout });
    const composer = page.locator('[contenteditable="true"]').first();
    // Exercise first-run onboarding through the visible official controls.
    for (let attempt = 0; attempt < 12 && !(await composer.isVisible()); attempt++) {
      const advance = page.getByRole("button", { name: /^(Next|Get started|Done|Continue)$/i }).last();
      if (await advance.isVisible()) await advance.click();
      await page.waitForTimeout(500);
    }
    await composer.waitFor({ state: "visible", timeout });
    await page.getByRole("button", { name: "New chat", exact: true }).waitFor({ state: "visible", timeout });
    assert.ok(started, "Official extension activation must complete");
    let attachments;
    if (args.includes("--attachments")) {
      const photo = path.join(workspace, "phone-photo.png");
      const notes = path.join(workspace, "phone-notes.txt");
      await writeFile(photo, Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWZkAAAAASUVORK5CYII=", "base64"));
      await writeFile(notes, "Attachment fixture\n");
      const documentId = await page.evaluate(() => window.__extensionSmokeDocumentId);
      await composer.fill("Unsent attachment draft");
      const pick = async files => {
        pickerFiles = files?.map(filename => pathToFileURL(filename).href);
        await page.getByRole("button", { name: "Add files and more", exact: true }).click();
        await page.getByText("Files and folders", { exact: true }).last().click();
      };
      await pick(undefined);
      await page.waitForTimeout(300);
      assert.equal(await composer.innerText(), "Unsent attachment draft", "Canceling picker must preserve draft");
      await pick([photo]);
      await page.waitForFunction(() => [...document.images].some(image => image.naturalWidth === 1 && image.naturalHeight === 1), null, { timeout });
      let mouseInputCheck;
      if (mouseInput) {
        assert.equal(await page.evaluate(() => matchMedia("(hover: hover)").matches), false, "Phone fixture must report no physical hover capability");
        const removePhoto = page.getByRole("button", { name: "Remove phone-photo.png", exact: true });
        await removePhoto.waitFor({ state: "attached", timeout });
        const isInteractive = () => removePhoto.evaluate(button => {
          const style = getComputedStyle(button);
          return style.pointerEvents !== "none" && Number(style.opacity) > 0;
        });
        const waitInteractive = async enabled => page.waitForFunction(({ button, enabled }) => {
          const style = getComputedStyle(button);
          return (style.pointerEvents !== "none" && Number(style.opacity) > 0) === enabled;
        }, { button: await removePhoto.elementHandle(), enabled }, { timeout });
        const mediaQueries = () => page.evaluate(() => {
          const values = [];
          const visit = rules => { for (const rule of rules) { if (rule.media) values.push(rule.media.mediaText); if (rule.cssRules) visit(rule.cssRules); } };
          for (const sheet of document.styleSheets) { try { visit(sheet.cssRules); } catch {} }
          return values;
        });
        const originalQueries = await mediaQueries();
        const serverProcesses = events.filter(event => event.type === "process-start").length;
        assert.equal(await isInteractive(), false, "Official photo delete starts hidden on a phone");
        const photoBounds = await page.getByRole("button", { name: "phone-photo.png", exact: true }).boundingBox();
        assert.ok(photoBounds, "Official photo attachment must have bounds");
        await page.mouse.move(photoBounds.x + photoBounds.width / 2, photoBounds.y + photoBounds.height / 2);
        assert.equal(await isInteractive(), false, "Synthetic mouse movement must not bypass the official hover media query");
        await page.evaluate(() => window.__codexExtensionHost.setMouseMode(true));
        await waitInteractive(true);
        assert.equal(await page.evaluate(() => matchMedia("(hover: hover)").matches), false, "Mouse mode must not override JavaScript device capability checks");
        await page.mouse.move(10, 10);
        await waitInteractive(false);
        await page.mouse.move(photoBounds.x + photoBounds.width / 2, photoBounds.y + photoBounds.height / 2);
        await waitInteractive(true);
        await page.evaluate(() => window.__codexExtensionHost.setMouseMode(false));
        await waitInteractive(false);
        assert.deepEqual(await mediaQueries(), originalQueries, "Disabling mouse mode must restore official media conditions exactly");
        await page.evaluate(() => window.__codexExtensionHost.setMouseMode(true));
        await waitInteractive(true);
        await page.screenshot({ path: path.join(artifactDirectory, "mouse-hover-delete.png"), fullPage: true });
        const deleteBounds = await removePhoto.boundingBox();
        assert.ok(deleteBounds, "Revealed delete button must have bounds");
        const x = deleteBounds.x + deleteBounds.width / 2;
        const y = deleteBounds.y + deleteBounds.height / 2;
        await page.mouse.move(x, y);
        await page.touchscreen.tap(x, y);
        await removePhoto.waitFor({ state: "detached", timeout });
        await page.evaluate(() => window.__codexExtensionHost.setMouseMode(false));
        assert.deepEqual(await mediaQueries(), originalQueries, "Removing an attachment must retain the original official styles");
        assert.equal(await composer.innerText(), "Unsent attachment draft", "Removing the image must preserve the draft");
        assert.equal(await page.evaluate(() => window.__extensionSmokeDocumentId), documentId, "Removing the image must preserve the document");
        assert.equal(events.filter(event => event.type === "process-start").length, serverProcesses, "Mouse mode must preserve the App Server process");
        assert.equal((await readRequests(fixtureLog)).filter(request => request.method === "turn/start").length, 0, "Hover and pointer operations must not send a message");
        mouseInputCheck = { physicalHoverUnavailable: true, mouseModeRevealedDelete: true, movingAwayHidesDelete: true, disablingRestoresStyles: true, touchRemovedPhoto: true, noTabRequired: true, draftPreserved: true, documentPreserved: true, serverPreserved: true, noAutomaticSend: true };
        await pick([photo]);
        await page.waitForFunction(() => [...document.images].some(image => image.naturalWidth === 1 && image.naturalHeight === 1), null, { timeout });
      }
      await pick([notes]);
      await page.getByText("phone-notes.txt", { exact: true }).first().waitFor({ timeout });
      assert.equal(await page.evaluate(() => window.__extensionSmokeDocumentId), documentId, "Attachment selection must retain the document");
      assert.equal((await readRequests(fixtureLog)).filter(request => request.method === "turn/start").length, 0, "Attachment selection must not send a turn");
      attachments = { pickerCancelled: true, photoDecoded: true, fileSelected: true, documentPreserved: true, noAutomaticSend: true,
        ...(mouseInputCheck ? { mouseInput: mouseInputCheck } : {}) };
      await page.screenshot({ path: path.join(artifactDirectory, "attachments.png"), fullPage: true });
    }
    let settings;
    async function checkSettings() {
      const documentId = await page.evaluate(() => window.__extensionSmokeDocumentId);
      const processes = events.filter(event => event.type === "process-start").length;
      const previousDraft = await composer.innerText();
      const draft = "Unsent settings panel draft";
      await composer.fill(draft);
      if (!realCodex) await page.getByText("Fixture Codex", { exact: true }).first().waitFor({ timeout });
      // The official gear is the unlabeled button immediately before New chat.
      await page.getByRole("button", { name: "New chat", exact: true }).locator("xpath=preceding::button[1]").click();
      await page.getByText("Codex settings", { exact: true }).click();
      await waitUntil(() => [...panels.values()].some(panel => panel.page && panel.ready), timeout, "Official settings panel did not open");
      const [surfaceId, panel] = [...panels.entries()].find(([, value]) => value.page && !value.closed);
      const settingsPage = panel.page;
      await settingsPage.getByRole("button", { name: "General", exact: true }).waitFor({ timeout });
      await settingsPage.getByText("Send shortcut", { exact: true }).waitFor({ timeout });
      await settingsPage.screenshot({ path: path.join(artifactDirectory, "settings.png"), fullPage: true });
      await settingsPage.getByRole("button", { name: "Configuration", exact: true }).click();
      await settingsPage.getByRole("heading", { name: "Configuration", exact: true }).waitFor({ timeout });
      await settingsPage.getByText("Custom config.toml settings", { exact: true }).waitFor({ timeout });
      await settingsPage.screenshot({ path: path.join(artifactDirectory, "settings-configuration.png"), fullPage: true });
      events.push({ type: "settings-controls", text: await settingsPage.locator("body").innerText(), buttons: await settingsPage.getByRole("button").evaluateAll(buttons => buttons.map(button => ({ label: button.getAttribute("aria-label"), text: button.textContent }))) });
      runtime.send({ type: "close-surface", surfaceId });
      await waitUntil(() => panel.closed, timeout, "Public settings panel disposal did not complete");
      await page.bringToFront();
      await composer.waitFor({ state: "visible", timeout });
      assert.equal(await page.evaluate(() => window.__extensionSmokeDocumentId), documentId, "Settings panel must retain the sidebar document");
      assert.equal(await composer.innerText(), draft, "Settings panel must retain the sidebar draft");
      assert.equal(events.filter(event => event.type === "process-start").length, processes, "Settings must preserve App Server");
      await composer.fill(previousDraft);
      settings = { publicPanel: true, categories: ["General", "Configuration"], configurationVisible: true, documentPreserved: true, draftPreserved: true, serverPreserved: true };
    }
    let approvalCheck;
    if (sendText) {
      await page.waitForTimeout(2500);
      await composer.fill(sendText);
      await page.waitForTimeout(500);
      events.push({ type: "send-controls", buttons: await page.getByRole("button").evaluateAll(buttons => buttons.map(button => ({ label: button.getAttribute("aria-label"), title: button.getAttribute("title"), text: button.textContent, disabled: button.disabled }))) });
      await page.getByRole("button", { name: "Send", exact: true }).click();
      if (approval) {
        const allow = page.getByRole("button", { name: "Allow once", exact: true });
        await allow.waitFor({ timeout });
        if (args.includes("--reload")) {
          pageReady = false;
          discardMessages = true;
          messages.length = 0;
          await page.reload({ waitUntil: "domcontentloaded" });
          await composer.waitFor({ state: "visible", timeout });
          const requests = await readRequests(fixtureLog);
          const turn = requests.find(request => isComposerTurn(request, sendText));
          const naming = requests.filter(request => request.method === "thread/name/set" && request.params.threadId === turn?.params.threadId).at(-1);
          await page.getByRole("button", { name: naming?.params.name || sendText, exact: true }).first().click({ timeout });
          await allow.waitFor({ timeout });
        }
        await allow.click();
        approvalCheck = { returnedThroughOfficialUi: true, coldReload: args.includes("--reload") };
      }
      await page.getByText(option("expect", realCodex ? "CODEX_REMOTE_EXTENSION_SMOKE_OK" : "CODEX_REMOTE_EXTENSION_FIXTURE_OK"), { exact: true }).first().waitFor({ timeout });
    }
    if (args.includes("--settings")) await checkSettings();
    let reload;
    if (args.includes("--reload")) {
      const documentId = await page.evaluate(() => window.__extensionSmokeDocumentId);
      const processes = events.filter(event => event.type === "process-start").length;
      const draft = "Unsent cold reload draft";
      await composer.fill(draft);
      await page.waitForTimeout(1000);
      await page.screenshot({ path: path.join(artifactDirectory, "before-reload.png"), fullPage: true });
      pageReady = false;
      discardMessages = true;
      messages.length = 0;
      await page.reload({ waitUntil: "domcontentloaded" });
      await composer.waitFor({ state: "visible", timeout });
      let reopened = false;
      if (sendText) {
        const reply = page.getByText(option("expect", realCodex ? "CODEX_REMOTE_EXTENSION_SMOKE_OK" : "CODEX_REMOTE_EXTENSION_FIXTURE_OK"), { exact: true }).first();
        if (!(await reply.isVisible())) {
          const requests = await readRequests(fixtureLog);
          const turn = requests.find(request => isComposerTurn(request, sendText));
          const naming = requests.filter(request => request.method === "thread/name/set" && request.params.threadId === turn?.params.threadId).at(-1);
          await page.getByRole("button", { name: naming?.params.name || sendText, exact: true }).first().click({ timeout });
          reopened = true;
        }
        await reply.waitFor({ timeout });
      }
      assert.notEqual(await page.evaluate(() => window.__extensionSmokeDocumentId), documentId, "Reload must create a new document");
      assert.equal(events.filter(event => event.type === "process-start").length, processes, "Cold page must preserve the running App Server");
      assert.equal((await composer.innerText()).trim(), draft, "Reopened official conversation must retain its unsent draft");
      reload = { newDocument: true, serverPreserved: true, conversationRestored: Boolean(sendText), reopenedThroughOfficialUi: reopened, draftPreserved: true };
    }
    const text = await page.locator("body").innerText();
    const cliRequests = await readRequests(fixtureLog);
    assert.ok(cliRequests.some(request => request.method === "initialize"), "Official extension must initialize the CLI");
    assert.ok(cliRequests.some(request => request.method === "model/list"), "Official extension must obtain the model list through App Server");
    if (sendText) assert.equal(cliRequests.filter(request => isComposerTurn(request, sendText)).length, 1, "Sending once must submit one composer turn");
    if (attachments && sendText) {
      const turn = cliRequests.find(request => isComposerTurn(request, sendText));
      assert.ok(turn.params.input.some(item => item.type === "localImage" || item.type === "image"), "Selected photo must reach documented image input");
      assert.ok(JSON.stringify(turn.params.input).includes("phone-notes.txt"), "Selected file must reach the turn input");
      attachments.sentImageAndFile = true;
    }
    if (approval) {
      const responses = cliRequests.filter(request => !request.method && request.result?.decision);
      assert.equal(responses.length, 1, "Approval must return exactly once after recovery");
      assert.equal(responses[0].result.decision, "accept", "Allow once must return the official accept decision");
    }
    assert.equal(browserErrors.filter(error => error.type === "pageerror").length, 0, "Official WebView must not have uncaught JavaScript errors");
    assert.equal(events.filter(event => event.type === "error" && event.fatal).length, 0, "Official extension host must not fail");
    report = { success: true, version: manifest.version, backend: realCodex ? "real" : "fixture", sent: Boolean(sendText), attachments, approval: approvalCheck, settings, reload, text, cliRequests, hostRequests, browserErrors, events, resources: Object.fromEntries(resources) };
    await page.screenshot({ path: path.join(artifactDirectory, "official-extension.png"), fullPage: true });
  } catch (error) {
    report = { success: false, version: manifest.version, error: error.stack || error.message, cliRequests: await readRequests(fixtureLog), hostRequests, browserErrors, events,
      text: await page?.locator("body").innerText().catch(() => ""), resources: Object.fromEntries(resources) };
    await page?.screenshot({ path: path.join(artifactDirectory, "failure.png"), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    await browser?.close();
    if (runtime && runtime.exitCode === null) {
      const exited = new Promise(resolve => runtime.once("exit", resolve));
      if (runtime.connected) runtime.send({ type: "stop" });
      await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 3000))]);
      if (runtime.exitCode === null) runtime.kill("SIGKILL");
    }
    if (realCodex && report) {
      const created = (await readRequests(fixtureLog)).filter(request => request.event === "thread-created" && !request.ephemeral).map(request => request.threadId);
      report.cleanup = await archiveThreads(realCodex, created);
    }
    await writeFile(path.join(artifactDirectory, "report.json"), JSON.stringify(report, null, 2));
    await new Promise(resolve => server.close(resolve));
    await rm(temporary, { recursive: true, force: true });
    console.log(JSON.stringify({ success: report?.success, version: manifest.version, artifacts: artifactDirectory,
      cliRequests: report?.cliRequests?.length, settings: report?.settings, approval: report?.approval, reload: report?.reload, attachments: report?.attachments }, null, 2));
  }
}

async function runProxy() {
  const executableArgs = args.slice(args.indexOf("--workspace") + 2);
  const child = spawn(option("proxy-cli"), executableArgs, { stdio: ["pipe", "pipe", "inherit"] });
  const requests = new Map();
  const log = option("fixture-log");
  const input = readline.createInterface({ input: process.stdin }).on("line", line => {
    try {
      const request = JSON.parse(line);
      appendFileSync(log, JSON.stringify(redact(request)) + "\n");
      if (request.id !== undefined && request.method) requests.set(String(request.id), { method: request.method, ephemeral: request.params?.ephemeral === true });
    } catch {}
    child.stdin.write(line + "\n");
  }).on("close", () => child.stdin.end());
  readline.createInterface({ input: child.stdout }).on("line", line => {
    try {
      const response = JSON.parse(line);
      const request = requests.get(String(response.id));
      if (request?.method === "thread/start" && response.result?.thread?.id) {
        appendFileSync(log, JSON.stringify({ event: "thread-created", threadId: response.result.thread.id, ephemeral: request.ephemeral }) + "\n");
      }
    } catch {}
    process.stdout.write(line + "\n");
  });
  process.on("SIGTERM", () => child.kill("SIGTERM"));
  process.on("SIGINT", () => child.kill("SIGINT"));
  await new Promise(resolve => child.once("exit", (code, signal) => { process.exitCode = code ?? (signal ? 1 : 0); input.close(); resolve(); }));
}

async function archiveThreads(executable, threadIds) {
  if (!threadIds.length) return [];
  const child = spawn(executable, ["app-server"], { stdio: ["pipe", "pipe", "ignore"] });
  const pending = new Map();
  const input = readline.createInterface({ input: child.stdout });
  input.on("line", line => {
    try { const response = JSON.parse(line); pending.get(String(response.id))?.(response); } catch {}
  });
  const request = (method, params) => {
    const id = randomUUID();
    const result = new Promise(resolve => pending.set(id, resolve));
    child.stdin.write(JSON.stringify({ id, method, params }) + "\n");
    return withTimeout(result, 5000, "Archive cleanup timed out").finally(() => pending.delete(id));
  };
  try {
    const initialized = await request("initialize", { clientInfo: { name: "codex_remote_smoke_cleanup", version: "1.0" }, capabilities: {} });
    if (initialized.error) return threadIds.map(threadId => ({ threadId, error: initialized.error }));
    child.stdin.write(JSON.stringify({ method: "initialized" }) + "\n");
    return await Promise.all(threadIds.map(async threadId => {
      try { return { threadId, ...await request("thread/archive", { threadId }) }; }
      catch (error) { return { threadId, error: { message: error.message } }; }
    }));
  } catch (error) { return threadIds.map(threadId => ({ threadId, error: { message: error.message } })); }
  finally { child.kill("SIGTERM"); input.close(); }
}

async function runFixture() {
  const executableArgs = args.filter((value, index) => index > args.indexOf("--workspace") + 1);
  if (executableArgs.includes("--version")) { console.log("codex-cli 0.162.0"); return; }
  if (!executableArgs.includes("app-server")) process.exit(2);
  const workspace = option("workspace");
  const log = option("fixture-log");
  const threads = new Map();
  const ephemeral = new Set();
  const approvals = new Map();
  const send = message => process.stdout.write(JSON.stringify(message) + "\n");
  const notify = (method, params) => send({ method, params });
  const model = { id: "gpt-5.3-codex", model: "gpt-5.3-codex", displayName: "Fixture Codex", description: "Local protocol fixture; no model requests", hidden: false,
    isDefault: true, inputModalities: ["text", "image"], supportsPersonality: true, defaultReasoningEffort: "medium",
    supportedReasoningEfforts: ["low", "medium", "high", "xhigh"].map(reasoningEffort => ({ reasoningEffort, description: reasoningEffort })) };
  const config = { model: model.model, model_provider: "openai", model_reasoning_effort: "medium", approval_policy: "on-request", sandbox_mode: "workspace-write", features: {}, profiles: {} };
  const session = thread => ({ thread, model: model.model, modelProvider: "openai", cwd: workspace, approvalPolicy: "on-request", approvalsReviewer: "user", reasoningEffort: "medium", serviceTier: null,
    activePermissionProfile: null, disabledPluginIds: [], instructionSources: [], runtimeWorkspaceRoots: [],
    sandbox: { type: "workspaceWrite", writableRoots: [workspace], networkAccess: false, excludeTmpdirEnvVar: false, excludeSlashTmp: false } });
  const thread = id => ({ id, preview: "", modelProvider: "openai", createdAt: Math.floor(Date.now() / 1000), updatedAt: Math.floor(Date.now() / 1000),
    cwd: workspace, cliVersion: "0.162.0", source: "vscode", gitInfo: null, name: null, path: null, status: { type: "idle" }, turns: [] });
  const input = readline.createInterface({ input: process.stdin });
  input.on("line", line => {
    let request;
    try { request = JSON.parse(line); } catch { return; }
    appendFileSync(log, JSON.stringify(redact(request)) + "\n");
    if (!request.method && approvals.has(String(request.id))) {
      approvals.get(String(request.id)).resolve(); approvals.delete(String(request.id)); return;
    }
    if (request.id === undefined || !request.method) return;
    const params = request.params || {};
    let result;
    switch (request.method) {
      case "initialize": result = { userAgent: "codex-fixture/0.162.0", platformFamily: "unix", platformOs: "linux" }; break;
      case "config/read": result = { config, origins: {}, layers: [] }; break;
      case "fs/readFile": {
        if (!params.path.startsWith(workspace + path.sep)) { send({ id: request.id, error: { code: -32000, message: "Fixture file does not exist" } }); return; }
        try { result = { dataBase64: readFileSync(params.path).toString("base64") }; }
        catch (error) { send({ id: request.id, error: { code: -32000, message: error.code || "Cannot read fixture file" } }); return; }
        break;
      }
      case "model/list": result = { data: [model], nextCursor: null }; break;
      case "account/read": result = { account: { type: "chatgpt", email: "fixture@example.invalid", planType: "plus" }, requiresOpenaiAuth: false }; break;
      case "account/rateLimits/read": result = { rateLimits: { primary: null, secondary: null, credits: null, planType: "plus" }, rateLimitsByLimitId: {} }; break;
      case "thread/list": result = { data: [...threads.values()].filter(thread => !ephemeral.has(thread.id)), nextCursor: null }; break;
      case "thread/loaded/list": result = { data: [...threads.keys()], nextCursor: null }; break;
      case "thread/start": {
        const created = thread(randomUUID()); threads.set(created.id, created); result = session(created);
        if (params.ephemeral) ephemeral.add(created.id);
        setTimeout(() => notify("thread/started", { thread: created }), 0); break;
      }
      case "thread/resume": {
        if (!threads.has(params.threadId)) threads.set(params.threadId, thread(params.threadId));
        result = session(threads.get(params.threadId));
        // The documented resume contract replays unresolved server requests after the snapshot.
        setTimeout(() => { for (const pending of approvals.values()) if (pending.request.params.threadId === params.threadId) send(pending.request); }, 0);
        break;
      }
      case "thread/read": result = { thread: threads.get(params.threadId) || thread(params.threadId) }; break;
      case "thread/name/set": {
        const current = threads.get(params.threadId);
        if (current) current.name = params.name;
        result = {}; setTimeout(() => notify("thread/name/updated", { threadId: params.threadId, threadName: params.name }), 0); break;
      }
      case "thread/unsubscribe": result = { status: "unsubscribed" }; break;
      case "turn/start": {
        const turn = { id: randomUUID(), status: "inProgress", items: [], error: null };
        result = { turn };
        const current = threads.get(params.threadId);
        if (current) { current.turns.push(turn); current.preview = params.input.find(item => item.type === "text")?.text.trim() || ""; }
        const finish = () => {
          const text = params.outputSchema?.properties?.title ? JSON.stringify({ title: "Smoke conversation", description: "Official extension smoke test" }) : "CODEX_REMOTE_EXTENSION_FIXTURE_OK";
          const item = { id: randomUUID(), type: "agentMessage", text, phase: "final_answer" };
          notify("item/started", { threadId: params.threadId, turnId: turn.id, item: { ...item, text: "" } });
          notify("item/agentMessage/delta", { threadId: params.threadId, turnId: turn.id, itemId: item.id, delta: item.text });
          notify("item/completed", { threadId: params.threadId, turnId: turn.id, item });
          Object.assign(turn, { status: "completed", items: [item] });
          notify("turn/completed", { threadId: params.threadId, turn });
        };
        setTimeout(() => {
          notify("turn/started", { threadId: params.threadId, turn });
          if (args.includes("--fixture-approval") && !params.outputSchema) {
            const id = randomUUID();
            const command = { id: randomUUID(), type: "commandExecution", command: "printf fixture-only", cwd: workspace, status: "inProgress", commandActions: [], aggregatedOutput: null, exitCode: null, durationMs: null };
            turn.items.push(command);
            notify("item/started", { threadId: params.threadId, turnId: turn.id, item: command });
            const request = { id, method: "item/commandExecution/requestApproval", params: {
              threadId: params.threadId, turnId: turn.id, itemId: command.id, command: command.command, cwd: workspace,
              reason: "Safe protocol fixture: no command executes.",
            } };
            approvals.set(id, { request, resolve: () => { notify("serverRequest/resolved", { threadId: params.threadId, requestId: id }); finish(); } });
            send(request);
          } else finish();
        }, 300);
        break;
      }
      case "skills/list": result = { data: [{ cwd: workspace, skills: [], errors: [] }] }; break;
      case "app/list": case "mcpServerStatus/list": case "experimentalFeature/list": result = { data: [], nextCursor: null }; break;
      // These experimental responses are published by app-server generate-json-schema --experimental.
      case "collaborationMode/list": result = { data: [{ name: "Default", mode: "default", model: model.model, reasoning_effort: "medium" }, { name: "Plan", mode: "plan", model: model.model, reasoning_effort: "medium" }] }; break;
      case "permissionProfile/list": result = { data: [], nextCursor: null }; break;
      case "hooks/list": result = { data: [] }; break;
      case "plugin/installed": result = { marketplaces: [], marketplaceLoadErrors: [] }; break;
      case "experimentalFeature/enablement/set": result = { enablement: params.enablement }; break;
      case "remoteControl/status/read": result = { installationId: "fixture", serverName: "fixture", status: "disabled", environmentId: null }; break;
      case "configRequirements/read": result = { requirements: null }; break;
      case "config/value/write": case "config/batchWrite": result = { status: "ok", version: "fixture", filePath: null, overriddenMetadata: null }; break;
      case "thread/archive": threads.delete(params.threadId); result = {}; break;
      default: send({ id: request.id, error: { code: -32601, message: `Fixture does not implement documented method ${request.method}` } }); return;
    }
    send({ id: request.id, result });
  });
  await new Promise(resolve => input.once("close", resolve));
}

async function readRequests(filename) {
  try { return (await readFile(filename, "utf8")).trim().split("\n").filter(Boolean).map(line => JSON.parse(line)); }
  catch (error) { if (error.code === "ENOENT") return []; throw error; }
}

function redact(value) {
  if (typeof value === "string") return redactText(value);
  if (Array.isArray(value)) return value.map(redact);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, /token|authorization|api.?key|secret|password/i.test(key) ? "[redacted]" : redact(item)]));
}

function redactText(value) {
  return value.replace(/Bearer\s+[A-Za-z0-9_.~-]+/gi, "Bearer [redacted]")
    .replace(/\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b/g, "[redacted]")
    .replace(/((?:access_token|refresh_token|api_key|authorization)\s*[=:]\s*["']?)[^\s"',&}]+/gi, "$1[redacted]");
}

function isComposerTurn(request, text) {
  return request.method === "turn/start" && (request.params?.turnTrigger === "composer" || request.params?.input?.some(item => item.type === "text" && item.text.trim() === text));
}

async function withTimeout(promise, milliseconds, message) {
  let timer;
  try { return await Promise.race([promise, new Promise((_resolve, reject) => { timer = setTimeout(() => reject(new Error(message)), milliseconds); })]); }
  finally { clearTimeout(timer); }
}

async function waitUntil(predicate, milliseconds, message) {
  const deadline = Date.now() + milliseconds;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(message);
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}
