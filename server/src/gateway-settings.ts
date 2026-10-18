import { readFile } from "node:fs/promises";
import { z } from "zod";
import { writePrivateFileAtomically } from "./private-file.js";

export const gatewaySettingsSchema = z.object({
  maxUploadBytes: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
  maxDownloadBytes: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
}).strict();

export type GatewaySettings = z.infer<typeof gatewaySettingsSchema>;

export class GatewaySettingsStore {
  private writeQueue = Promise.resolve();

  private constructor(private readonly path: string, private value: GatewaySettings) {}

  static async open(path: string, defaults: GatewaySettings): Promise<GatewaySettingsStore> {
    let value = gatewaySettingsSchema.parse({
      maxUploadBytes: defaults.maxUploadBytes,
      maxDownloadBytes: defaults.maxDownloadBytes,
    });
    try {
      value = gatewaySettingsSchema.parse(JSON.parse(await readFile(path, "utf8")));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    return new GatewaySettingsStore(path, value);
  }

  get current(): GatewaySettings { return { ...this.value }; }

  async update(value: GatewaySettings): Promise<GatewaySettings> {
    const parsed = gatewaySettingsSchema.parse(value);
    const write = this.writeQueue.then(async () => {
      await writePrivateFileAtomically(this.path, `${JSON.stringify(parsed, null, 2)}\n`);
      this.value = parsed;
      return this.current;
    });
    this.writeQueue = write.then(() => {}, () => {});
    return write;
  }
}
