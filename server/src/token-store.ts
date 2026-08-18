import { createHash, randomBytes, randomUUID } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { ConflictError, NotFoundError } from "./errors.js";
import { writePrivateFileAtomically } from "./private-file.js";

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
  private fileStamp = "missing";

  private constructor(private readonly path: string) {}

  static async open(path: string): Promise<TokenStore> {
    const store = new TokenStore(path);
    await store.load();
    return store;
  }

  async verify(token: string): Promise<string | undefined> {
    if (!token) return undefined;
    await this.refresh();
    const hash = hashToken(token);
    return this.records.find((record) => record.tokenHash === hash)?.id;
  }

  async import(tokens: string[]): Promise<void> {
    await this.refresh();
    let changed = this.migrateBootstrapLabels();
    for (const token of tokens) {
      const tokenHash = hashToken(token);
      if (this.revokedTokenHashes.has(tokenHash)) continue;
      if (this.records.some((record) => record.tokenHash === tokenHash)) continue;
      this.records.push({
        id: tokenHash,
        label: this.nextAdminLabel(),
        tokenHash,
        createdAt: new Date().toISOString(),
      });
      changed = true;
    }
    if (changed) await this.persist();
  }

  async list(): Promise<Omit<TokenRecord, "tokenHash">[]> {
    await this.refresh();
    return this.records.map(({ tokenHash: _tokenHash, ...record }) => structuredClone(record));
  }

  async create(label: string): Promise<{ id: string; token: string }> {
    await this.refresh();
    const normalizedLabel = label.trim();
    if (!normalizedLabel) throw new Error("Token tag 不能为空");
    if (this.records.some((record) => record.label.toLocaleLowerCase() === normalizedLabel.toLocaleLowerCase())) {
      throw new ConflictError(`Token tag 已存在：${normalizedLabel}`);
    }
    const token = randomBytes(32).toString("base64url");
    const record: TokenRecord = {
      id: randomUUID(),
      label: normalizedLabel,
      tokenHash: hashToken(token),
      createdAt: new Date().toISOString(),
    };
    this.records.push(record);
    await this.persist();
    return { id: record.id, token };
  }

  async revoke(id: string): Promise<void> {
    await this.refresh();
    const before = this.records.length;
    const record = this.records.find((candidate) => candidate.id === id);
    this.records = this.records.filter((record) => record.id !== id);
    if (this.records.length === before) throw new NotFoundError("Token");
    this.revokedTokenHashes.add(record!.tokenHash);
    await this.persist();
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
      this.records = parsed.tokens;
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
    this.writeQueue = this.writeQueue.then(async () => {
      await writePrivateFileAtomically(this.path, `${JSON.stringify(snapshot, null, 2)}\n`);
      const metadata = await stat(this.path, { bigint: true });
      this.fileStamp = `${metadata.mtimeNs}:${metadata.size}`;
    });
    await this.writeQueue;
  }

  private migrateBootstrapLabels(): boolean {
    let changed = false;
    for (const record of this.records) {
      if (record.label !== "bootstrap") continue;
      record.label = this.nextAdminLabel();
      changed = true;
    }
    return changed;
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
