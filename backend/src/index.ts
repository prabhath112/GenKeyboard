import { buildMessages, cleanOutput, parseRequest, ValidationError } from "./actions";
import { alert } from "./alerts";
import { parseLimits, ProviderBudget } from "./budget";
import { bumpHourly, hourlyReport } from "./hourly";
import { type IntegrityEnv, integrityConfigured, verifyIntegrityToken } from "./integrity";
import { ProviderChain, providersFromEnv, type ProviderEnv } from "./providers";
import { admitDevice, consumeQuota, type QuotaStore } from "./quota";
import { mintSession, sha256Hex, verifySession } from "./session";

export interface Env extends ProviderEnv, IntegrityEnv {
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
  /**
   * "true" turns the Play Integrity gate on: /v1/transform then needs a session token from
   * /v1/attest. Left off, the service behaves exactly as it did before attestation existed,
   * so the Play Console side can be finished without taking the API down.
   */
  REQUIRE_ATTESTATION?: string;
  /** Secret. HMAC key for session tokens. Rotating it invalidates every live session. */
  SESSION_SECRET?: string;
  /** How long a session survives before the app must attest again. Default 24h. */
  SESSION_TTL_SECONDS?: string;
}

const DEFAULT_SESSION_TTL = 60 * 60 * 24;

const DAY_SECONDS = 60 * 60 * 24;

function dayOf(d: Date): string {
  return d.toISOString().slice(0, 10);
}

async function bump(store: QuotaStore, key: string): Promise<number> {
  return bumpBy(store, key, 1);
}

async function bumpBy(store: QuotaStore, key: string, by: number): Promise<number> {
  const n = Number((await store.get(key)) ?? "0") + by;
  await store.put(key, String(n), { expirationTtl: DAY_SECONDS * 2 });
  return n;
}

const ACTIONS = ["grammar", "tone", "paraphrase", "shorten", "expand", "translate", "reply", "summarize", "emojify", "humanize", "custom"];

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

  if (env.REQUIRE_ATTESTATION === "true") {
    // Fail closed: a gate that silently disables itself on a missing secret is not a gate.
    if (!env.SESSION_SECRET) return error(503, "misconfigured", "attestation required but unavailable");
    const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
    const sessionDevice = bearer ? await verifySession(env.SESSION_SECRET, bearer) : null;
    if (sessionDevice === null) {
      return error(401, "attestation_required", "missing or expired session; call /v1/attest");
    }
    if (sessionDevice.toLowerCase() !== deviceId.toLowerCase()) {
      return error(401, "unauthorized", "session does not match device");
    }
  }

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
  const failures: string[] = [];
  const chain = new ProviderChain(
    providers,
    (err) => {
      console.warn("provider failed", err.message);
      failures.push(err.message.slice(0, 160));
    },
    timeoutMs,
    budget,
  );
  const signal = AbortSignal.timeout(timeoutMs * providers.length);

  const day = dayOf(new Date());
  ctx.waitUntil(bump(env.QUOTA, `req:${day}`));
  ctx.waitUntil(bump(env.QUOTA, `act:${parsed.action}:${day}`));
  const started = Date.now();
  try {
    const { text, provider } = await chain.completeWithSource(buildMessages(parsed), signal);
    // Latency sum + count feed the daily summary's average. ponytail: no percentiles; add a histogram if needed.
    ctx.waitUntil(bump(env.QUOTA, `latn:${day}`));
    ctx.waitUntil(bumpBy(env.QUOTA, `latms:${day}`, Date.now() - started));
    ctx.waitUntil(bumpHourly(env.QUOTA, true));
    // No `remaining` in the response: capacity is managed server-side per model, users never see a counter.
    return json(200, { text: cleanOutput(text), provider });
  } catch (e) {
    const msg = (e as Error).message;
    console.error("all providers failed", msg);
    ctx.waitUntil(bump(env.QUOTA, `fail:${dayOf(new Date())}`));
    ctx.waitUntil(bumpHourly(env.QUOTA, false));
    const detail = failures.length > 0 ? failures.map((f) => `• ${f}`).join("\n") : msg.slice(0, 300);
    ctx.waitUntil(alert(env, "upstream_failed", `All providers failed for a request (action: ${parsed.action}). Users are seeing errors.\n${detail}`, 600));
    return error(502, "upstream_failed", "AI providers unavailable");
  }
}

