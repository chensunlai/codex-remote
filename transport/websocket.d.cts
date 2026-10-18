import type WebSocket from 'ws';
export const WIRE_LIMIT: number;
export const CHUNK_BYTES: number;
export const DEFAULT_LIMIT: number;
export const MAX_LIMIT: number;
export class ChunkedTransport {
  constructor(socket: WebSocket, options?: { server?: boolean; maxMessageBytes?: number; onMessage?: (text: string) => void; onActivity?: (active: boolean) => void; onFatal?: (error: Error, code: number) => void });
  readonly ready: Promise<void>;
  readonly negotiated: boolean;
  readonly closed: boolean;
  maxMessageBytes: number;
  onMessage: (text: string) => void;
  onActivity: (active: boolean) => void;
  send(value: unknown): Promise<void>;
  dispose(): void;
}
