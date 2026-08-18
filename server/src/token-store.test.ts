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
      const ownerId = await first.verify(token);
      expect(ownerId).toBeTruthy();
      await first.revoke(ownerId!);

      const reopened = await TokenStore.open(path);
      await reopened.import([token]);
      expect(await reopened.verify(token)).toBeUndefined();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("observes tokens changed by another management process", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-token-refresh-"));
    const path = join(directory, "tokens.json");
    try {
      const gateway = await TokenStore.open(path);
      const admin = await TokenStore.open(path);
      const created = await admin.create("phone");

      expect(await gateway.verify(created.token)).toBe(created.id);
      await admin.revoke(created.id);
      expect(await gateway.verify(created.token)).toBeUndefined();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("tags the initial token as admin and requires unique tags", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-token-tags-"));
    const path = join(directory, "tokens.json");
    try {
      const store = await TokenStore.open(path);
      await store.import(["initial-token"]);
      expect(await store.list()).toEqual([
        expect.objectContaining({ label: "admin" }),
      ]);
      await expect(store.create(" ")).rejects.toThrow("tag 不能为空");
      await expect(store.create("ADMIN")).rejects.toThrow("tag 已存在");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
