import { describe, expect, it } from "vitest";
import { AgentService } from "./agent-service.js";
import { APP_VERSION } from "./version.js";

describe("Agent service metadata", () => {
  it("reports the package version and available channel/runtime capabilities", async () => {
    const service = new AgentService("codex");
    try {
      await expect(service.request("service.describe", {})).resolves.toEqual(
        expect.objectContaining({
          agentVersion: APP_VERSION,
          capabilities: ["codex-raw-channels", "codex-runtime-control", "official-extension-host-v1", "extension-window-management-v1", "terminal", "files", "stream-transfer"],
        }),
      );
    } finally {
      service.close();
    }
  });
});
