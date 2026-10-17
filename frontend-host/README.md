# Official frontend host

This directory supplies the browser host expected by the unmodified official
`OpenAI.chatgpt` VS Code WebView. The chat interface, state machine, streaming,
approval UI and app-server requests remain official code.

`adapters.json` records the exact frontend version, RPC bundle SHA-256 and its
export names. Both registered versions use the official capability RPC runtime
in the same JavaScript realm, retaining byte arrays and capability references.
Updating the version number alone is insufficient: each new bundle needs a
verified binding and UI integration checks. No official bundle is committed.

## Resource layout

Import an installed extension or its Marketplace VSIX:

```sh
node scripts/import-official-frontend.mjs --source /path/to/openai.chatgpt-extension
node scripts/import-official-frontend.mjs --source /path/to/package.vsix
```

The importer accepts ZIP and gzip wrapped VSIX, rejects path traversal and
symlinks, checks the publisher and registered RPC bundle hash, and writes to
`.data/official-frontends/<version>/`. Existing output is preserved; use
`--output` for a different destination. The official `webview/` stays intact.
`index.html` is a separate wrapper; `host/` is a copy of this directory.

Android serves the official WebView resources at its per-service HTTPS origin.
It injects `shim.js` before official module scripts, `host.mjs` as a module, and
`theme.css`. `app-server-inbox.mjs` and `adapters.json` must be served alongside
the host. The document base points at the official WebView resource root.
Required metadata:

- `codex-extension-version`: registered plugin version.
- `codex-session-id`: unique view session identifier.
- `codex-build-flavor`: `prod`.
- `codex-remote-storage-key`: stable remote service scope.

Set `body.vscode-dark` or `body.vscode-light`. Theme variables live on the HTML
root because official CSS references them there. Root startup route `/` is the
official sidebar entry; it can take several seconds to initialize.

## Native bridge

JavaScript calls `CodexRemoteHost.postMessage(JSON.stringify(message))`. Native
delivers objects to `window.codexRemoteReceive(message)`.

| Direction | Message |
| --- | --- |
| JS → Native | `{type:'ready'}` |
| JS → Native | `{type:'rpc',request:{id,method,params}}` |
| JS → Native | `{type:'notification',request:{method,params}}` |
| JS → Native | `{type:'response',response:{id,result or error}}` |
| JS → Native | `{type:'host-request',id,method,params}` |
| JS → Native | `{type:'log',level,message}` |
| Native → JS | `{type:'initialized',result,runningVersion,home,workspace?,serviceName}` |
| Native → JS | `{type:'rpc-message',message}` |
| Native → JS | `{type:'host-response',id,result or error}` |
| Native → JS | `{type:'connection',state,message?}` |

Agent owns app-server `initialize`/`initialized`. `result` is its raw initialize
result; `home` is the remote user's home and `result.codexHome` is the configured
Codex home. `workspace` is the absolute remote working directory selected by
Android. Before initializing the official UI, the host saves that directory
and updates its single local project. This replaces any previously cached
home-directory project; it does not change the remote user's home. Server
requests and notifications wait for official UI readiness;
client responses flow immediately so bootstrap requests can finish. Native
only emits `resync-required` for a live view whose replay cursor is lost. The
host reloads in that case; a cold attach must not cause a reload loop.

Native host methods: `fs/read`, `fs/write`, `fs/list`, `fs/stat`, `fs/mkdir`,
`clipboard/read`, `clipboard/write`, `open-url`, and `file/open`.
File content uses `{path,encoding:'utf8'|'base64'}` and `{content}`. Clipboard
content uses `{text}`. `file/open` shows a remote file preview.

Persisted frontend settings, global state, view state and thread memberships
use browser storage scoped to the remote service. Capability RPC remains
inside the page; only app-server JSON-RPC and native host requests cross the
Android bridge.

## Validation and current limits

```sh
node --test frontend-host/host.test.mjs scripts/import-official-frontend.test.mjs
node scripts/smoke-official-frontend.mjs --codex /path/to/codex \
  --send 'Reply with exactly CODEX_REMOTE_FRONTEND_OK. Do not use any tools.' \
  --expect CODEX_REMOTE_FRONTEND_OK
node scripts/smoke-official-frontend.mjs --codex /path/to/codex \
  --send 'Protocol approval fixture.' --fixture approval
```

The browser smoke needs Playwright Chromium. Set `CODEX_PLAYWRIGHT_MODULE` to
an externally installed Playwright entry module if it is not installed in the
repository. `--frontend` chooses another imported version. Fixtures `stream`,
`approval`, and `error` exercise the real official UI using explicit protocol
fixtures for the turn; they do not invoke a model or run the displayed command.
Without a fixture the smoke uses a real app-server and model. Only threads
created by that invocation are archived during cleanup. Existing threads and
system authentication/configuration are not deliberately modified.
`--reconnect` emits disconnected/connected host events after the reply and
asserts that the official frontend requests `thread/resume`; this tests the
frontend recovery path, not a physical Gateway disconnect.

Current supported scope is remote local chat. Git workers, cloud tasks,
GitHub integration, voice, desktop windows and VS Code commands are not
implemented. Unsupported host operations report errors instead of simulated
success. A compatible plugin binding does not guarantee every Codex CLI version
or every official menu is supported.
