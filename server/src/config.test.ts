import { describe, expect, it } from "vitest";
import { defaultDataDirectory, resolveDataDirectory } from "./config.js";

describe("Gateway configuration", () => {
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
