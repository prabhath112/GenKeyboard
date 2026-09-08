import { describe, expect, it } from "vitest";
import { alert } from "../src/alerts";
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

describe("alert", () => {
  it("is a no-op without a webhook url", async () => {
    let calls = 0;
    const f = (async () => {
      calls++;
      return new Response(null, { status: 204 });
    }) as typeof fetch;
    expect(await alert({ QUOTA: memStore() }, "x", "msg", 600, f)).toBe(false);
    expect(calls).toBe(0);
  });

  it("posts once, then dedupes the same key", async () => {
    const bodies: string[] = [];
    const f = (async (_u: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(String(init?.body));
      return new Response(null, { status: 204 });
    }) as typeof fetch;
    const env = { QUOTA: memStore(), DISCORD_WEBHOOK_URL: "https://discord.test/hook" };
    expect(await alert(env, "k", "first", 600, f)).toBe(true);
    expect(await alert(env, "k", "second", 600, f)).toBe(false);
    expect(await alert(env, "other", "third", 600, f)).toBe(true);
    expect(bodies).toHaveLength(2);
    expect(JSON.parse(bodies[0]).content).toBe("first");
  });
});
