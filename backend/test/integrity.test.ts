import { describe, expect, it } from "vitest";
import { checkVerdict, integrityConfigured } from "../src/integrity";

const PACKAGE = "com.genkeyboard.app";
const HASH = "a".repeat(64);
const NOW = 1_700_000_000_000;

function verdict(overrides: Record<string, unknown> = {}) {
  return {
    requestDetails: { requestPackageName: PACKAGE, requestHash: HASH, timestampMillis: String(NOW - 1000) },
    appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED" },
    deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
    accountDetails: { appLicensingVerdict: "LICENSED" },
    ...overrides,
  };
}

describe("checkVerdict", () => {
  it("accepts a genuine app on a genuine licensed device", () => {
    expect(checkVerdict(verdict(), PACKAGE, HASH, NOW)).toEqual({ ok: true, reason: "ok" });
  });

  it("accepts MEETS_STRONG_INTEGRITY", () => {
    const v = verdict({ deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_STRONG_INTEGRITY"] } });
    expect(checkVerdict(v, PACKAGE, HASH, NOW).ok).toBe(true);
  });

  it("rejects a token minted for another device id", () => {
    expect(checkVerdict(verdict(), PACKAGE, "b".repeat(64), NOW)).toEqual({
      ok: false,
      reason: "request_hash_mismatch",
    });
  });

  it("rejects another package name", () => {
    expect(checkVerdict(verdict(), "com.someone.else", HASH, NOW).reason).toBe("package_mismatch");
  });

  it("rejects a stale token", () => {
    const v = verdict({
      requestDetails: { requestPackageName: PACKAGE, requestHash: HASH, timestampMillis: String(NOW - 11 * 60 * 1000) },
    });
    expect(checkVerdict(v, PACKAGE, HASH, NOW).reason).toBe("token_stale");
  });

  it("rejects an unrecognized or sideloaded app", () => {
    expect(checkVerdict(verdict({ appIntegrity: { appRecognitionVerdict: "UNRECOGNIZED_VERSION" } }), PACKAGE, HASH, NOW).reason).toBe("app_unrecognized");
    expect(checkVerdict(verdict({ accountDetails: { appLicensingVerdict: "UNLICENSED" } }), PACKAGE, HASH, NOW).reason).toBe("unlicensed");
  });

  it("rejects a rooted or emulated device", () => {
    // An empty verdict array is what a rooted or attacked device produces.
    expect(checkVerdict(verdict({ deviceIntegrity: { deviceRecognitionVerdict: [] } }), PACKAGE, HASH, NOW).reason).toBe("device_untrusted");
    expect(checkVerdict(verdict({ deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_BASIC_INTEGRITY"] } }), PACKAGE, HASH, NOW).reason).toBe("device_untrusted");
  });

  it("rejects a payload with missing sections", () => {
    expect(checkVerdict({}, PACKAGE, HASH, NOW).ok).toBe(false);
  });
});

describe("integrityConfigured", () => {
  it("needs both the package name and the service account", () => {
    expect(integrityConfigured({})).toBe(false);
    expect(integrityConfigured({ PLAY_PACKAGE_NAME: PACKAGE })).toBe(false);
    expect(integrityConfigured({ PLAY_PACKAGE_NAME: PACKAGE, GOOGLE_SERVICE_ACCOUNT_JSON: "{}" })).toBe(true);
  });
});
