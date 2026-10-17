// Read official route state; all navigation still goes through official messages.
export function createSettingsNavigation({ notify, navigate, prepare }) {
  let current, source, inSettings = false;
  const isSettings = location => location?.pathname === "/settings" || location?.pathname?.startsWith("/settings/");
  return {
    update(location) {
      if (!location) return;
      const next = isSettings(location);
      if (next && !inSettings) source = current;
      if (!next) source = undefined;
      current = location;
      if (next !== inSettings) { inSettings = next; notify(next); }
    },
    exit() {
      if (!inSettings) return;
      const target = source ?? { pathname: "/", search: "", hash: "", state: null };
      const path = target.pathname + (target.search || "") + (target.hash || "");
      const options = { replace: true, state: target.state };
      // prepareNavigation runs the official unsaved-settings guard. Its returned
      // callback is deliberately not invoked: the official host message navigates.
      if (prepare(path, options) == null) return;
      navigate({ type: "navigate-to-route", path, ...options });
    },
    reset() { current = source = undefined; inSettings = false; notify(false); },
  };
}

export async function installSettingsNavigation(binding, notify, navigate, page = window) {
  async function load(record) {
    const url = new URL(record.bundle, page.document.baseURI);
    const bytes = await (await page.fetch(url)).arrayBuffer();
    const hash = [...new Uint8Array(await page.crypto.subtle.digest("SHA-256", bytes))].map(byte => byte.toString(16).padStart(2, "0")).join("");
    if (hash !== record.sha256) throw new Error("Official navigation bundle hash does not match the verified binding");
    return import(url.href);
  }
  const [state, actions] = await Promise.all([load(binding.state), load(binding.actions)]);
  let scope, unsubscribe;
  const navigation = createSettingsNavigation({ notify, navigate,
    prepare: (path, options) => scope.get(actions[binding.actions.navigationExport]).prepareNavigation(path, options),
  });
  function attach() {
    const next = state[binding.state.contextExport]()?.scope;
    if (next === scope) return;
    unsubscribe?.();
    navigation.reset();
    scope = next;
    unsubscribe = scope?.watch(({ get }) => navigation.update(get(state[binding.state.locationExport])));
  }
  attach();
  const timer = page.setInterval(attach, 250);
  page.addEventListener("pagehide", () => { page.clearInterval(timer); unsubscribe?.(); navigation.reset(); }, { once: true });
  return { ...navigation, applyRecordUpdate: actions[binding.actions.recordUpdateExport] };
}
