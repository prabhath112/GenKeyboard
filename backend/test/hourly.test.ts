import { describe, expect, it } from "vitest";
import { bumpHourly, hourKey, hourlyStats, SPIKE_MIN } from "../src/hourly";
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

describe("hourly", () => {
  it("counts requests and failures per hour", async () => {
    const s = memStore();
    const t = new Date("2026-09-09T14:20:00Z");
    await bumpHourly(s, true, t);
    await bumpHourly(s, false, t);
    expect(s.data.get(`hreq:${hourKey(t)}`)).toBe("2");
    expect(s.data.get(`hfail:${hourKey(t)}`)).toBe("1");
  });

  it("flags a spike against the 24h baseline, not against quiet hours", async () => {
    const s = memStore();
    const now = new Date("2026-09-09T15:05:00Z");
    // baseline: 5 requests/hour for the 24 hours before the last one
    for (let i = 2; i <= 25; i++) s.data.set(`hreq:${hourKey(new Date(now.getTime() - i * 3600_000))}`, "5");
    s.data.set(`hreq:${hourKey(new Date(now.getTime() - 3600_000))}`, "12");
    let st = await hourlyStats(s, now);
    expect(st.baseline).toBe(5);
    expect(st.spike).toBe(false); // 12 < max(30, 15)

    s.data.set(`hreq:${hourKey(new Date(now.getTime() - 3600_000))}`, String(SPIKE_MIN + 1));
    st = await hourlyStats(s, now);
    expect(st.spike).toBe(true);
  });
});
