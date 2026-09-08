import { buildMessages, cleanOutput, parseRequest, ValidationError } from "./actions";
import { ProviderChain, providersFromEnv, type ProviderEnv } from "./providers";
import { consumeQuota, type QuotaStore } from "./quota";

export interface Env extends ProviderEnv {
  QUOTA: QuotaStore;
  PROVIDER_TIMEOUT_MS?: string;
  FREE_DAILY_LIMIT?: string;
  MAX_TEXT_CHARS?: string;
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

async function handleTransform(req: Request, env: Env): Promise<Response> {
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

  const quota = await consumeQuota(env.QUOTA, deviceId, Number(env.FREE_DAILY_LIMIT ?? 50));
  if (!quota.allowed) return error(429, "quota_exceeded", "daily limit reached");

  const providers = providersFromEnv(env);
  if (providers.length === 0) return error(503, "no_providers", "no provider configured");

  const timeoutMs = Number(env.PROVIDER_TIMEOUT_MS ?? 10000);
  const chain = new ProviderChain(providers, (err) => console.warn("provider failed", err.message), timeoutMs);
  const signal = AbortSignal.timeout(timeoutMs * providers.length);

  try {
    const { text, provider } = await chain.completeWithSource(buildMessages(parsed), signal);
    return json(200, { text: cleanOutput(text), provider, remaining: quota.remaining });
  } catch (e) {
    console.error("all providers failed", (e as Error).message);
    return error(502, "upstream_failed", "AI providers unavailable");
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) return json(200, { ok: true });
    if (req.method === "POST" && url.pathname === "/v1/transform") return handleTransform(req, env);
    return error(404, "not_found", "no such route");
  },
};
