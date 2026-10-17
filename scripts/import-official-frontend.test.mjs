import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, rm, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import { fileURLToPath } from "node:url";

const run = promisify(execFile);
const importer = fileURLToPath(new URL("./import-official-frontend.mjs", import.meta.url));

for (const gzip of [false, true]) {
  test(`reads ${gzip ? "gzip wrapped" : "plain"} VSIX before rejecting an unverified version`, async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "codex-import-test-"));
    try {
      const archive = path.join(directory, "package.vsix");
      await run("python3", ["-c", `import gzip,io,json,sys,zipfile
buffer=io.BytesIO()
with zipfile.ZipFile(buffer,'w') as archive:
    archive.writestr('extension/package.json',json.dumps({'publisher':'OpenAI','name':'chatgpt','version':'unverified'}))
payload=buffer.getvalue()
open(sys.argv[1],'wb').write(gzip.compress(payload) if sys.argv[2]=='true' else payload)
`, archive, String(gzip)]);
      await assert.rejects(run(process.execPath, [importer, "--source", archive, "--output", path.join(directory, "output")]), error => /No verified host binding for plugin unverified/.test(error.stderr));
      await assert.rejects(stat(path.join(directory, "output")), { code: "ENOENT" });
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
}

test("rejects archive traversal before extracting files", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "codex-import-test-"));
  try {
    const archive = path.join(directory, "unsafe.vsix");
    await run("python3", ["-c", "import sys,zipfile\nwith zipfile.ZipFile(sys.argv[1],'w') as archive: archive.writestr('extension/webview/../../escape','unsafe')", archive]);
    await assert.rejects(run(process.execPath, [importer, "--source", archive]), error => /Unsafe archive path/.test(error.stderr));
  } finally { await rm(directory, { recursive: true, force: true }); }
});
