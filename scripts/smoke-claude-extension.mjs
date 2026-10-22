#!/usr/bin/env node
// Runs the unmodified official package with an isolated, non-networked CLI fixture.
// It never launches the bundled Claude binary or sends a real model request.
// Usage: node scripts/smoke-claude-extension.mjs --extension /path/to/official/package --approval --interactions
// Requires Playwright (or CODEX_PLAYWRIGHT_MODULE) and a Chromium installation.
// --browser/CLAUDE_SMOKE_BROWSER can select an existing Chromium executable.
// Exercises the official sidebar only; external CLI/MCP-initiated diffs are outside this test.
import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { appendFileSync, createReadStream } from 'node:fs';
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

const args = process.argv.slice(2), repository = path.resolve(import.meta.dirname, '..');
const option = (name, fallback) => { const index = args.indexOf('--' + name); return index < 0 ? fallback : args[index + 1]; };
if (args.includes('--fixture')) await fixture();
else await smoke();

async function fixture() {
  const log = value => appendFileSync(process.env.CLAUDE_SMOKE_LOG, JSON.stringify(value) + '\n');
  const send = value => process.stdout.write(JSON.stringify(value) + '\n');
  log({ event: 'spawn', args: args.slice(1) });
  if (args.includes('--version')) { console.log('2.1.306 (Claude Code fixture)'); return; }
  if (args.includes('auth') && args.includes('status')) { send({ loggedIn: true, authMethod: 'api_key', apiProvider: 'anthropic' }); return; }
  const session = randomUUID();
  for await (const line of readline.createInterface({ input: process.stdin })) {
    let message; try { message = JSON.parse(line); } catch { continue; }
    log({ event: 'input', message });
    if (message.type === 'control_request') {
      const response = message.request?.subtype === 'initialize' ? {
        commands: [], models: [{ value: 'claude-sonnet-4-6', displayName: 'Fixture Sonnet', description: 'Local smoke fixture' }], agents: [],
        account: { apiProvider: 'anthropic' }, permissionMode: 'default', output_style: 'default', available_output_styles: ['default'],
        slash_commands: [], settings: {}, claude_settings: {}, fast_mode: false,
      } : message.request?.subtype === 'get_settings' ? { effective: {}, sources: [], errors: [] } : {};
      send({ type: 'control_response', response: { subtype: 'success', request_id: message.request_id, response } });
    } else if (message.type === 'user') {
      send({ type: 'system', subtype: 'init', cwd: process.cwd(), session_id: session, tools: ['Read', 'Edit', 'Write'], mcp_servers: [],
        model: 'claude-sonnet-4-6', permissionMode: 'default', apiKeySource: 'user', slash_commands: [], uuid: randomUUID() });
      const text = typeof message.message?.content === 'string' ? message.message.content : message.message?.content?.filter(item => item.type === 'text').map(item => item.text).join(' ') || '';
      if (text.includes('SMOKE_EDIT') || text.includes('SMOKE_WRITE')) {
        const input = text.includes('SMOKE_WRITE') ? { file_path: path.join(process.cwd(), 'approval.txt'), content: 'approved content\n' } :
          { file_path: path.join(process.cwd(), 'approval.txt'), old_string: 'before', new_string: 'after', replace_all: false };
        const tool = text.includes('SMOKE_WRITE') ? 'Write' : 'Edit';
        send({ type: 'control_request', request_id: 'smoke-approval', request: { subtype: 'can_use_tool', tool_name: tool, input,
          permission_suggestions: [], tool_use_id: 'smoke-tool' } });
      } else finish('CLAUDE_REMOTE_SMOKE_OK');
    } else if (message.type === 'control_response' && message.response.request_id === 'smoke-approval') {
      finish('CLAUDE_PERMISSION_' + String(message.response.response?.behavior).toUpperCase());
    }
  }
  function finish(text) {
    send({ type: 'assistant', uuid: randomUUID(), session_id: session, message: { id: randomUUID(), type: 'message', role: 'assistant', model: 'claude-sonnet-4-6',
      content: [{ type: 'text', text }], stop_reason: 'end_turn', stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 } } });
    send({ type: 'result', subtype: 'success', is_error: false, duration_ms: 1, duration_api_ms: 0, num_turns: 1, result: text, session_id: session,
      total_cost_usd: 0, usage: { input_tokens: 1, output_tokens: 1 }, modelUsage: {}, permission_denials: [], uuid: randomUUID() });
  }
}

