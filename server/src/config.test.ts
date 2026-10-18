import { describe, expect, it } from "vitest";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defaultDataDirectory, resolveDataDirectory, loadConfig } from "./config.js";

describe("Gateway configuration", () => {
  it("defaults both transfers to 10 MiB and reloads persisted administrator limits before env defaults", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-config-"));
    try {
      const env = { CODEX_REMOTE_DATA_DIR: directory, CODEX_REMOTE_API_TOKEN: "fixture-admin" };
      expect(await loadConfig(env)).toMatchObject({ maxUploadBytes: 10 * 1024 * 1024, maxDownloadBytes: 10 * 1024 * 1024 });
      await writeFile(join(directory, "gateway-settings.json"), JSON.stringify({ maxUploadBytes: 777, maxDownloadBytes: 888 }));
      expect(await loadConfig({ ...env, CODEX_REMOTE_MAX_UPLOAD_BYTES: "222", CODEX_REMOTE_MAX_DOWNLOAD_BYTES: "333" }))
        .toMatchObject({ maxUploadBytes: 777, maxDownloadBytes: 888 });
      await expect(loadConfig({ ...env, CODEX_REMOTE_MAX_UPLOAD_BYTES: "10MiB" })).rejects.toThrow("positive integer");
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

  it("stores standalone data beside the installed binary", () => {
    expect(defaultDataDirectory("/opt/codex-remote/codex-remote-gateway", true))
      .toBe("/opt/codex-remote/data");
  });

  it("keeps an explicit data directory override", () => {
    expect(resolveDataDirectory(
      { CODEX_REMOTE_DATA_DIR: "/srv/codex-data" },
      "/opt/codex-remote/data",
    )).toBe("/srv/codex-data");
  });
});
