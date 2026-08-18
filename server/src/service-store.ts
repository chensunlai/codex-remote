import { readFile, stat } from "node:fs/promises";
import type { AgentDescription, ServiceRecord } from "./domain.js";
import { NotFoundError } from "./errors.js";
import { writePrivateFileAtomically } from "./private-file.js";

interface StoreFile {
  version: 1;
  services: ServiceRecord[];
}

export class ServiceStore {
  private records: ServiceRecord[] | undefined;
  private writeQueue = Promise.resolve();
  private fileStamp = "missing";

  constructor(private readonly path: string) {}

  async list(ownerId: string): Promise<ServiceRecord[]> {
    await this.ensureLoaded();
    return structuredClone(this.records!.filter((service) => service.ownerId === ownerId));
  }

  async listAll(): Promise<ServiceRecord[]> {
    await this.ensureLoaded();
    return structuredClone(this.records!);
  }

  async get(ownerId: string, id: string): Promise<ServiceRecord> {
    await this.ensureLoaded();
    const service = this.records!.find(
      (candidate) => candidate.ownerId === ownerId && candidate.id === id,
    );
    if (!service) throw new NotFoundError("Service");
    return structuredClone(service);
  }

  async register(
    ownerId: string,
    id: string,
    name: string,
    description?: AgentDescription,
  ): Promise<ServiceRecord> {
    await this.ensureLoaded();
    const now = new Date().toISOString();
    const index = this.records!.findIndex(
      (candidate) => candidate.ownerId === ownerId && candidate.id === id,
    );
    const current = index >= 0 ? this.records![index] : undefined;
    const record: ServiceRecord = {
      id,
      ownerId,
      name,
      ...(description?.hostname ? { hostname: description.hostname } : {}),
      ...(description?.platform ? { platform: description.platform } : {}),
      ...(description?.arch ? { arch: description.arch } : {}),
      ...(description?.agentVersion ? { agentVersion: description.agentVersion } : {}),
      ...(description?.home ? { home: description.home } : {}),
      createdAt: current?.createdAt ?? now,
      lastConnectedAt: now,
    };
    if (index >= 0) this.records![index] = record;
    else this.records!.push(record);
    await this.persist();
    return structuredClone(record);
  }

  async delete(ownerId: string, id: string): Promise<void> {
    await this.ensureLoaded();
    const before = this.records!.length;
    this.records = this.records!.filter(
      (service) => service.ownerId !== ownerId || service.id !== id,
    );
    if (this.records.length === before) throw new NotFoundError("Service");
    await this.persist();
  }

  private async ensureLoaded(): Promise<void> {
    await this.writeQueue;
    try {
      const metadata = await stat(this.path, { bigint: true });
      const stamp = `${metadata.mtimeNs}:${metadata.size}`;
      if (this.records && stamp === this.fileStamp) return;
      const parsed = JSON.parse(await readFile(this.path, "utf8")) as StoreFile;
      if (parsed.version !== 1 || !Array.isArray(parsed.services)) {
        throw new Error("Unsupported service store format");
      }
      this.records = parsed.services;
      this.fileStamp = stamp;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      this.records = [];
      this.fileStamp = "missing";
    }
  }

  private async persist(): Promise<void> {
    const snapshot: StoreFile = { version: 1, services: structuredClone(this.records!) };
    this.writeQueue = this.writeQueue.then(async () => {
      await writePrivateFileAtomically(this.path, `${JSON.stringify(snapshot, null, 2)}\n`);
      const metadata = await stat(this.path, { bigint: true });
      this.fileStamp = `${metadata.mtimeNs}:${metadata.size}`;
    });
    await this.writeQueue;
  }
}
