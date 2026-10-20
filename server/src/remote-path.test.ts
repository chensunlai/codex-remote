import { describe, expect, it } from "vitest";
import { isRemoteAbsolutePath } from "./remote-path.js";

describe("remote absolute paths on a Linux Gateway", () => {
  it.each(["/", "/home/user/项目 a", "C:\\", "D:/", "C:\\Projects\\项目 a", "D:/Projects/项目 a", "\\\\server\\share", "\\\\server\\share\\项目 a", "//server/share/path"])("accepts %s without rewriting it", (path) => {
    expect(isRemoteAbsolutePath(path)).toBe(true);
  });
  it.each(["", "relative/file", "C:", "C:relative", "\\root-relative", "\\\\server", "C:\\bad\0name", "/bad\0name"])("rejects %s", (path) => {
    expect(isRemoteAbsolutePath(path)).toBe(false);
  });
});