/**
 * Trades a Play Integrity token for a session token.
 * The app calls this once per session, then reuses the session on /v1/transform.
 */
async function handleAttest(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const deviceId = req.headers.get("X-Device-Id") ?? "";
  if (!DEVICE_ID_RE.test(deviceId)) return error(401, "unauthorized", "missing or malformed X-Device-Id");
  if (!env.SESSION_SECRET || !integrityConfigured(env)) {
    return error(503, "not_configured", "attestation not available");
  }

  let body: { token?: unknown };
  try {
    body = (await req.json()) as { token?: unknown };
  } catch {
    return error(400, "invalid_json", "body is not valid JSON");
  }
  const token = typeof body.token === "string" ? body.token.trim() : "";
  if (!token) return error(400, "invalid_request", "missing token");

  // The app sets the Play Integrity requestHash to SHA-256 of its device id; recomputing it here
  // is what stops a valid token from one device being replayed under another device's id.
  const verdict = await verifyIntegrityToken(env, env.QUOTA, token, await sha256Hex(deviceId));
  if (!verdict.ok) {
    ctx.waitUntil(
      alert(
        env,
        `attest:${verdict.reason}`,
        `Attestation refused (${verdict.reason}) for device ${deviceId.slice(0, 8)}….`,
        600,
      ),
    );
    return error(403, "attestation_failed", verdict.reason);
  }

  const ttl = Number(env.SESSION_TTL_SECONDS ?? DEFAULT_SESSION_TTL);
  return json(200, { session: await mintSession(env.SESSION_SECRET, deviceId, ttl), expiresIn: ttl });
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
  const latN = Number((await env.QUOTA.get(`latn:${day}`)) ?? "0");
  const latMs = Number((await env.QUOTA.get(`latms:${day}`)) ?? "0");
  const avg = latN > 0 ? `${(latMs / latN / 1000).toFixed(1)}s` : "n/a";
  const actions: string[] = [];
  for (const a of ACTIONS) {
    const n = Number((await env.QUOTA.get(`act:${a}:${day}`)) ?? "0");
    if (n > 0) actions.push(`${a} ${n}`);
  }
  actions.sort((x, y) => Number(y.split(" ")[1]) - Number(x.split(" ")[1]));
  await alert(
    env,
    `summary:${day}`,
    `**GenKeyboard daily summary ${day}**\nRequests: ${total}  Failed: ${failed}  Avg latency: ${avg}\n` +
      `Top actions: ${actions.slice(0, 5).join(", ") || "none"}\n` +
      `${lines.join("\n") || "• no provider traffic"}`,
    DAY_SECONDS,
  );
}

/** Top of every hour: status line, usage-spike alert, 80%-of-budget warnings. */
async function hourlyStatus(env: Env): Promise<void> {
  const limits = parseLimits(env.PROVIDER_DAILY_LIMITS);
  const budget = new ProviderBudget(env.QUOTA, limits);
  const lines = [];
  for (const p of providersFromEnv(env)) {
    lines.push({ name: p.name, used: await budget.used(p.name), limit: limits[p.name] ?? 0 });
  }
  await hourlyReport(env, lines);
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) return json(200, { ok: true });
    if (req.method === "POST" && url.pathname === "/v1/attest") return handleAttest(req, env, ctx);
    if (req.method === "POST" && url.pathname === "/v1/transform") return handleTransform(req, env, ctx);
    return error(404, "not_found", "no such route");
  },

  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    // "5 0 * * *" = daily summary; "0 * * * *" = hourly status + spike/budget alerts.
    if (event.cron === "5 0 * * *") {
      ctx.waitUntil(dailySummary(env));
      return;
    }
    ctx.waitUntil(hourlyStatus(env));
  },
};
