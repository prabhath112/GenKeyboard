import { describe, expect, it } from "vitest";
import { admitDevice } from "../src/quota";
import type { QuotaStore } from "../src/quota";

function memStore(): QuotaStore {
  const data = new Map<string, string>();
  return {
    async get(k) {
      return data.get(k) ?? null;
    },
    async put(k, v) {
      data.set(k, v);
    },
  };
}

describe("admitDevice", () => {
  it("admits the first N devices, keeps admitting known ones, refuses new ones after", async () => {
    const s = memStore();
    expect(await admitDevice(s, "A", 2)).toBe(true);
    expect(await admitDevice(s, "B", 2)).toBe(true);
    expect(await admitDevice(s, "C", 2)).toBe(false);
    expect(await admitDevice(s, "a", 2)).toBe(true); // same device, case-insensitive
    expect(await admitDevice(s, "C", 2)).toBe(false);
  });

  it("0 means no limit", async () => {
    const s = memStore();
    for (const id of ["A", "B", "C", "D"]) expect(await admitDevice(s, id, 0)).toBe(true);
  });
});
