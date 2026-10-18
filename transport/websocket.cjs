'use strict';
const { TextDecoder } = require('node:util');
const WIRE_LIMIT = 32 * 1024 * 1024;
const CHUNK_BYTES = WIRE_LIMIT - 16;
const HIGH_WATER = 3 * 1024 * 1024;
const DEFAULT_LIMIT = 128 * 1024 * 1024;
const MAX_LIMIT = 512 * 1024 * 1024;
const QUEUE_HEADROOM = 8 * 1024 * 1024;
const STALL_MS = 60_000;
const MAGIC = Buffer.from('CRW1');

/** Ordered opaque JSON transport. Fragments never reach application handlers. */
class ChunkedTransport {
  constructor(socket, options = {}) {
    this.socket = socket;
    this.maxMessageBytes = options.maxMessageBytes ?? MAX_LIMIT;
    this.server = options.server === true;
    this.onMessage = options.onMessage ?? (() => {});
    this.onActivity = options.onActivity ?? (() => {});
    this.onFatal = options.onFatal ?? (() => {});
    this.queuedBytes = 0;
    this.transferId = 0;
    this.tail = Promise.resolve();
    this.closed = false;
    this.negotiated = false;
    this.incoming = null;
    this.ready = new Promise((resolve, reject) => { this.resolveReady = resolve; this.rejectReady = reject; });
    this.ready.catch(() => {});
    this.handshakeTimer = setTimeout(() => this.fail('Transport handshake timeout', 4400), STALL_MS);
    this.handshakeTimer.unref?.();
    socket.on('message', (data, binary) => {
      try { this.receive(Buffer.isBuffer(data) ? data : Buffer.from(data), binary); }
      catch (error) { this.fail(error.message, error.code === 'MESSAGE_TOO_LARGE' ? 4409 : 4400); }
    });
    socket.on('close', () => this.dispose());
    socket.on('error', () => this.dispose());
    if (this.server) this.control({ type: 'transport-config', protocol: 'crw1', chunkBytes: CHUNK_BYTES, maxMessageBytes: this.maxMessageBytes });
  }

  control(value) { this.socket.send(JSON.stringify(value)); }

  receive(data, binary) {
    if (this.closed) return;
    if (data.length > WIRE_LIMIT) throw tooLarge();
    if (!this.negotiated) {
      if (binary) throw new Error('Expected transport handshake');
      const hello = JSON.parse(data.toString());
      const expected = this.server ? 'transport-ready' : 'transport-config';
      if (hello.type !== expected || hello.protocol !== 'crw1' || hello.chunkBytes !== CHUNK_BYTES
        || !Number.isInteger(hello.maxMessageBytes) || hello.maxMessageBytes < 1 || hello.maxMessageBytes > MAX_LIMIT) {
        throw new Error('Unsupported transport configuration; upgrade Gateway and Agent/App');
      }
      this.maxMessageBytes = Math.min(this.maxMessageBytes, hello.maxMessageBytes);
      if (!this.server) this.control({ type: 'transport-ready', protocol: 'crw1', chunkBytes: CHUNK_BYTES, maxMessageBytes: this.maxMessageBytes });
      this.negotiated = true;
      clearTimeout(this.handshakeTimer);
      this.resolveReady();
      return;
    }
    if (!binary) {
      if (this.incoming) throw new Error('Message interrupted an unfinished transfer');
      if (data.length > this.maxMessageBytes) throw tooLarge();
      this.onMessage(decode(data));
      return;
    }
    if (data.length <= 16 || data.length > CHUNK_BYTES + 16 || !data.subarray(0, 4).equals(MAGIC)) throw new Error('Invalid fragment header');
    const id = data.readUInt32BE(4), offset = data.readUInt32BE(8), total = data.readUInt32BE(12);
    if (total > this.maxMessageBytes) throw tooLarge();
    if (total === 0 || offset + data.length - 16 > total) throw new Error('Invalid fragment length');
    let transfer = this.incoming;
    if (!transfer) {
      if (offset !== 0) throw new Error('Fragment starts at a nonzero offset');
      transfer = this.incoming = { id, total, offset: 0, bytes: Buffer.allocUnsafe(total) };
      this.onActivity(true);
    }
    if (transfer.id !== id || transfer.total !== total || transfer.offset !== offset) throw new Error('Out-of-order fragment');
    data.copy(transfer.bytes, offset, 16);
    transfer.offset += data.length - 16;
    clearTimeout(this.receiveTimer);
    if (transfer.offset === total) {
      this.incoming = null;
      try { this.onMessage(decode(transfer.bytes)); }
      finally { this.onActivity(false); }
    } else {
      this.receiveTimer = setTimeout(() => this.fail('Fragment transfer stalled', 4408), STALL_MS);
      this.receiveTimer.unref?.();
    }
  }

