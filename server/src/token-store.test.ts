import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { TokenStore } from "./token-store.js";

describe("TokenStore", () => {
  it("migrates only configured secrets to admin, preserving IDs and denying misleading labels", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-token-migrate-"));
    const path = join(directory, "tokens.json");
    try {
      const secrets = ["actual-bootstrap", "forged-admin", "forged-bootstrap"];
      await writeFile(path, JSON.stringify({ version: 1, tokens: secrets.map((token, index) => ({
        id: `existing-owner-${index}`,
        label: ["bootstrap", "admin", "bootstrap"][index],
        tokenHash: createHash("sha256").update(token).digest("hex"),
        createdAt: "2026-10-01T00:00:00Z",
      })) }));
      const store = await TokenStore.open(path);
      await store.import([secrets[0]!]);
      expect(await store.authenticate(secrets[0]!)).toMatchObject({ id: "existing-owner-0", role: "admin" });
      expect(await store.authenticate(secrets[1]!)).toMatchObject({ id: "existing-owner-1", role: "user" });
      expect(await store.authenticate(secrets[2]!)).toMatchObject({ id: "existing-owner-2", role: "user" });
      const reopened = await TokenStore.open(path);
      expect(await reopened.authenticate(secrets[0]!)).toMatchObject({ id: "existing-owner-0", role: "admin" });
      expect(JSON.stringify(await reopened.list())).not.toContain("tokenHash");
      expect((await reopened.create("new-admin-looking-label")).role).toBe("user");
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

  it("serializes concurrent token creation and prevents remote self/last-admin revocation", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-token-concurrent-"));
    try {
      const store = await TokenStore.open(join(directory, "tokens.json"));
      const admin = await store.create("initial", "admin");
      const attempts = await Promise.allSettled([store.create("duplicate"), store.create("duplicate")]);
      expect(attempts.filter((attempt) => attempt.status === "fulfilled")).toHaveLength(1);
      await expect(store.revoke(admin.id, admin.id)).rejects.toThrow("当前使用");
      await expect(store.revoke(admin.id, "other-actor")).rejects.toThrow("最后一个");
      expect(await store.verify(admin.token)).toBe(admin.id);
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

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
