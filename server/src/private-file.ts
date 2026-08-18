import { randomBytes } from "node:crypto";
import { chown, mkdir, rename, rm, stat, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export async function writePrivateFileAtomically(path: string, content: string): Promise<void> {
  const directory = dirname(path);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  try {
    await writeFile(temporary, content, { mode: 0o600, flag: "wx" });
    await inheritDirectoryOwner(temporary, directory);
    await rename(temporary, path);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

export async function writePrivateFileExclusively(path: string, content: string): Promise<void> {
  const directory = dirname(path);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  let created = false;
  try {
    await writeFile(path, content, { mode: 0o600, flag: "wx" });
    created = true;
    await inheritDirectoryOwner(path, directory);
  } catch (error) {
    if (created) await rm(path, { force: true });
    throw error;
  }
}

async function inheritDirectoryOwner(path: string, directory: string): Promise<void> {
  if (process.getuid?.() !== 0) return;
  const owner = await stat(directory);
  await chown(path, owner.uid, owner.gid);
}
