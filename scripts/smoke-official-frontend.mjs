import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { readFile, stat, readdir, mkdir } from "node:fs/promises";
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
const early = [];
const logs = [];
const outbound = [];
const requestMethods = new Map();
const createdThreads = new Set();
const cleanupReplies = new Map();
let fixtureTurn;
let fixtureThread;
const fixture = option("fixture", "");
assert.ok(["", "approval", "error", "stream"].includes(fixture), "--fixture must be approval, error, or stream");
async function fixtureMessage(message) {
  await page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), message);
}
async function finishFixture() {
  const item = { id: "fixture-agent-message", type: "agentMessage", text: "CODEX_REMOTE_FIXTURE_OK", phase: "final_answer" };
  await fixtureMessage({ method: "item/started", params: { threadId: fixtureThread, turnId: fixtureTurn, item: { ...item, text: "" } } });
  await fixtureMessage({ method: "item/agentMessage/delta", params: { threadId: fixtureThread, turnId: fixtureTurn, itemId: item.id, delta: "CODEX_REMOTE_" } });
  await fixtureMessage({ method: "item/agentMessage/delta", params: { threadId: fixtureThread, turnId: fixtureTurn, itemId: item.id, delta: "FIXTURE_OK" } });
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
  const context = await browser.newContext({ viewport: { width: 412, height: 915 } });
  page = await context.newPage();
  page.on("pageerror", error => logs.push({ type: "pageerror", message: error.stack || error.message }));
  page.on("console", message => { if (message.type() === "error" || message.type() === "warning") logs.push({ type: "console", message: message.text() }); });
  await page.exposeBinding("codexSmokeNative", async (_, raw) => {
    const message = JSON.parse(raw);
    if (message.type === "log") { logs.push(message); return; }
    if (message.type === "ready") {
      for (let attempt = 0; !initializeResult && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
      if (!initializeResult) throw new Error("app-server initialization timed out");
      await page.evaluate(message => window.codexRemoteReceive(message), { type: "initialized", result: initializeResult, runningVersion: version, home: os.homedir(), serviceName: "Local smoke" });
      nativeReady = true;
      for (const message of early.splice(0)) await page.evaluate(message => window.codexRemoteReceive({ type: "rpc-message", message }), message);
    } else if (["rpc", "notification", "response"].includes(message.type)) {
      const request = message.request || message.response;
      if (request.id && request.method) requestMethods.set(request.id, request.method);
      outbound.push({ method: request.method, id: request.id });
      if (fixture && request.method === "turn/start") {
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
          case "fs/read": result = { content: await readFile(params.path, params.encoding === "base64" ? "base64" : "utf8") }; break;
          case "fs/stat": { const info = await stat(params.path); result = { size: info.size, isDirectory: info.isDirectory(), mtimeMs: info.mtimeMs }; break; }
          case "fs/list": result = { entries: (await readdir(params.path, { withFileTypes: true })).map(entry => ({ name: entry.name, path: path.join(params.path, entry.name), isDirectory: entry.isDirectory() })) }; break;
          default: throw new Error(`Smoke host does not implement ${message.method}`);
        }
      } catch (cause) { error = { message: cause.message }; }
      await page.evaluate(message => window.codexRemoteReceive(message), { type: "host-response", id: message.id, result, error });
    }
  });
  await page.addInitScript(() => { window.CodexRemoteHost = { postMessage: message => window.codexSmokeNative(message) }; });
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(Number(option("wait", "12000")));
  await page.locator('[contenteditable="true"]').first().waitFor({ state: "visible", timeout: 15000 });
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
  }
  if (args.includes("--reconnect")) {
    await page.evaluate(() => window.codexRemoteReceive({ type: "connection", state: "disconnected" }));
    await page.waitForTimeout(300);
    await page.evaluate(() => window.codexRemoteReceive({ type: "connection", state: "connected" }));
    await page.waitForTimeout(4000);
    assert.ok(outbound.some(message => message.method === "thread/resume"), "Official UI must resume the current thread after reconnect");
  }
  const text = (await page.locator("body").innerText()).slice(0, 12000);
  if (fixture) {
    assert.ok(outbound.some(message => message.method === "turn/start"), "Official UI must send turn/start");
    assert.ok(text.includes(fixture === "error" ? "CODEX_REMOTE_FIXTURE_ERROR" : "CODEX_REMOTE_FIXTURE_OK"), "Official UI must render the fixture result");
  }
  if (fixture === "approval") {
    assert.deepEqual(logs.find(message => message.type === "fixture-approval-response")?.result, { decision: "accept" }, "Official approval must return its decision through the host");
  }
  if (option("expect", "")) await page.getByText(option("expect"), { exact: true }).first().waitFor({ state: "visible", timeout: 1000 });
  assert.deepEqual(logs.filter(message => message.type === "pageerror"), [], "Official UI must not have uncaught JavaScript errors");
  const artifactDirectory = path.join(repository, ".data/frontend-smoke");
  await mkdir(artifactDirectory, { recursive: true });
  await page.screenshot({ path: path.join(artifactDirectory, "official-frontend.png"), fullPage: true });
  const theme = await page.evaluate(() => ({ className: document.body.className, color: getComputedStyle(document.body).color, background: getComputedStyle(document.body).backgroundColor, foregroundToken: getComputedStyle(document.body).getPropertyValue("--vscode-foreground") }));
  report = { url: page.url(), fixture: fixture || null, theme, text, createdThreads: [...createdThreads], outbound, logs: logs.slice(-100) };
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
}
console.log(JSON.stringify(report, null, 2));
