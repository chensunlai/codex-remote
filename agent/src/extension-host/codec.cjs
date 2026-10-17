'use strict';

// Transport the public WebView API's values without interpreting plugin messages.
function encode(value, seen = new Set()) {
  if (value == null) return ['null', null];
  if (typeof value === 'boolean') return ['bool', value];
  if (typeof value === 'number') return ['number', Number.isFinite(value) ? value : null];
  if (typeof value === 'string') return ['string', value];
  if (value instanceof ArrayBuffer) return ['bytes', Buffer.from(value).toString('base64')];
  if (ArrayBuffer.isView(value)) return ['bytes', Buffer.from(value.buffer, value.byteOffset, value.byteLength).toString('base64')];
  if (typeof value !== 'object') throw new TypeError('WebView messages must be serializable');
  if (seen.has(value)) throw new TypeError('Cyclic WebView message');
  seen.add(value);
  try {
    if (Array.isArray(value)) return ['array', value.map(item => encode(item, seen))];
    return ['object', Object.entries(value).filter(([, item]) => item !== undefined)
      .map(([key, item]) => [key, encode(item, seen)])];
  } finally { seen.delete(value); }
}

function decode(value, depth = 0) {
  if (depth > 100 || !Array.isArray(value) || value.length !== 2) throw new TypeError('Invalid WebView value');
  const [tag, item] = value;
  switch (tag) {
    case 'null': return null;
    case 'bool': if (typeof item === 'boolean') return item; break;
    case 'number': if (typeof item === 'number' || item === null) return item; break;
    case 'string': if (typeof item === 'string') return item; break;
    case 'bytes': if (typeof item === 'string') return new Uint8Array(Buffer.from(item, 'base64')); break;
    case 'array': if (Array.isArray(item)) return item.map(child => decode(child, depth + 1)); break;
    case 'object': {
      if (!Array.isArray(item)) break;
      const result = {};
      for (const entry of item) {
        if (!Array.isArray(entry) || entry.length !== 2 || typeof entry[0] !== 'string') throw new TypeError('Invalid WebView property');
        Object.defineProperty(result, entry[0], { value: decode(entry[1], depth + 1), enumerable: true, writable: true, configurable: true });
      }
      return result;
    }
  }
  throw new TypeError('Invalid WebView value');
}

module.exports = { encode, decode };