  send(value) {
    if (this.closed || !this.negotiated) return Promise.reject(new Error('Transport is not ready'));
    const bytes = Buffer.from(JSON.stringify(value));
    if (bytes.length > this.maxMessageBytes) {
      this.fail('Complete message exceeds negotiated limit', 4409);
      return Promise.reject(tooLarge());
    }
    if (this.queuedBytes + bytes.length > this.maxMessageBytes + QUEUE_HEADROOM) {
      this.socket.close(1013, 'Transport send queue is full; reconnect to resume');
      return Promise.reject(new Error('Transport send queue is full'));
    }
    this.queuedBytes += bytes.length;
    const sent = this.tail.then(async () => {
      if (bytes.length <= WIRE_LIMIT) return this.write(bytes.toString('utf8'));
      this.onActivity(true);
      try {
        const id = this.transferId = (this.transferId + 1) >>> 0;
        for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
          const part = Buffer.allocUnsafe(16 + Math.min(CHUNK_BYTES, bytes.length - offset));
          MAGIC.copy(part); part.writeUInt32BE(id, 4); part.writeUInt32BE(offset, 8); part.writeUInt32BE(bytes.length, 12);
          bytes.copy(part, 16, offset, offset + part.length - 16);
          await this.write(part);
        }
      } finally { this.onActivity(false); }
    });
    this.tail = sent.catch(() => {}).finally(() => { this.queuedBytes -= bytes.length; });
    return sent;
  }

  async write(data) {
    let changed = Date.now(), previous = this.socket.bufferedAmount;
    while (this.socket.bufferedAmount > HIGH_WATER) {
      if (this.closed || this.socket.readyState !== 1) throw new Error('Transport disconnected');
      const buffered = this.socket.bufferedAmount;
      if (buffered < previous) changed = Date.now();
      if (Date.now() - changed >= STALL_MS) { this.fail('Transport send stalled', 4408); throw new Error('Transport send stalled'); }
      previous = buffered;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    if (this.closed || this.socket.readyState !== 1) throw new Error('Transport disconnected');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.fail('Transport send stalled', 4408); finish(new Error('Transport send stalled')); }, STALL_MS);
      const closed = () => finish(new Error('Transport disconnected'));
      const finish = error => { clearTimeout(timer); this.socket.off('close', closed); if (error) reject(error); else resolve(); };
      this.socket.once('close', closed);
      this.socket.send(data, error => finish(error));
    });
  }

  fail(message, code) {
    if (this.closed) return;
    this.onFatal(new Error(message), code);
    this.socket.close(code, message.slice(0, 100));
    this.dispose();
  }

  dispose() {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.handshakeTimer); clearTimeout(this.receiveTimer);
    if (this.incoming) { this.incoming = null; this.onActivity(false); }
    this.rejectReady(new Error('Transport disconnected'));
  }
}
function tooLarge() { return Object.assign(new Error('Complete message exceeds negotiated limit'), { code: 'MESSAGE_TOO_LARGE' }); }
function decode(bytes) { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
module.exports = { ChunkedTransport, WIRE_LIMIT, CHUNK_BYTES, DEFAULT_LIMIT, MAX_LIMIT };
