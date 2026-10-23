import { lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { FileService } from "./file-service.js";

describe("file service symbolic links", () => {
  let root: string;
  let browse: string;
  let target: string;
  const service = new FileService();

  beforeEach(async () => {
    root = await mkdtemp(join(tmpdir(), "remote-links-"));
    browse = join(root, "browse");
    target = join(root, "target 中文");
    await mkdir(browse);
    await mkdir(target);
    await writeFile(join(target, "hello.txt"), "中文🙂");
  });
  afterEach(async () => { await rm(root, { recursive: true, force: true }); });

  it("browses directory links and junctions using the original path and sorts them as directories", async () => {
    const link = join(browse, "linked directory");
    await symlink(target, link, process.platform === "win32" ? "junction" : "dir");
    await writeFile(join(browse, "aaa.txt"), "file");
    expect(await service.list(browse)).toEqual([
      expect.objectContaining({ name: "linked directory", path: link, type: "directory" }),
      expect.objectContaining({ name: "aaa.txt", type: "file" }),
    ]);
    expect(await service.list(link)).toEqual([
      expect.objectContaining({ path: join(link, "hello.txt"), type: "file", size: Buffer.byteLength("中文🙂") }),
    ]);
    await service.delete(link, true);
    expect(await readFile(join(target, "hello.txt"), "utf8")).toBe("中文🙂");
    await expect(lstat(link)).rejects.toMatchObject({ code: "ENOENT" });
  });

  // File symlinks require developer mode or elevation on Windows; junctions above do not.
  it.skipIf(process.platform === "win32")("follows file links, retains broken/cyclic links and deletes only the link", async () => {
    const file = join(browse, "file-link");
    await symlink("../target 中文/hello.txt", file);
    await symlink("missing", join(browse, "broken"));
    await symlink("cycle", join(browse, "cycle"));
    expect(await service.list(browse)).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: "file-link", type: "file", size: Buffer.byteLength("中文🙂") }),
      expect.objectContaining({ name: "broken", type: "symlink" }),
      expect.objectContaining({ name: "cycle", type: "symlink" }),
    ]));
    expect(await service.downloadStat(file)).toEqual({ name: "file-link", size: Buffer.byteLength("中文🙂") });
    await service.delete(file, false);
    expect(await readFile(join(target, "hello.txt"), "utf8")).toBe("中文🙂");
  });
});
