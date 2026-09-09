import { buildMessages, cleanOutput, parseRequest, ValidationError } from "./actions";
import { alert } from "./alerts";
import { parseLimits, ProviderBudget } from "./budget";
import { ProviderChain, providersFromEnv, type ProviderEnv } from "./providers";
import { admitDevice, consumeQuota, type QuotaStore } from "./quota";

export interface Env extends ProviderEnv {
  QUOTA: QuotaStore;
  PROVIDER_TIMEOUT_MS?: string;
  /** Hidden abuse cap per device. Never shown to users; only bites a runaway script. */
  DEVICE_DAILY_LIMIT?: string;
  /** Secret. Comma-separated device ids exempt from the cap (owner's phones). */
  UNLIMITED_DEVICE_IDS?: string;
  /** Per-provider daily budgets, e.g. "gemini:gemini-3.1-flash-lite=450,openrouter=100". 0 = unlimited. */
  PROVIDER_DAILY_LIMITS?: string;
  /** Secret. Discord webhook for operator alerts. Unset = alerts off. */
  DISCORD_WEBHOOK_URL?: string;
  /**
   * Closed-beta size: how many distinct devices may ever use the service. Set in the Cloudflare
   * dashboard (Variables and Secrets) so it survives deploys; 0 = open to everyone. Default 5.
   */
  MAX_DEVICES?: string;
  MAX_TEXT_CHARS?: string;
}

const DAY_SECONDS = 60 * 60 * 24;

function dayOf(d: Date): string {
  return d.toISOString().slice(0, 10);
}

async function bump(store: QuotaStore, key: string): Promise<number> {
  const n = Number((await store.get(key)) ?? "0") + 1;
  await store.put(key, String(n), { expirationTtl: DAY_SECONDS * 2 });
  return n;
}

const DEVICE_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function error(status: number, code: string, message: string): Response {
  return json(status, { error: { code, message } });
}

async function handleTransform(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  // ponytail: device id = bearer token. No signup friction, abusable by a determined script.
  // Upgrade path: Play Integrity attestation on first launch -> signed token.
  const deviceId = req.headers.get("X-Device-Id") ?? "";
  if (!DEVICE_ID_RE.test(deviceId)) return error(401, "unauthorized", "missing or malformed X-Device-Id");

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return error(400, "invalid_json", "body is not valid JSON");
  }

  let parsed;
  try {
    parsed = parseRequest(body, Number(env.MAX_TEXT_CHARS ?? 4000));
  } catch (e) {
    if (e instanceof ValidationError) return error(400, "invalid_request", e.message);
    throw e;
  }

  // Owner/test devices listed in the UNLIMITED_DEVICE_IDS secret skip the member limit and the abuse cap.
  const unlimited = (env.UNLIMITED_DEVICE_IDS ?? "").toLowerCase().split(",").map((s) => s.trim());
  if (!unlimited.includes(deviceId.toLowerCase())) {
    if (!(await admitDevice(env.QUOTA, deviceId, Number(env.MAX_DEVICES ?? 5)))) {
      ctx.waitUntil(alert(env, `device_limit:${deviceId}`, `New device ${deviceId.slice(0, 8)}… refused: member limit (${env.MAX_DEVICES ?? 5}) reached. Raise MAX_DEVICES in Cloudflare to admit more.`, DAY_SECONDS));
      // 401 so the app shows its "device not authorized" message without an app update.
      return error(401, "device_limit", "member limit reached");
    }
    const quota = await consumeQuota(env.QUOTA, deviceId, Number(env.DEVICE_DAILY_LIMIT ?? 500));
    if (!quota.allowed) {
      ctx.waitUntil(alert(env, `cap:${deviceId}`, `Device ${deviceId.slice(0, 8)}… hit the daily cap (${env.DEVICE_DAILY_LIMIT ?? 500}). Possible abuse.`, DAY_SECONDS));
      return error(429, "quota_exceeded", "daily limit reached");
    }
  }

  const providers = providersFromEnv(env);
  if (providers.length === 0) return error(503, "no_providers", "no provider configured");

  const timeoutMs = Number(env.PROVIDER_TIMEOUT_MS ?? 10000);
  const budget = new ProviderBudget(
    env.QUOTA,
    parseLimits(env.PROVIDER_DAILY_LIMITS),
    undefined,
    async (name, reason) => {
      await alert(env, `parked:${name}:${dayOf(new Date())}`, `Model **${name}** parked for the rest of the day: ${reason}. Next model in the chain takes over.`, DAY_SECONDS);
    },
  );
  const chain = new ProviderChain(
    providers,
    (err) => console.warn("provider failed", err.message),
    timeoutMs,
    budget,
  );
  const signal = AbortSignal.timeout(timeoutMs * providers.length);

  ctx.waitUntil(bump(env.QUOTA, `req:${dayOf(new Date())}`));
  try {
    const { text, provider } = await chain.completeWithSource(buildMessages(parsed), signal);
    // No `remaining` in the response: capacity is managed server-side per model, users never see a counter.
    return json(200, { text: cleanOutput(text), provider });
  } catch (e) {
    const msg = (e as Error).message;
    console.error("all providers failed", msg);
    ctx.waitUntil(bump(env.QUOTA, `fail:${dayOf(new Date())}`));
    ctx.waitUntil(alert(env, "upstream_failed", `All providers failed for a request. Users are seeing errors.\nLast error: ${msg.slice(0, 300)}`, 600));
    return error(502, "upstream_failed", "AI providers unavailable");
  }
}

/** Midnight UTC summary of the day that just ended. */
async function dailySummary(env: Env): Promise<void> {
  const yesterday = new Date(Date.now() - DAY_SECONDS * 1000);
  const day = dayOf(yesterday);
  const budget = new ProviderBudget(env.QUOTA, parseLimits(env.PROVIDER_DAILY_LIMITS));
  const lines: string[] = [];
  for (const p of providersFromEnv(env)) {
    const n = await budget.used(p.name, yesterday);
    if (n > 0) lines.push(`• ${p.name}: ${n}`);
  }
  const total = Number((await env.QUOTA.get(`req:${day}`)) ?? "0");
  const failed = Number((await env.QUOTA.get(`fail:${day}`)) ?? "0");
  await alert(
    env,
    `summary:${day}`,
    `**GenKeyboard daily summary ${day}**\nRequests: ${total}  Failed: ${failed}\n${lines.join("\n") || "• no provider traffic"}`,
    DAY_SECONDS,
  );
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) return json(200, { ok: true });
    if (req.method === "POST" && url.pathname === "/v1/transform") return handleTransform(req, env, ctx);
    return error(404, "not_found", "no such route");
  },

  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(dailySummary(env));
  },
};
