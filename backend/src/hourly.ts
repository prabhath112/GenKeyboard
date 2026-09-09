import { alert, type AlertEnv } from "./alerts";
import type { QuotaStore } from "./quota";

/**
 * Hourly Discord status + anomaly detection.
 *
 * Every request bumps an hourly counter (hreq:<YYYY-MM-DDTHH>, hfail:<...>). Once an hour the cron posts a
 * short status line and compares the last full hour against the baseline of the previous 24 hours.
 * ponytail: baseline = mean of non-empty buckets; a spike is > max(SPIKE_MIN, SPIKE_FACTOR x mean). Good enough
 * to catch a script hammering the API; not a real forecast. Upgrade path: per-device hourly buckets.
 */
const DAY_SECONDS = 60 * 60 * 24;
export const SPIKE_FACTOR = 3;
export const SPIKE_MIN = 30;

export function hourKey(d: Date): string {
  return d.toISOString().slice(0, 13); // 2026-09-09T14
}

export async function bumpHourly(store: QuotaStore, ok: boolean, now: Date = new Date()): Promise<void> {
  const h = hourKey(now);
  for (const k of ok ? [`hreq:${h}`] : [`hreq:${h}`, `hfail:${h}`]) {
    const n = Number((await store.get(k)) ?? "0") + 1;
    await store.put(k, String(n), { expirationTtl: DAY_SECONDS * 2 });
  }
}

export interface HourlyStats {
  hour: string;
  requests: number;
  failures: number;
  baseline: number;
  spike: boolean;
}

/** Stats for the hour that just ended, plus the mean of the 24 hours before it. */
export async function hourlyStats(store: QuotaStore, now: Date = new Date()): Promise<HourlyStats> {
  const last = new Date(now.getTime() - 3600_000);
  const hour = hourKey(last);
  const requests = Number((await store.get(`hreq:${hour}`)) ?? "0");
  const failures = Number((await store.get(`hfail:${hour}`)) ?? "0");
  let sum = 0;
  let n = 0;
  for (let i = 2; i <= 25; i++) {
    const v = Number((await store.get(`hreq:${hourKey(new Date(now.getTime() - i * 3600_000))}`)) ?? "0");
    if (v > 0) {
      sum += v;
      n++;
    }
  }
  const baseline = n > 0 ? sum / n : 0;
  const spike = requests > Math.max(SPIKE_MIN, SPIKE_FACTOR * baseline);
  return { hour, requests, failures, baseline, spike };
}

export interface BudgetLine {
  name: string;
  used: number;
  limit: number; // 0 = unlimited
}

/** Post the hourly status; spike or a model past 80% of its daily budget gets its own loud alert. */
export async function hourlyReport(env: AlertEnv, budgets: BudgetLine[], now: Date = new Date()): Promise<void> {
  const s = await hourlyStats(env.QUOTA, now);
  const models = budgets
    .filter((b) => b.used > 0)
    .map((b) => (b.limit > 0 ? `${b.name} ${b.used}/${b.limit}` : `${b.name} ${b.used}`))
    .join(", ");
  await alert(
    env,
    `hourly:${s.hour}`,
    `⏱ **Hourly ${s.hour}Z**  requests: ${s.requests}  failed: ${s.failures}  baseline: ${s.baseline.toFixed(1)}/h\n` +
      `Models today: ${models || "none"}`,
    3600,
  );
  if (s.spike) {
    await alert(
      env,
      `spike:${s.hour}`,
      `🚨 **Usage spike**: ${s.requests} requests in the last hour vs a baseline of ${s.baseline.toFixed(1)}/h. ` +
        `Check Workers Logs for the device ids involved.`,
      3600,
    );
  }
  for (const b of budgets) {
    if (b.limit > 0 && b.used >= b.limit * 0.8 && b.used < b.limit) {
      await alert(
        env,
        `budget80:${b.name}:${now.toISOString().slice(0, 10)}`,
        `⚠️ **${b.name}** at ${b.used}/${b.limit} of today's budget (${Math.round((100 * b.used) / b.limit)}%). Next model takes over when it is spent.`,
        DAY_SECONDS,
      );
    }
  }
}
