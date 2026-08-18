import { mkdtemp, readFile, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  writePrivateFileAtomically,
  writePrivateFileExclusively,
} from "./private-file.js";

describe("private file writes", () => {
  it("atomically replaces a private file without leaving temporary files", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-private-file-"));
    const path = join(directory, "store.json");
    try {
      await writePrivateFileAtomically(path, "first\n");
      await writePrivateFileAtomically(path, "second\n");

      expect(await readFile(path, "utf8")).toBe("second\n");
      expect((await stat(path)).mode & 0o777).toBe(0o600);
      expect(await readdir(directory)).toEqual(["store.json"]);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("does not replace an existing file during exclusive creation", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-private-new-"));
    const path = join(directory, "api-token");
    try {
      await writePrivateFileExclusively(path, "original\n");
      await expect(writePrivateFileExclusively(path, "replacement\n")).rejects.toMatchObject({
        code: "EEXIST",
      });
      expect(await readFile(path, "utf8")).toBe("original\n");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
