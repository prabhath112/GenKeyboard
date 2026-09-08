import type { ProviderError } from "./providers";
import type { QuotaStore } from "./quota";

/**
 * Per-provider daily budget, invisible to users. Free tiers cap each model per day
 * (AI Studio: gemini-3.1-flash-lite 500/day, gemini-2.5-flash-lite unlimited). When a
 * provider is spent, the chain skips it and the next one answers.
 *
 * ponytail: KV counters are eventually consistent; a burst may overshoot a limit by a few
 * requests. Limits are set with headroom below the vendor cap for that reason.
 */
export interface ProviderGate {
  allow(name: string): Promise<boolean>;
  record(name: string, err?: ProviderError): Promise<void>;
}

const DAY_SECONDS = 60 * 60 * 24;
const COOLDOWN_SECONDS = 60;

/** "gemini=450,gemini_lite=0,openrouter=100" -> map. 0 or missing = unlimited. */
export function parseLimits(spec: string | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  for (const part of (spec ?? "").split(",")) {
    const [name, n] = part.split("=").map((s) => s.trim());
    if (name && n && Number.isFinite(Number(n))) out[name] = Number(n);
  }
  return out;
}

export class ProviderBudget implements ProviderGate {
  constructor(
    private readonly store: QuotaStore,
    private readonly limits: Record<string, number>,
    private readonly now: () => Date = () => new Date(),
    /** Called once when a provider gets parked for the rest of the day (budget spent or daily 429). */
    private readonly onParked: (name: string, reason: string) => Promise<void> = async () => {},
  ) {}

  /** Today's request count for a provider, for summaries. */
  async used(name: string, day: Date = this.now()): Promise<number> {
    return Number((await this.store.get(`b:${name}:${day.toISOString().slice(0, 10)}`)) ?? "0");
  }

  private dayKey(name: string): string {
    return `b:${name}:${this.now().toISOString().slice(0, 10)}`;
  }

  private coolKey(name: string): string {
    return `cool:${name}`;
  }

  async allow(name: string): Promise<boolean> {
    if (await this.store.get(this.coolKey(name))) return false;
    const limit = this.limits[name] ?? 0;
    if (limit <= 0) return true;
    return Number((await this.store.get(this.dayKey(name))) ?? "0") < limit;
  }

  async record(name: string, err?: ProviderError): Promise<void> {
    const key = this.dayKey(name);
    const used = Number((await this.store.get(key)) ?? "0") + 1;
    await this.store.put(key, String(used), { expirationTtl: DAY_SECONDS * 2 });

    const limit = this.limits[name] ?? 0;
    if (limit > 0 && used === limit) await this.onParked(name, `daily budget of ${limit} spent`);

    if (err?.status !== 429) return;
    // Vendor says we're over quota. Daily quota -> park the provider for the rest of the day.
    // Anything else (per-minute burst) -> short cooldown so the fallback answers meanwhile.
    if (/per.?day|daily|PerDay/i.test(err.message)) {
      await this.store.put(key, String(Math.max(used, limit > 0 ? limit : 1_000_000)), {
        expirationTtl: DAY_SECONDS * 2,
      });
      await this.onParked(name, "vendor returned a daily-quota 429");
    } else {
      await this.store.put(this.coolKey(name), "1", { expirationTtl: COOLDOWN_SECONDS });
    }
  }
}
