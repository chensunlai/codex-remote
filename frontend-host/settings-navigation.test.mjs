import assert from "node:assert/strict";
import test from "node:test";
import { createSettingsNavigation } from "./settings-navigation.mjs";

test("settings sections retain the original route, query, fragment and navigation state", () => {
  const notifications = [], messages = [], prepared = [];
  const nav = createSettingsNavigation({ notify: value => notifications.push(value), navigate: message => messages.push(message),
    prepare: (...args) => { prepared.push(args); return () => assert.fail("The prepared navigation callback must not run"); } });
  const source = { pathname: "/local/first", search: "?hostId=local", hash: "#item", state: { draft: { id: "draft" } } };
  nav.update(source);
  nav.update({ pathname: "/settings/agent" });
  nav.update({ pathname: "/settings/general" });
  nav.exit();
  assert.deepEqual(notifications, [true]);
  assert.deepEqual(messages, [{ type: "navigate-to-route", path: "/local/first?hostId=local#item", replace: true, state: source.state }]);
  assert.deepEqual(prepared, [[messages[0].path, { replace: true, state: source.state }]]);
  nav.update(source);
  nav.update({ pathname: "/local/second", state: null });
  nav.update({ pathname: "/settings" });
  nav.exit();
  assert.equal(messages[1].path, "/local/second");
  assert.deepEqual(notifications, [true, false, true]);
});

test("cancelled official leave confirmation keeps settings and its source; reset discards the source", () => {
  let allowed = false;
  const messages = [];
  const nav = createSettingsNavigation({ notify() {}, navigate: message => messages.push(message), prepare: () => allowed ? () => {} : null });
  nav.update({ pathname: "/local/thread" });
  nav.update({ pathname: "/settings/agent" });
  nav.exit();
  assert.equal(messages.length, 0);
  allowed = true;
  nav.exit();
  assert.equal(messages[0].path, "/local/thread");
  nav.reset();
  nav.exit();
  assert.equal(messages.length, 1);
  nav.update({ pathname: "/settings/agent" });
  nav.exit();
  assert.deepEqual(messages[1], { type: "navigate-to-route", path: "/", state: null, replace: true });
  nav.update({ pathname: "/settings-example" });
  nav.exit();
  assert.equal(messages.length, 2, "Only the settings route is intercepted");
});
