import assert from "node:assert/strict";
import test from "node:test";
import { createClientCoordination } from "./client-coordination.mjs";

const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
  const sent = [];
  const events = [];
  const host = createClientCoordination(message => sent.push(message));
  host.setView({
    getThreadRole: async ({ conversationId }) => conversationId === "owned" ? "owner" : "follower",
    requestThreadFollower: async request => { events.push(request); return { method: request.request.method, result: { accepted: true } }; },
    clientStatusChanged: async event => events.push(event),
    ipcConnectionReset: async event => events.push({ reset: event }),
    threadStreamStateChanged: async event => events.push(event),
  });
  const reply = (call, result, extra = {}) => host.receive({ type: "message", message: {
    type: "response", requestId: call.requestId, method: call.method, resultType: "success", handledByClientId: "owner", result, ...extra,
  } });
  async function connect() {
    host.connect(true);
    host.receive({ type: "connected" });
    reply(sent.at(-1).message, { clientId: "phone" });
    await tick();
  }
  return { host, sent, events, reply, connect };
}

test("discovery waits for IPC initialization and preserves the official response envelope", async () => {
  const f = fixture();
  f.host.connect(true);
  const owner = f.host.service.findThreadOwner({ hostId: "local", conversationId: "external" });
  f.host.receive({ type: "connected" });
  f.reply(f.sent.at(-1).message, { clientId: "phone" });
  await tick();
  const discovery = f.sent.at(-1).message;
  assert.equal(discovery.method, "thread-owner-discovery");
  f.reply(discovery, {});
  assert.equal(await owner, "owner");
  const operation = f.host.service.requestThreadFollower({ hostId: "local", targetClientId: "owner", request: {
    method: "thread-follower-interrupt-turn", params: { conversationId: "external", expectedTurnId: "turn-1" },
  } });
  await tick();
  const call = f.sent.at(-1).message;
  assert.equal(call.version, 4);
  assert.equal(call.targetClientId, "owner");
  assert.equal(call.hostId, undefined);
  f.reply(call, { interrupted: true });
  assert.deepEqual(await operation, { type: "response", requestId: call.requestId, method: call.method,
    handledByClientId: "owner", resultType: "success", result: { interrupted: true } });
  f.host.disconnect();
});

test("routes owner operations into the official view and rejects wrong versions or hosts", async () => {
  const f = fixture();
  await f.connect();
  for (const [version, hostId, expected] of [[2, undefined, true], [1, undefined, false], [2, "other", false]]) {
    const call = { type: "request", requestId: "approve", method: "thread-follower-start-turn", version,
      params: { conversationId: "owned", turnStart: { input: [] } }, hostId };
    f.host.receive({ type: "message", message: { type: "client-discovery-request", requestId: "discover", request: call } });
    await tick();
    assert.equal(f.sent.at(-1).message.response.canHandle, expected);
    f.host.receive({ type: "message", message: call });
    await tick();
    assert.equal(f.sent.at(-1).message.resultType, expected ? "success" : "error");
  }
  assert.equal(f.events.filter(event => event.request).length, 1);
  f.host.disconnect();
});

test("only delivers compatible targeted streams and leaves read markers local", async () => {
  const f = fixture();
  await f.connect();
  const stream = { type: "broadcast", sourceClientId: "owner", method: "thread-stream-state-changed",
    version: 11, targetClientIds: ["phone"], params: { conversationId: "external", opaqueSnapshot: [1, 2] } };
  for (const message of [stream, { ...stream, version: 10 }, { ...stream, targetClientIds: ["someone-else"] }]) {
    f.host.receive({ type: "message", message });
  }
  await tick();
  assert.deepEqual(f.events.filter(event => event.sourceClientId === "owner"), [{ sourceClientId: "owner", params: stream.params }]);
  const count = f.sent.length;
  await f.host.service.threadReadStateChanged({ hostId: "local", conversationId: "external" });
  assert.equal(f.sent.length, count);
  f.host.disconnect();
});

test("disconnect rejects a pending mutation and reconnect never resends it", async () => {
  const f = fixture();
  await f.connect();
  const operation = f.host.service.requestThreadFollower({ hostId: "local", targetClientId: "owner", request: {
    method: "thread-follower-command-approval-decision", params: { conversationId: "external", decision: "accept" },
  } });
  const failed = assert.rejects(operation, /outcome may be unknown/);
  await tick();
  f.host.disconnect();
  await failed;
  await f.connect();
  assert.equal(f.sent.filter(event => event.message?.method === "thread-follower-command-approval-decision").length, 1);
  assert.equal(f.events.filter(event => event.reset).length, 1);
  f.host.disconnect();
});

test("unavailable official IPC falls back to local ownership without claiming an external writer", async () => {
  const f = fixture();
  f.host.connect(true);
  const owner = f.host.service.findThreadOwner({ hostId: "local", conversationId: "external" });
  f.host.receive({ type: "unavailable" });
  assert.equal(await owner, null);
  assert.equal(f.sent.filter(event => event.type === "message").length, 0);
  await assert.rejects(f.host.service.requestThreadFollower({ hostId: "local", request: {
    method: "thread-follower-start-turn", params: { conversationId: "external" },
  } }), /not-connected/);
});

test("untrusted app input is sent only after the targeted owner advertises support", async () => {
  const f = fixture();
  await f.connect();
  const operation = f.host.service.requestThreadFollower({ hostId: "local", targetClientId: "owner", request: {
    method: "thread-follower-start-turn", params: { conversationId: "external", turnStart: { context: { responseItems: [{}] } } },
  } });
  const rejected = assert.rejects(operation, /untrusted app input/);
  await tick();
  f.reply(f.sent.at(-1).message, { supportsUntrustedAppInput: false });
  await rejected;
  assert.equal(f.sent.filter(event => event.message?.method === "thread-follower-start-turn").length, 0);
  f.host.disconnect();
});
