import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ServiceStore } from "./service-store.js";

describe("ServiceStore", () => {
  it("observes service records changed by another management process", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-service-refresh-"));
    const path = join(directory, "services.json");
    try {
      const gateway = new ServiceStore(path);
      const admin = new ServiceStore(path);
      await admin.register("owner-1", "service-1", "build-server");

      expect(await gateway.listAll()).toEqual([
        expect.objectContaining({ id: "service-1", name: "build-server" }),
      ]);
      await admin.delete("owner-1", "service-1");
      expect(await gateway.listAll()).toEqual([]);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
