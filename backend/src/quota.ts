/**
 * Per-device daily quota on Workers KV.
 * ponytail: KV is eventually consistent, so a burst can overshoot the limit by a few requests.
 * Upgrade path: Durable Object counter per device if exact limits matter (paid tier).
 */
export interface QuotaStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, opts?: { expirationTtl?: number }): Promise<void>;
}

export interface QuotaResult {
  allowed: boolean;
  remaining: number;
}

const DAY_SECONDS = 60 * 60 * 24;

/**
 * Closed beta gate: only the first `maxDevices` distinct device ids ever seen are admitted.
 * Registered devices never expire; free a slot by deleting its `dev:<id>` key in the KV dashboard.
 * ponytail: KV is eventually consistent, so two brand-new devices in the same second may both get in.
 */
export async function admitDevice(store: QuotaStore, deviceId: string, maxDevices: number): Promise<boolean> {
  if (maxDevices <= 0) return true;
  const key = `dev:${deviceId.toLowerCase()}`;
  if (await store.get(key)) return true;
  const count = Number((await store.get("devcount")) ?? "0");
  if (count >= maxDevices) return false;
  await store.put(key, new Date().toISOString());
  await store.put("devcount", String(count + 1));
  return true;
}

export function quotaKey(deviceId: string, now = new Date()): string {
  return `q:${deviceId}:${now.toISOString().slice(0, 10)}`;
}

export async function consumeQuota(
  store: QuotaStore,
  deviceId: string,
  dailyLimit: number,
  now = new Date(),
): Promise<QuotaResult> {
  const key = quotaKey(deviceId, now);
  const used = Number((await store.get(key)) ?? "0");
  if (used >= dailyLimit) return { allowed: false, remaining: 0 };
  await store.put(key, String(used + 1), { expirationTtl: DAY_SECONDS * 2 });
  return { allowed: true, remaining: dailyLimit - used - 1 };
}
