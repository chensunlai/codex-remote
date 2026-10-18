# CRW1 opaque WebSocket transport

This protocol belongs to Codex Remote. Official extension and WebView messages
remain unchanged; decoding a complete transport message yields their original
JSON envelope. It is independent of official message names and schemas.

Endpoints: `/agent/v2/connect`, `/api/v4/services/:serviceId/extension`.
After authentication the server sends a text control message:

```json
{"type":"transport-config","protocol":"crw1","chunkBytes":33554416,"maxMessageBytes":134217728}
```

The client replies with `type: "transport-ready"`, the same protocol and maximum
chunk size, and its supported complete-message limit. Both use the smaller
complete-message limit for this connection. No application messages precede
the handshake. The server setting is captured at connection creation; an App
connection also respects its Agent connection's negotiated limit.

Agent/Gateway send JSON envelopes of at most 32 MiB of UTF-8 as one text message.
Android uses an 8 MiB upload threshold because OkHttp has a fixed 16 MiB send
queue. Larger envelopes use binary messages with a 16-byte header followed by
up to 32 MiB minus 16 bytes (Android sends up to 8 MiB minus 16 bytes):

| Offset | Content |
| --- | --- |
| 0 | ASCII `CRW1` |
| 4 | Transfer ID, unsigned 32-bit big endian |
| 8 | Byte offset, unsigned 32-bit big endian |
| 12 | Complete UTF-8 byte length, unsigned 32-bit big endian |

Each direction permits one unfinished transfer, strictly contiguous offsets and
constant ID/total length. Smaller fragments are valid: senders can respect their
own queue constraints without changing the receiver. Decode UTF-8 only after
reassembly, rejecting invalid encoding. Transfer IDs wrap at 32 bits; they are
connection-local and do not replace application message IDs or acknowledgements.

Wire messages are limited to 32 MiB including the header. Complete messages use
the configured 64/128/256/512 MiB bound (default 128). Send queues are bounded to
one complete-message limit plus 8 MiB. Writes wait above 3 MiB of socket backlog;
a single 32 MiB write can temporarily raise native backlog to approximately
35 MiB (Android: 11 MiB, below OkHttp's 16 MiB cap).

Bun releases use public `ServerWebSocket.send()` and `getBufferedAmount()` for
writes. A `-1` result means accepted into the native buffer and is never retried;
only `0` (not accepted) permits retry. This avoids duplicate frames in Bun's ws
compatibility drain queue. Fastify/ws still owns authentication, HTTP upgrades,
receive events and connection lifecycle. Node uses the ordinary ws implementation.

Close codes: 4400 malformed protocol/upgrade needed; 4409 complete message too
large; 4408 no transfer progress for 60 seconds. Clients stop automatic retries
for 4400/4409. Queue overflow uses 1013 and permits recovery. Partial transfers
are discarded at disconnect, never acknowledged or retried by this layer.
Existing application replay/confirmation rules govern reconnects. RPC deadlines
pause while the Agent link is actively sending or receiving a fragmented
message; the stall deadline still applies. All endpoints must be updated together;
the 2.2.0 handshake's 1 MiB chunk setting is rejected explicitly.

`fixtures/unicode.properties` is a shared JS/Kotlin byte-level test vector.
