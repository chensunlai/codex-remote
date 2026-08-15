import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { TokenStore } from "./token-store.js";

describe("TokenStore", () => {
  it("does not re-import a revoked bootstrap token", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-token-test-"));
    const path = join(directory, "tokens.json");
    const token = "bootstrap-token";
    try {
      const first = await TokenStore.open(path);
      await first.import([token]);
      const ownerId = first.verify(token);
      expect(ownerId).toBeTruthy();
      await first.revoke(ownerId!);

      const reopened = await TokenStore.open(path);
      await reopened.import([token]);
      expect(reopened.verify(token)).toBeUndefined();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
