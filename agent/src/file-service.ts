import {
  createReadStream,
  createWriteStream,
  type WriteStream,
} from "node:fs";
import {
  lstat,
  mkdir,
  readdir,
  realpath,
  rmdir,
  stat,
  unlink,
} from "node:fs/promises";
import { homedir } from "node:os";
import { basename, isAbsolute, join } from "node:path";
import type { Readable } from "node:stream";

interface UploadEntry {
  stream: WriteStream;
  completed: Promise<void>;
  error?: Error;
}

export class FileService {
  private readonly uploads = new Map<string, UploadEntry>();

  async home(): Promise<{ path: string }> {
    return { path: await realpath(homedir()) };
  }

  async list(path: string): Promise<unknown[]> {
    assertAbsolute(path);
    const entries = await readdir(path, { withFileTypes: true });
    const files = await Promise.all(
      entries.map(async (entry) => {
        const fullPath = join(path, entry.name);
        const attributes = await lstat(fullPath);
        return {
          name: entry.name,
          path: fullPath,
          type: entry.isDirectory()
            ? "directory"
            : entry.isFile()
              ? "file"
              : entry.isSymbolicLink()
                ? "symlink"
                : "other",
          size: attributes.size,
          modifiedAt: Math.floor(attributes.mtimeMs / 1000),
          permissions: attributes.mode & 0o7777,
        };
      }),
    );
    return files.sort((left, right) => {
      if (left.type === "directory" && right.type !== "directory") return -1;
      if (right.type === "directory" && left.type !== "directory") return 1;
      return left.name.localeCompare(right.name);
    });
  }

  async mkdir(path: string): Promise<{ path: string }> {
    assertAbsolute(path);
    await mkdir(path);
    return { path };
  }

  async delete(path: string, directory: boolean): Promise<void> {
    assertAbsolute(path);
    if (directory) await rmdir(path);
    else await unlink(path);
  }

  async downloadStat(path: string): Promise<{ name: string; size: number }> {
    assertAbsolute(path);
    const attributes = await stat(path);
    if (!attributes.isFile()) throw new Error("只能下载普通文件");
    return { name: basename(path), size: attributes.size };
  }

  openDownload(path: string): Readable {
    assertAbsolute(path);
    return createReadStream(path);
  }

  openUpload(path: string, streamId: string): void {
    assertAbsolute(path);
    if (this.uploads.has(streamId)) throw new Error("上传流 ID 已存在");
    const stream = createWriteStream(path, { flags: "w", mode: 0o600 });
    let resolveCompleted!: () => void;
    let rejectCompleted!: (error: Error) => void;
    const completed = new Promise<void>((resolve, reject) => {
      resolveCompleted = resolve;
      rejectCompleted = reject;
    });
    const entry: UploadEntry = { stream, completed };
    stream.once("finish", resolveCompleted);
    stream.once("error", (error) => {
      entry.error = error;
      rejectCompleted(error);
    });
    this.uploads.set(streamId, entry);
  }

  writeUpload(streamId: string, data: Buffer): void {
    const entry = this.uploads.get(streamId);
    if (!entry) throw new Error("上传流不存在");
    entry.stream.write(data);
  }

  endUpload(streamId: string): void {
    const entry = this.uploads.get(streamId);
    if (!entry) return;
    entry.stream.end();
  }

  failUpload(streamId: string, message: string): void {
    const entry = this.uploads.get(streamId);
    if (!entry) return;
    entry.stream.destroy(new Error(message));
  }

  async finishUpload(streamId: string): Promise<{ path: string; size: number }> {
    const entry = this.uploads.get(streamId);
    if (!entry) throw new Error("上传流不存在");
    try {
      await entry.completed;
      if (entry.error) throw entry.error;
      const path = entry.stream.path;
      if (typeof path !== "string") throw new Error("上传目标路径无效");
      const attributes = await stat(path);
      return { path, size: attributes.size };
    } finally {
      this.uploads.delete(streamId);
    }
  }
}

function assertAbsolute(path: string): void {
  if (!isAbsolute(path)) throw new Error("远端路径必须是绝对路径");
}
