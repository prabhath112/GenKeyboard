import type { QuotaStore } from "./quota";

/**
 * Operator alerts to a Discord webhook. Off when DISCORD_WEBHOOK_URL is unset.
 * Same alert key fires at most once per `dedupeSeconds` (KV), so a burst of failures is one message.
 */
export interface AlertEnv {
  DISCORD_WEBHOOK_URL?: string;
  QUOTA: QuotaStore;
}

export async function alert(
  env: AlertEnv,
  key: string,
  message: string,
  dedupeSeconds = 600,
  fetchFn: typeof fetch = (input, init) => fetch(input, init),
): Promise<boolean> {
  if (!env.DISCORD_WEBHOOK_URL) return false;
  const k = `alert:${key}`;
  if (dedupeSeconds > 0) {
    if (await env.QUOTA.get(k)) return false;
    await env.QUOTA.put(k, "1", { expirationTtl: Math.max(60, dedupeSeconds) });
  }
  try {
    const res = await fetchFn(env.DISCORD_WEBHOOK_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content: message.slice(0, 1900), username: "GenKeyboard API" }),
    });
    return res.ok;
  } catch (e) {
    console.warn("discord alert failed", (e as Error).message);
    return false;
  }
}
