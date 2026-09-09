import { describe, expect, it } from "vitest";
import { mintSession, sha256Hex, verifySession } from "../src/session";

const SECRET = "test-secret-value";
const DEVICE = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
const NOW = 1_700_000_000_000;

describe("session tokens", () => {
  it("round-trips a device id", async () => {
    const token = await mintSession(SECRET, DEVICE, 3600, NOW);
    expect(await verifySession(SECRET, token, NOW)).toBe(DEVICE);
  });

  it("rejects an expired token", async () => {
    const token = await mintSession(SECRET, DEVICE, 60, NOW);
    expect(await verifySession(SECRET, token, NOW + 61_000)).toBeNull();
    expect(await verifySession(SECRET, token, NOW + 59_000)).toBe(DEVICE);
  });

  it("rejects a token signed with a different secret", async () => {
    const token = await mintSession(SECRET, DEVICE, 3600, NOW);
    expect(await verifySession("other-secret", token, NOW)).toBeNull();
  });

  it("rejects a tampered payload", async () => {
    const token = await mintSession(SECRET, DEVICE, 3600, NOW);
    const [, signature] = token.split(".");
    const forged = Buffer.from(`${DEVICE}:99999999999`).toString("base64url");
    expect(await verifySession(SECRET, `${forged}.${signature}`, NOW)).toBeNull();
  });

  it("rejects malformed input without throwing", async () => {
    for (const bad of ["", "nodot", "a.b.c", "!!!.???", "."]) {
      expect(await verifySession(SECRET, bad, NOW)).toBeNull();
    }
  });
});

describe("sha256Hex", () => {
  it("matches the well-known digest of 'abc'", async () => {
    expect(await sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});
