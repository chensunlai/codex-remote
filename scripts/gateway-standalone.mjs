import { MAX_WEBSOCKET_BYTES } from "../server/src/websocket-limits.ts";

// Bun 1.3.14 ignores ws.maxPayload when Fastify upgrades an HTTP server.
// Configure its underlying server before starting the Gateway:
// https://github.com/oven-sh/bun/issues/8261
const serve = Bun.serve;
Bun.serve = function (options) {
  return serve.call(this, {
    ...options,
    ...(options.websocket ? {
      websocket: {
        ...options.websocket,
        maxPayloadLength: MAX_WEBSOCKET_BYTES,
        backpressureLimit: MAX_WEBSOCKET_BYTES,
      },
    } : {}),
  });
};

await import("../server/src/cli.ts");
