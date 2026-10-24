export function installService(executable: string, config: string): Promise<void>;
export function status(executable: string, version: string): Promise<unknown>;
export function enqueue(executable: string, version: string, confirmed: boolean): Promise<unknown>;
export function markStarted(executable: string, version: string): Promise<void>;
