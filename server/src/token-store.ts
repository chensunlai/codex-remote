import { createHash, randomBytes, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { NotFoundError } from "./errors.js";

export interface TokenRecord {
  id: string;
  label: string;
  tokenHash: string;
  createdAt: string;
}

interface StoreFile {
  version: 1;
  tokens: TokenRecord[];
  revokedTokenHashes?: string[];
}

export class TokenStore {
  private records: TokenRecord[] = [];
  private revokedTokenHashes = new Set<string>();
  private writeQueue = Promise.resolve();

  private constructor(private readonly path: string) {}

  static async open(path: string): Promise<TokenStore> {
    const store = new TokenStore(path);
    await store.load();
    return store;
  }

  verify(token: string): string | undefined {
    if (!token) return undefined;
    const hash = hashToken(token);
    return this.records.find((record) => record.tokenHash === hash)?.id;
  }

  async import(tokens: string[]): Promise<void> {
    let changed = false;
    for (const token of tokens) {
      const tokenHash = hashToken(token);
      if (this.revokedTokenHashes.has(tokenHash)) continue;
      if (this.records.some((record) => record.tokenHash === tokenHash)) continue;
      this.records.push({
        id: tokenHash,
        label: "bootstrap",
        tokenHash,
        createdAt: new Date().toISOString(),
      });
      changed = true;
    }
    if (changed) await this.persist();
  }

  list(): Omit<TokenRecord, "tokenHash">[] {
    return this.records.map(({ tokenHash: _tokenHash, ...record }) => structuredClone(record));
  }

  async create(label: string): Promise<{ id: string; token: string }> {
    const token = randomBytes(32).toString("base64url");
    const record: TokenRecord = {
      id: randomUUID(),
      label: label.trim() || "user",
      tokenHash: hashToken(token),
      createdAt: new Date().toISOString(),
    };
    this.records.push(record);
    await this.persist();
    return { id: record.id, token };
  }

  async revoke(id: string): Promise<void> {
    const before = this.records.length;
    const record = this.records.find((candidate) => candidate.id === id);
    this.records = this.records.filter((record) => record.id !== id);
    if (this.records.length === before) throw new NotFoundError("Token");
    this.revokedTokenHashes.add(record!.tokenHash);
    await this.persist();
  }

  private async load(): Promise<void> {
    try {
      const parsed = JSON.parse(await readFile(this.path, "utf8")) as StoreFile;
      if (parsed.version !== 1 || !Array.isArray(parsed.tokens)) {
        throw new Error("Unsupported token store format");
      }
      this.records = parsed.tokens;
      this.revokedTokenHashes = new Set(parsed.revokedTokenHashes ?? []);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  private async persist(): Promise<void> {
    const snapshot: StoreFile = {
      version: 1,
      tokens: structuredClone(this.records),
      revokedTokenHashes: [...this.revokedTokenHashes],
    };
    this.writeQueue = this.writeQueue.then(async () => {
      await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${process.pid}.tmp`;
      await writeFile(temporary, `${JSON.stringify(snapshot, null, 2)}\n`, { mode: 0o600 });
      await rename(temporary, this.path);
    });
    await this.writeQueue;
  }
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}
