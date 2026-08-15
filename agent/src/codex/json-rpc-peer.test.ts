import { PassThrough } from "node:stream";
import { describe, expect, it } from "vitest";
import { JsonRpcError, JsonRpcPeer } from "./json-rpc-peer.js";

describe("JsonRpcPeer", () => {
  it("preserves Codex error codes and data", async () => {
    const input = new PassThrough();
    const output = new PassThrough();
    output.resume();
    const peer = new JsonRpcPeer(input, output);

    const request = peer.request("thread/read", {
      threadId: "empty-thread",
      includeTurns: true,
    });
    input.write(`${JSON.stringify({
      id: 1,
      error: {
        code: -32600,
        message: "includeTurns is unavailable before first user message",
        data: { kind: "unmaterializedThread" },
      },
    })}\n`);

    await expect(request).rejects.toEqual(
      expect.objectContaining<JsonRpcError>({
        name: "JsonRpcError",
        rpcCode: -32600,
        data: { kind: "unmaterializedThread" },
      }),
    );
    peer.close();
  });
});
