// Configure the bundled Statsig SDK through its override adapter, without
// rewriting the official frontend or changing app-server model capabilities.
export function createGateOverrides(gates, previous = null) {
  const getGateOverride = (gate, user, options) => {
    if (!Object.hasOwn(gates, gate.name)) return previous?.getGateOverride?.(gate, user, options) ?? null;
    return { ...gate, value: gates[gate.name], ruleID: "override",
      details: { ...gate.details, reason: "LocalOverride" } };
  };
  return new Proxy(previous ?? {}, {
    get(target, key) {
      if (key === "getGateOverride") return getGateOverride;
      const value = Reflect.get(target, key, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

export function watchFeatureGates(gates, page = window, onError = () => {}) {
  const configured = new WeakSet();
  function apply() {
    // Account changes can replace the client. Each instance is configured once,
    // after initialization, so an in-flight user update is never interrupted.
    for (const client of Object.values(page.__STATSIG__?.instances ?? {})) {
      if (configured.has(client) || client.loadingStatus !== "Ready") continue;
      configured.add(client);
      try {
        const user = client.getContext().user;
        client.overrideAdapter = createGateOverrides(gates, client.overrideAdapter);
        // The public refresh path clears cached evaluations and notifies React.
        // Keep the identity and cached assignments; do not fetch new assignments.
        const result = client.updateUserSync(user, { disableBackgroundCacheRefresh: true });
        if (!result.success) throw result.error ?? new Error("Feature gate refresh failed");
      } catch (error) { onError(error); }
    }
  }
  apply();
  const timer = page.setInterval(apply, 500);
  const stop = () => page.clearInterval(timer);
  page.addEventListener("pagehide", stop, { once: true });
  return stop;
}
