import { describe, expect, it } from "vitest";
import { parseLimits, ProviderBudget } from "../src/budget";
import { ProviderError } from "../src/providers";
import type { QuotaStore } from "../src/quota";

function memStore(): QuotaStore & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return {
    data,
    async get(k) {
      return data.get(k) ?? null;
    },
    async put(k, v) {
      data.set(k, v);
    },
  };
}

describe("parseLimits", () => {
  it("parses name=number pairs, ignores junk", () => {
    expect(parseLimits("gemini=450, gemini_lite=0,openrouter=abc,,=5")).toEqual({ gemini: 450, gemini_lite: 0 });
    expect(parseLimits(undefined)).toEqual({});
  });
});

describe("ProviderBudget", () => {
  it("blocks a provider once its daily limit is spent, unlimited when 0", async () => {
    const b = new ProviderBudget(memStore(), { gemini: 2, gemini_lite: 0 });
    expect(await b.allow("gemini")).toBe(true);
    await b.record("gemini");
    await b.record("gemini");
    expect(await b.allow("gemini")).toBe(false);
    for (let i = 0; i < 10; i++) await b.record("gemini_lite");
    expect(await b.allow("gemini_lite")).toBe(true);
  });

  it("parks a provider for the day on a daily-quota 429, cools down on other 429s", async () => {
    const store = memStore();
    const b = new ProviderBudget(store, { gemini: 100, openrouter: 0 });
    await b.record("gemini", new ProviderError("gemini", 429, "HTTP 429 quota exceeded: GenerateRequestsPerDayPerProject"));
    expect(await b.allow("gemini")).toBe(false);

    await b.record("openrouter", new ProviderError("openrouter", 429, "HTTP 429 rate limited"));
    expect(await b.allow("openrouter")).toBe(false);
    expect(store.data.has("cool:openrouter")).toBe(true);
  });

  it("does not count a non-429 failure as exhaustion", async () => {
    const b = new ProviderBudget(memStore(), { gemini: 5 });
    await b.record("gemini", new ProviderError("gemini", 502, "HTTP 502"));
    expect(await b.allow("gemini")).toBe(true);
  });
});
