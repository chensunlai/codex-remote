import { describe, expect, it } from "vitest";
import { errorMessage } from "./error-message.js";

describe("errorMessage", () => {
  it("unwraps WebSocket ErrorEvent-shaped values", () => {
    expect(errorMessage({
      type: "error",
      error: Object.assign(new Error("TLS handshake failed"), { code: "CERT_ERROR" }),
    })).toBe("TLS handshake failed; code=CERT_ERROR");
  });

  it("keeps useful transport fields without a message", () => {
    expect(errorMessage({
      type: "connect",
      code: "ECONNREFUSED",
      syscall: "connect",
      hostname: "gateway.example.com",
    })).toBe("code=ECONNREFUSED; syscall=connect; hostname=gateway.example.com; connect");
  });
});
