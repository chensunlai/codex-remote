import { MAX_WEBSOCKET_BYTES } from "../server/src/websocket-limits.ts";
import { configureBunServer } from "../transport/bun-server.cjs";

configureBunServer(Bun, MAX_WEBSOCKET_BYTES);
await import("../server/src/cli.ts");
