import assert from "node:assert/strict";
import test from "node:test";
import { createGateOverrides, watchFeatureGates } from "./feature-gates.mjs";

test("model overrides preserve unrelated gates and an existing adapter's private state", () => {
  class Previous {
    #value = { preserved: true };
    getGateOverride(gate) { return { ...gate, value: false, previous: this.#value }; }
    getDynamicConfigOverride() { return this.#value; }
  }
  const previous = new Previous();
  const adapter = createGateOverrides({ ultra: true, settings: true }, previous);
  const gate = { name: "ultra", value: false, details: { reason: "Network", time: 123 } };
  assert.deepEqual(adapter.getGateOverride(gate), {
    ...gate, value: true, ruleID: "override", details: { reason: "LocalOverride", time: 123 },
  });
  assert.equal(gate.value, false, "the official evaluation is not mutated");
  assert.deepEqual(adapter.getGateOverride({ name: "unrelated" }), previous.getGateOverride({ name: "unrelated" }));
  assert.deepEqual(adapter.getDynamicConfigOverride(), { preserved: true });
  assert.equal(createGateOverrides({ ultra: true }).getGateOverride({ name: "unrelated" }), null);
});

test("late and replaced SDK clients refresh once with unchanged identity and no background request", () => {
  let tick, unload, cleared = false;
  const page = {
    setInterval(callback) { tick = callback; return 1; },
    clearInterval(id) { assert.equal(id, 1); cleared = true; },
    addEventListener(event, callback) { assert.equal(event, "pagehide"); unload = callback; },
  };
  const errors = [];
  watchFeatureGates({ ultra: true }, page, error => errors.push(error));
  const identity = { userID: "existing-user" };
  let refreshes = 0;
  function client() {
    return { loadingStatus: "Loading", getContext: () => ({ user: identity }),
      updateUserSync(user, options) {
        assert.equal(user, identity);
        assert.deepEqual(options, { disableBackgroundCacheRefresh: true });
        assert.equal(this.overrideAdapter.getGateOverride({ name: "ultra" }).value, true);
        refreshes++;
        return { success: true };
      } };
  }
  const first = client();
  page.__STATSIG__ = { instances: { sdk: first } };
  tick();
  assert.equal(refreshes, 0);
  first.loadingStatus = "Ready";
  tick(); tick();
  assert.equal(refreshes, 1);
  page.__STATSIG__.instances.sdk = { ...client(), loadingStatus: "Ready" };
  tick(); tick();
  assert.equal(refreshes, 2);
  assert.deepEqual(errors, []);
  unload();
  assert.equal(cleared, true);
});