async function smoke() {
  const extensionPath = option('extension', process.env.CLAUDE_EXTENSION_DIRECTORY);
  assert.ok(extensionPath, 'Pass --extension /path/to/the/installed/official/Claude/package');
  const extensionDirectory = await realpath(extensionPath);
  const artifacts = path.resolve(option('artifacts', '.data/claude-smoke'));
  const manifest = JSON.parse(await readFile(path.join(extensionDirectory, 'package.json'), 'utf8'));
  assert.equal((manifest.publisher + '.' + manifest.name).toLowerCase(), 'anthropic.claude-code');
  const originalFiles = [manifest.main, 'webview/index.js', 'webview/index.css', 'resources/native-binary/claude'];
  const checksums = async () => Object.fromEntries(await Promise.all(originalFiles.map(async file => {
    const hash = createHash('sha256');
    for await (const bytes of createReadStream(path.join(extensionDirectory, file))) hash.update(bytes);
    return [file, hash.digest('hex')];
  })));
  const beforeHashes = await checksums();
  const temporary = await mkdtemp(path.join(tmpdir(), 'claude-extension-smoke-'));
  const dir = name => path.join(temporary, name);
  await Promise.all(['home', 'config', 'state', 'workspace'].map(name => mkdir(dir(name))));
  await mkdir(artifacts, { recursive: true });
  const wrapper = dir('fixture-wrapper');
  const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
  await writeFile(wrapper, '#!/bin/sh\nexec ' + quote(process.execPath) + ' ' + quote(fileURLToPath(import.meta.url)) + ' --fixture "$@"\n');
  await chmod(wrapper, 0o755);
  await writeFile(path.join(dir('state'), 'configuration.json'), JSON.stringify({
    'claudeCode.claudeProcessWrapper': wrapper, 'claudeCode.hideOnboarding': true, 'claudeCode.usePythonEnvironment': false,
  }));
  const workspace = path.resolve(option('workspace', dir('workspace')));
  if (workspace === dir('workspace')) await writeFile(path.join(workspace, 'approval.txt'), 'before\n');
  else assert.equal(await readFile(path.join(workspace, 'approval.txt'), 'utf8'), 'before\n', 'Shared fixture workspace must be pre-created');
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/(ANTHROPIC|CLAUDE|CODEX|OPENAI|TOKEN|API_KEY|SECRET)/i.test(key)));
  Object.assign(env, { HOME: dir('home'), CLAUDE_CONFIG_DIR: dir('config'), CLAUDE_SMOKE_LOG: path.join(artifacts, 'cli.jsonl'),
    ANTHROPIC_API_KEY: 'sk-ant-local-fixture-only', ANTHROPIC_BASE_URL: 'http://127.0.0.1:1',
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1', DISABLE_TELEMETRY: '1', DISABLE_ERROR_REPORTING: '1' });
  await writeFile(env.CLAUDE_SMOKE_LOG, '');
  const imageFixture = dir('fixture.png'), textFixture = dir('fixture.txt');
  await writeFile(imageFixture, Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZFsAAAAASUVORK5CYII=', 'base64'));
  await writeFile(textFixture, 'Fixture attachment 中文🙂\n');
  let selectedFiles = [], pickerCount = 0;
  let html = '', runtime, browser, page, ready = false;
  const events = [], errors = [], queue = [], surfaces = new Map(), resources = new Map();
  const wait = async (predicate, message, timeout = 30000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (await predicate()) return; await new Promise(resolve => setTimeout(resolve, 50)); }
    throw new Error(message);
  };
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname === '/') { response.setHeader('content-type', 'text/html'); response.end(html); return; }
      const prefix = '/extension/' + encodeURIComponent(manifest.version) + '/';
      if (!url.pathname.startsWith(prefix)) { response.writeHead(404).end(); return; }
      const file = await realpath(path.resolve(extensionDirectory, decodeURIComponent(url.pathname.slice(prefix.length))));
      if (!file.startsWith(extensionDirectory + path.sep)) { response.writeHead(403).end(); return; }
      const bytes = await readFile(file); resources.set(path.relative(extensionDirectory, file), createHash('sha256').update(bytes).digest('hex'));
      const mime = { '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
      response.setHeader('content-type', mime[path.extname(file)] || 'application/octet-stream'); response.end(bytes);
    } catch { response.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  try {
    runtime = fork(path.join(repository, 'agent/src/extension-host/runtime.cjs'), [], { env, detached: true, stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
    runtime.on('error', error => errors.push({ type: 'process', message: error.message }));
    runtime.stdout.on('data', bytes => events.push({ type: 'stdout', message: bytes.toString() }));
    runtime.stderr.on('data', bytes => events.push({ type: 'stderr', message: bytes.toString() }));
    const deliver = payload => page.evaluate(value => window.__codexExtensionHost.receive(value), payload).catch(error => errors.push({ type: 'delivery', message: error.message }));
    runtime.on('message', event => {
      if (event.type === 'html' && event.surfaceId === 'main') html = event.payload.html;
      else if (event.type === 'message' && event.surfaceId === 'main') { if (ready) void deliver(event.payload); else queue.push(event.payload); }
      else if (event.type === 'host-request') {
        if (event.payload.method === 'showOpenDialog') pickerCount++;
        runtime.send({ type: 'host-response', id: event.payload.id,
          result: event.payload.method === 'clipboard.read' ? '' : event.payload.method === 'showOpenDialog' ? selectedFiles.map(value => pathToFileURL(value).href) : undefined }, () => {});
      }
      else if (event.type === 'surface') {
        if (event.payload.action === 'close') surfaces.delete(event.surfaceId); else surfaces.set(event.surfaceId, { ...surfaces.get(event.surfaceId), ...event.payload });
      }
      events.push(event);
    });
    runtime.send({ type: 'start', options: { extensionDirectory, stateDirectory: dir('state'), workspace,
      clientId: randomUUID(), hostId: randomUUID(), origin, main: manifest.main, toolId: 'claude', sidebarViewId: 'claudeVSCodeSidebar', language: 'en' } });
    await wait(() => html, 'Official extension did not produce HTML');
    const { chromium } = await import(process.env.CODEX_PLAYWRIGHT_MODULE || 'playwright');
    browser = await chromium.launch({ headless: true, executablePath: option('browser', process.env.CLAUDE_SMOKE_BROWSER) });
    const context = await browser.newContext({ viewport: { width: 412, height: 915 }, hasTouch: true, isMobile: true });
    await context.route('**/*', route => route.request().url().startsWith(origin) || route.request().url().startsWith('data:') ? route.continue() : route.abort());
    await context.exposeBinding('smokePost', (_source, payload) => runtime.send({ type: 'message', surfaceId: 'main', payload }));
    await context.exposeBinding('smokeReady', async () => { ready = true; for (const message of queue.splice(0)) await deliver(message); });
    const themeCss = await readFile(path.join(repository, 'extension-webview-host/theme.css'), 'utf8');
    const bridge = await readFile(path.join(repository, 'extension-webview-host/bridge.js'), 'utf8');
    await context.addInitScript({ content: `
      window.CodexExtensionBridge = {
        getState: () => localStorage.getItem('state') || 'null', setState: (_id,json) => localStorage.setItem('state',json),
        postMessage: (_id,json) => window.smokePost(JSON.parse(json)), ready: () => window.smokeReady()
      };
      window.__codexExtensionConfig = { documentId:'smoke',theme:'dark',fontScale:1,themeCss:${JSON.stringify(themeCss)} };
      ${bridge}
    ` });
    page = await context.newPage();
    page.on('filechooser', chooser => { pickerCount++; void chooser.setFiles(selectedFiles); });
    page.on('pageerror', error => errors.push({ type: 'pageerror', message: error.stack || error.message }));
    page.on('console', message => { if (['error', 'warning'].includes(message.type())) errors.push({ type: message.type(), message: message.text() }); });
    await page.goto(origin);
    const composer = page.locator('textarea, [contenteditable]:not([contenteditable="false"])').first();
    await composer.waitFor({ state: 'visible', timeout: 30000 });
    await composer.fill('Unsent fixture draft');
    assert.equal(await composer.evaluate(element => element.value ?? element.innerText), 'Unsent fixture draft');
    await page.screenshot({ path: path.join(artifacts, 'chat.png'), fullPage: true });
    if (args.includes('--interactions')) {
      selectedFiles = [imageFixture, textFixture];
      await page.getByTitle('Add', { exact: true }).click();
      await page.getByText('Upload from computer', { exact: true }).click();
      await wait(() => pickerCount > 0, 'Attachment picker did not open');
      await page.getByTitle('Remove attachment', { exact: true }).nth(1).waitFor();
      await writeFile(path.join(artifacts, 'attachments.txt'), await page.locator('body').innerText());
      await writeFile(path.join(artifacts, 'attachments.html'), await page.content());
      await page.screenshot({ path: path.join(artifacts, 'attachments.png'), fullPage: true });
      await page.getByTitle('Remove attachment', { exact: true }).first().click();
      await page.getByTitle('Remove attachment', { exact: true }).first().click();
      assert.equal(await page.getByTitle('Remove attachment', { exact: true }).count(), 0);
      selectedFiles = [];
      const previousPicks = pickerCount;
      await page.getByTitle('Add', { exact: true }).click();
      await page.getByText('Upload from computer', { exact: true }).click();
      await wait(() => pickerCount > previousPicks, 'Cancelled attachment picker did not open');
      assert.equal(await page.getByTitle('Remove attachment', { exact: true }).count(), 0);
      assert.equal(await composer.evaluate(element => element.value ?? element.innerText), 'Unsent fixture draft');
      selectedFiles = [imageFixture, textFixture];
      await page.getByTitle('Add', { exact: true }).click();
      await page.getByText('Upload from computer', { exact: true }).click();
      await page.getByTitle('Remove attachment', { exact: true }).nth(1).waitFor();
    }
    if (args.includes('--approval')) {
      let caseIndex = 0;
      for (const [prompt, action, entry] of [['SMOKE_EDIT', 'accept', 'native'], ['SMOKE_WRITE', 'reject', 'native'],
        ['SMOKE_EDIT', 'accept', 'webview'], ['SMOKE_WRITE', 'reject', 'webview'], ['SMOKE_EDIT', 'reject', 'close']]) {
        const prefix = String(++caseIndex) + '-' + entry + '-' + prompt;
        const result = page.getByText('CLAUDE_PERMISSION_' + (action === 'accept' ? 'ALLOW' : 'DENY'), { exact: true });
        const previousResults = await result.count();
        await composer.fill(prompt); await composer.press('Enter');
        await wait(() => [...surfaces.values()].some(value => value.kind === 'diff' && !value.pending), 'No official diff approval appeared');
        const surface = [...surfaces.values()].find(value => value.kind === 'diff' && !value.pending);
        await writeFile(path.join(artifacts, prefix + '-approval.html'), await page.content());
        await writeFile(path.join(artifacts, prefix + '-approval.txt'), await page.locator('body').innerText());
        assert.ok(surface.actions.includes(action));
        assert.equal(surface.diff.originalText, 'before\n');
        if (entry === 'webview') await page.getByRole('button', { name: action === 'accept' ? /^1 Yes$/ : /^2 No$/ }).click();
        else runtime.send({ type: entry === 'close' ? 'close-surface' : 'surface-action', surfaceId: surface.id, action });
        await wait(() => !surfaces.has(surface.id), 'Official extension did not close its diff');
        await wait(async () => await result.count() > previousResults, 'Official permission decision did not reach the CLI fixture');
      }
      const cli = (await readFile(env.CLAUDE_SMOKE_LOG, 'utf8')).trim().split('\n').map(JSON.parse);
      const decisions = cli.filter(event => event.message?.type === 'control_response' && event.message.response.request_id === 'smoke-approval').map(event => event.message.response.response);
      assert.deepEqual(decisions.map(value => value.behavior), ['allow', 'deny', 'allow', 'deny', 'deny']);
      for (const decision of decisions.filter(value => value.behavior === 'allow')) assert.deepEqual(decision.updatedInput, {
        file_path: path.join(workspace, 'approval.txt'), old_string: 'before', new_string: 'after', replace_all: false,
      });
      await writeFile(path.join(artifacts, 'approvals.json'), JSON.stringify({ decisions: decisions.map(value => value.behavior), originalEditsPreserved: true }, null, 2));
    } else {
      await composer.fill('SMOKE_HELLO'); await composer.press('Enter');
      await page.getByText('CLAUDE_REMOTE_SMOKE_OK', { exact: true }).last().waitFor({ timeout: 15000 });
    }
    if (args.includes('--interactions')) {
      await composer.fill('Draft survives view visibility');
      runtime.send({ type: 'visibility', visible: false });
      await new Promise(resolve => setTimeout(resolve, 150));
      runtime.send({ type: 'visibility', visible: true });
      assert.equal(await composer.evaluate(element => element.value ?? element.innerText), 'Draft survives view visibility');
      await page.getByRole('button', { name: 'Session history', exact: true }).click();
      await page.getByRole('dialog', { name: 'Past conversations' }).waitFor();
      await writeFile(path.join(artifacts, 'history.txt'), await page.locator('body').innerText());
      await writeFile(path.join(artifacts, 'history.html'), await page.content());
      await page.keyboard.press('Escape');
      assert.equal(await composer.evaluate(element => element.value ?? element.innerText), 'Draft survives view visibility');
      await page.getByRole('button', { name: 'New session', exact: true }).click();
      await wait(async () => await composer.evaluate(element => element.value ?? element.innerText) === '', 'New session did not clear its composer');
      await composer.fill('Independent new session draft');
      const cli = (await readFile(env.CLAUDE_SMOKE_LOG, 'utf8')).trim().split('\n').map(JSON.parse);
      const user = cli.find(event => event.message?.type === 'user').message.message.content;
      assert.ok(user.some(part => part.type === 'image' && part.source.type === 'base64'));
      assert.ok(user.some(part => part.type === 'document' && part.title === 'fixture.txt'));
      await writeFile(path.join(artifacts, 'interactions.json'), JSON.stringify({ attachmentTypes: user.map(part => part.type), pickerCount,
        removeAndCancel: true, visibilityDraft: true, history: true, newSession: true }, null, 2));
    }
    assert.equal(await readFile(path.join(workspace, 'approval.txt'), 'utf8'), 'before\n', 'The host never writes approvals to project files');
    assert.equal(events.filter(event => event.type === 'error' && event.fatal).length, 0);
    assert.equal(errors.filter(event => event.type === 'pageerror').length, 0, 'No uncaught browser error');
    assert.deepEqual(await checksums(), beforeHashes, 'Official package files must remain byte-for-byte unchanged');
    await writeFile(path.join(artifacts, 'package-hashes.json'), JSON.stringify(beforeHashes, null, 2));
    await page.screenshot({ path: path.join(artifacts, 'result.png'), fullPage: true });
    console.log(JSON.stringify({ ok: true, version: manifest.version, resources: resources.size, approval: args.includes('--approval'), consoleWarnings: errors.length }));
  } finally {
    if (page) {
      await writeFile(path.join(artifacts, 'page.txt'), await page.locator('body').innerText().catch(() => ''));
      await writeFile(path.join(artifacts, 'page.html'), await page.content().catch(() => ''));
      await page.screenshot({ path: path.join(artifacts, 'final.png'), fullPage: true }).catch(() => {});
    }
    await writeFile(path.join(artifacts, 'events.json'), JSON.stringify(events, null, 2));
    await writeFile(path.join(artifacts, 'errors.json'), JSON.stringify(errors, null, 2));
    await writeFile(path.join(artifacts, 'resources.json'), JSON.stringify(Object.fromEntries(resources), null, 2));
    await browser?.close();
    if (runtime?.connected) runtime.send({ type: 'stop' }, () => {});
    await new Promise(resolve => setTimeout(resolve, 500));
    if (runtime?.pid) try { process.kill(-runtime.pid, 'SIGKILL'); } catch {}
    await new Promise(resolve => server.close(resolve));
    await rm(temporary, { recursive: true, force: true });
  }
}
