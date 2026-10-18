import { createHash, randomBytes, randomUUID } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { ConflictError, NotFoundError } from "./errors.js";
import { writePrivateFileAtomically } from "./private-file.js";

export interface TokenRecord {
  id: string;
  label: string;
  tokenHash: string;
  createdAt: string;
  role: TokenRole;
}

export type TokenRole = "user" | "admin";

interface StoreFile {
  version: 1;
  tokens: TokenRecord[];
  revokedTokenHashes?: string[];
}

export class TokenStore {
  private records: TokenRecord[] = [];
  private revokedTokenHashes = new Set<string>();
  private writeQueue = Promise.resolve();
  private fileStamp = "missing";
  private mutationQueue = Promise.resolve();

  private constructor(private readonly path: string) {}

  static async open(path: string): Promise<TokenStore> {
    const store = new TokenStore(path);
    await store.load();
    return store;
  }

  async verify(token: string): Promise<string | undefined> {
    return (await this.authenticate(token))?.id;
  }

  async authenticate(token: string): Promise<Omit<TokenRecord, "tokenHash"> | undefined> {
    if (!token) return undefined;
    await this.refresh();
    const hash = hashToken(token);
    const record = this.records.find((record) => record.tokenHash === hash);
    if (!record) return undefined;
    const { tokenHash: _tokenHash, ...metadata } = record;
    return structuredClone(metadata);
  }

  async import(tokens: string[]): Promise<void> {
    return this.mutate(async () => {
      await this.refresh();
      let changed = false;
      for (const token of tokens) {
        const tokenHash = hashToken(token);
        if (this.revokedTokenHashes.has(tokenHash)) continue;
        const existing = this.records.find((record) => record.tokenHash === tokenHash);
        if (existing) {
          // Only possession of a configured bootstrap secret establishes this role.
          // A legacy label such as "admin" or "bootstrap" is never authorization.
          if (existing.role !== "admin") { existing.role = "admin"; changed = true; }
          if (existing.label === "bootstrap") { existing.label = this.nextAdminLabel(); changed = true; }
          continue;
        }
        this.records.push({
          id: tokenHash,
          label: this.nextAdminLabel(),
          tokenHash,
          createdAt: new Date().toISOString(),
          role: "admin",
        });
        changed = true;
      }
      if (changed) await this.persist();
    });
  }

  async list(): Promise<Omit<TokenRecord, "tokenHash">[]> {
    await this.refresh();
    return this.records.map(({ tokenHash: _tokenHash, ...record }) => structuredClone(record));
  }

  async create(label: string, role: TokenRole = "user"): Promise<{ id: string; token: string; label: string; role: TokenRole }> {
    return this.mutate(async () => {
      await this.refresh();
      const normalizedLabel = label.trim();
      if (!normalizedLabel) throw new Error("Token tag 不能为空");
      if (role !== "user" && role !== "admin") throw new Error("Invalid token role");
      if (this.records.some((record) => record.label.toLocaleLowerCase() === normalizedLabel.toLocaleLowerCase())) {
        throw new ConflictError(`Token tag 已存在：${normalizedLabel}`);
      }
      const token = randomBytes(32).toString("base64url");
      const record: TokenRecord = {
        id: randomUUID(),
        label: normalizedLabel,
        tokenHash: hashToken(token),
        createdAt: new Date().toISOString(),
        role,
      };
      this.records.push(record);
      await this.persist();
      return { id: record.id, token, label: normalizedLabel, role };
    });
  }

  async revoke(id: string, actingTokenId?: string): Promise<void> {
    return this.mutate(async () => {
      await this.refresh();
      const record = this.records.find((candidate) => candidate.id === id);
      if (!record) throw new NotFoundError("Token");
      if (actingTokenId) {
        if (id === actingTokenId) throw new ConflictError("不能撤销当前使用的管理员令牌，请先切换到另一管理员令牌");
        if (record.role === "admin" && this.records.filter((candidate) => candidate.role === "admin").length <= 1) {
          throw new ConflictError("不能撤销最后一个管理员令牌");
        }
      }
      this.records = this.records.filter((record) => record.id !== id);
      this.revokedTokenHashes.add(record.tokenHash);
      await this.persist();
    });
  }

  private async load(): Promise<void> {
    await this.refresh(true);
  }

  private async refresh(force = false): Promise<void> {
    await this.writeQueue;
    try {
      const metadata = await stat(this.path, { bigint: true });
      const stamp = `${metadata.mtimeNs}:${metadata.size}`;
      if (!force && stamp === this.fileStamp) return;
      const parsed = JSON.parse(await readFile(this.path, "utf8")) as StoreFile;
      if (parsed.version !== 1 || !Array.isArray(parsed.tokens)) {
        throw new Error("Unsupported token store format");
      }
      this.records = parsed.tokens.map((record) => ({ ...record, role: record.role === "admin" ? "admin" : "user" }));
      this.revokedTokenHashes = new Set(parsed.revokedTokenHashes ?? []);
      this.fileStamp = stamp;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      if (force || this.fileStamp !== "missing") {
        this.records = [];
        this.revokedTokenHashes.clear();
        this.fileStamp = "missing";
      }
    }
  }

  private async persist(): Promise<void> {
    const snapshot: StoreFile = {
      version: 1,
      tokens: structuredClone(this.records),
      revokedTokenHashes: [...this.revokedTokenHashes],
    };
    const write = this.writeQueue.then(async () => {
      await writePrivateFileAtomically(this.path, `${JSON.stringify(snapshot, null, 2)}\n`);
      const metadata = await stat(this.path, { bigint: true });
      this.fileStamp = `${metadata.mtimeNs}:${metadata.size}`;
    });
    this.writeQueue = write.catch(() => {});
    await write;
  }

  private mutate<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.mutationQueue.then(async () => {
      try { return await operation(); }
      catch (error) {
        // A failed disk write must not leave an unpersisted privilege change active.
        await this.refresh(true);
        throw error;
      }
    });
    this.mutationQueue = result.then(() => {}, () => {});
    return result;
  }

  private nextAdminLabel(): string {
    const labels = new Set(this.records.map((record) => record.label.toLocaleLowerCase()));
    if (!labels.has("admin")) return "admin";
    let suffix = 2;
    while (labels.has(`admin-${suffix}`)) suffix += 1;
    return `admin-${suffix}`;
  }
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}
