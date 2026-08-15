import { describe, expect, it } from "vitest";
import { AgentService } from "./agent-service.js";
import { APP_VERSION } from "./version.js";

describe("Agent service metadata", () => {
  it("reports the package version", async () => {
    const service = new AgentService("codex");
    try {
      await expect(service.request("service.describe", {})).resolves.toEqual(
        expect.objectContaining({ agentVersion: APP_VERSION }),
      );
    } finally {
      service.close();
    }
  });
});
