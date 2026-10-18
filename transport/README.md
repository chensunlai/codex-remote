# CRW1 opaque WebSocket transport

This protocol belongs to Codex Remote. Official extension and WebView messages
remain unchanged; decoding a complete transport message yields their original
JSON envelope. It is independent of official message names and schemas.

Endpoints: `/agent/v2/connect`, `/api/v4/services/:serviceId/extension`.
After authentication the server sends a text control message:

```json
{"type":"transport-config","protocol":"crw1","chunkBytes":1048576,"maxMessageBytes":134217728}
```

The client replies with `type: "transport-ready"`, the same protocol and chunk
size, and its supported limit. Both use the smaller limit for this connection.
No application messages precede the handshake. The server setting is captured
at connection creation; an App connection also respects its Agent connection's
negotiated limit.

JSON envelopes of at most 1 MiB of UTF-8 use one text message. Larger envelopes
use binary messages with a 16-byte header followed by up to 1 MiB of bytes:

| Offset | Content |
| --- | --- |
| 0 | ASCII `CRW1` |
| 4 | Transfer ID, unsigned 32-bit big endian |
| 8 | Byte offset, unsigned 32-bit big endian |
| 12 | Complete UTF-8 byte length, unsigned 32-bit big endian |

Each direction permits one unfinished transfer, strictly contiguous offsets and
constant ID/total length. Decode UTF-8 only after reassembly, rejecting invalid
encoding. Transfer IDs wrap at 32 bits; they are connection-local and do not
replace application message IDs or acknowledgements.

Wire messages remain limited to 16 MiB. Complete messages use the configured
64/128/256/512 MiB bound (default 128). Send queues are bounded to one complete
message limit plus 8 MiB; writes wait above 3 MiB of socket backlog, so at most
approximately 4 MiB is queued in the WebSocket implementation.

Close codes: 4400 malformed protocol/upgrade needed; 4409 complete message too
large; 4408 no transfer progress for 60 seconds. Clients stop automatic retries
for 4400/4409. Queue overflow uses 1013 and permits recovery. Partial transfers
are discarded at disconnect, never acknowledged or retried by this layer.
Existing application replay/confirmation rules govern reconnects. RPC deadlines
pause while the Agent link is actively sending or receiving a fragmented
message; the stall deadline still applies.

`fixtures/unicode.properties` is a shared JS/Kotlin byte-level test vector.
