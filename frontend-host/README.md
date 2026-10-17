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
`theme.css`. The host's `.mjs` helpers and `adapters.json` must be served alongside
the host. The document base points at the official WebView resource root.
Required metadata:

- `codex-extension-version`: registered plugin version.
- `codex-session-id`: unique view session identifier.
- `codex-build-flavor`: `prod`.
- `codex-remote-storage-key`: stable remote service scope.

Set HTML `data-codex-theme` to `dark`, `light`, or `anthropic`, plus the matching
`body.vscode-dark`/`body.vscode-light` class and `data-vscode-theme-kind` attribute
(Anthropic uses the official light mode). Theme variables live on the HTML
root because official CSS references them there. Root startup route `/` is the
official sidebar entry; it can take several seconds to initialize.
The native WebView must fill its available viewport. The host sets HTML, body
and the mount root to `height: 100%`; official components retain their layout.

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
| JS → Native | `{type:'thread-conflict',threadId,message,retryable:true}` |
| JS → Native | `{type:'settings-navigation',inSettings:boolean}` |
| JS ↔ Native | `{type:'coordination',message}` |
| Native → JS | `{type:'initialized',result,runningVersion,home,workspace?,serviceName,clientCoordination}` |
| Native → JS | `{type:'rpc-message',message}` |
| Native → JS | `{type:'host-response',id,result or error}` |
| Native → JS | `{type:'connection',state,message?}` |
| Native → JS | `{type:'theme',theme:'dark' or 'light' or 'anthropic'}` |
| Native → JS | `{type:'exit-settings'}` |
| Native → JS | `{type:'font-scale',scale:number}` |

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
The first connected event is a snapshot; duplicate connected events do not
restart official thread recovery. A genuine reconnect triggers recovery once.
On Linux, `clientCoordination: true` enables the official owner/follower protocol.
Agent connects to the existing same-user official IPC router at
`$CODEX_HOME/ipc/ipc.sock` (or the official legacy temporary socket). Gateway and
Android relay live IPC JSON; Agent handles the 4-byte little-endian framing.
`client-coordination.mjs` adapts the official host capability to this transport,
including discovery, targeted follower operations, approvals, and stream state.
Roles and conversation behavior remain in the unmodified official WebView.
The desktop plugin must be open under the same remote user and Codex home.
This needs matching updated Gateway, Agent and App; an older Agent falls back
to local operation. Agent does not create an IPC router. Independent CLI writers
that do not participate in official client coordination still report the original
writer error and may need to release the thread. There is no forced takeover or
automatic fork. Read/unread markers remain local because the desktop account
and execution-host identity context is not exposed by this host.

Coordination is bound to the current renderer session and process epoch. It is
never buffered or replayed: disconnect rejects outstanding follower calls and
removes the IPC peer without stopping the Agent-owned app-server or active task.
Do not retry a mutation with an unknown outcome automatically. Gateway channel
authentication and service/owner isolation also apply to coordination frames.
Theme messages update CSS and the official theme attribute without reloading
the page, changing the connection or submitting the current draft.
Font-scale messages set the standard VS Code host font tokens and send the official
`chat-font-settings` message for chat and code text (13 px at 100%). The latest
value is replayed when the official UI becomes ready, including cold starts.
Android sends the product of the App preference and system font scale. No official
CSS selectors or components are overridden. Native and host surfaces use the
same foreground mixes for subtle borders, secondary text and input backgrounds.

Native host methods: `fs/read`, `fs/write`, `fs/list`, `fs/stat`, `fs/mkdir`,
`clipboard/read`, `clipboard/write`, `open-url`, and `file/open`.
File content uses `{path,encoding:'utf8'|'base64'}` and `{content}`. Clipboard
content uses `{text}`. `file/open` shows a remote file preview.

Persisted frontend settings, global state, view state and thread memberships
use browser storage scoped to the remote service. Capability RPC remains
inside the page; app-server JSON-RPC, coordination JSON and native host requests cross the
Android bridge.

The App is the primary window for this storage scope. Its persisted-state sync
advertises `canWritePrimaryWindowTabPersistence`, and record updates are merged
by the official exported function before the complete value is saved and echoed.
This preserves official composer drafts when the chat route unmounts for settings.

Settings navigation reads the official current route through the version bindings
in `adapters.json`; both additional bundles are checked against SHA-256 before
import. Entering settings remembers the source route and state. Exiting calls the
official `prepareNavigation` guard, then sends `navigate-to-route` to that source
(or `/` when there is no source). Android maps its toolbar and system Back to this
operation, with native modals and the keyboard taking priority. JavaScript confirm
dialogs use the native theme and retain the official message. No official source,
DOM structure or React component is rewritten. Subscriptions are disposed on
`pagehide`; a replaced official scope discards the previous source.

## Validation and current limits

```sh
node --test frontend-host/*.test.mjs scripts/import-official-frontend.test.mjs
node scripts/smoke-official-frontend.mjs --codex /path/to/codex \
  --settings-return --model-settings --fixture stream --send CODEX_REMOTE_SETTINGS_TEST
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
`--fixture resume-conflict --reconnect` checks writer-error propagation and
duplicate-connection suppression using a fixture on its own test thread.
`--fonts --font-scale 1.15` checks startup and live chat/code font sizes, draft
preservation and the absence of document reloads or extra thread/turn operations.
`--artifacts <directory>` selects the output directory for screenshots.
`--themes` checks all three palettes and saves screenshots while preserving an
unsent draft and the same browser document.
Conversation fixtures also check root viewport height and the composer position;
`--height 621` exercises a short mobile viewport.

Current supported scope is remote local chat. Git workers, cloud tasks,
GitHub integration, voice, desktop windows and VS Code commands are not
implemented. Unsupported host operations report errors instead of simulated
success. A compatible plugin binding does not guarantee every Codex CLI version
or every official menu is supported.

## Phone attachments and previews

The official `pick-file` / `pick-files` requests delegate to native `files/pick`.
Android uses the system document picker (including image filters and multiple
selection), uploads selected bytes using the existing Gateway filesystem API,
and returns official `{label,path,fsPath}` descriptors containing remote paths.
Cancellation returns an empty list (or `file: null`). Human selection has no host
request timeout; HTTP uploads retain their network timeouts. Destroying the
renderer cancels pending selection. Folder requests use the remote directory
selector. `read-file-metadata` maps the native stat result to official `sizeBytes`
and `isFile` fields. The composer and attachment state stay entirely official.

Chat links using `openIn.open` and the native Files page share a binary-aware
preview dialog. It reads raw download bytes, decodes supported raster images with
a bounded bitmap size, and decodes UTF-8 only after rejecting binary data. No
Gateway/Agent protocol change or official resource patch is required.

Run `frontend:smoke -- --attachments --fixture stream --send ATTACHMENT_TEST` to
check official attachment selection/cancellation, thumbnails, a fixture turn with
both image and file inputs, and a Markdown preview link. Native instrumentation
checks system picker cancellation, byte-preserving uploads and image rendering.
