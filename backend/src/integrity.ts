/**
 * Play Integrity verification.
 *
 * The app sends an integrity token from the Standard Integrity API; we hand it to Google to
 * decrypt, then check the verdicts. Google's decode endpoint needs an OAuth access token, which
 * means minting a service-account JWT (RS256) and exchanging it. The access token is cached in
 * KV for its lifetime so a burst of attestations does not mint one per request.
 *
 * ponytail: attestation runs once per session (see session.ts), not per transform. Two extra
 * network hops on /v1/attest is fine; on /v1/transform it would not be.
 */

import type { QuotaStore } from "./quota";

export interface IntegrityEnv {
  /** Package name registered in Play Console. Must match requestDetails.requestPackageName. */
  PLAY_PACKAGE_NAME?: string;
  /** Secret. Full service-account JSON with access to the Play Integrity API. */
  GOOGLE_SERVICE_ACCOUNT_JSON?: string;
}

export interface IntegrityResult {
  ok: boolean;
  /** Short machine-readable reason when ok is false. Safe to log; never contains the token. */
  reason: string;
}

const OAUTH_SCOPE = "https://www.googleapis.com/auth/playintegrity";
const ACCESS_TOKEN_KEY = "gauth:playintegrity";
/** Google access tokens last an hour; refresh a little early so none expires mid-flight. */
const ACCESS_TOKEN_TTL = 3300;
/** Reject tokens minted long ago, so a captured one cannot be replayed days later. */
const MAX_TOKEN_AGE_MS = 10 * 60 * 1000;

const ACCEPTED_DEVICE_VERDICTS = ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"];

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/** True once both the package name and the service-account secret are set. */
export function integrityConfigured(env: IntegrityEnv): boolean {
  return Boolean(env.PLAY_PACKAGE_NAME && env.GOOGLE_SERVICE_ACCOUNT_JSON);
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function base64Url(input: string | Uint8Array): string {
  const binary =
    typeof input === "string" ? input : Array.from(input, (b) => String.fromCharCode(b)).join("");
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Self-signed JWT for the OAuth2 jwt-bearer grant. */
async function signAssertion(sa: ServiceAccount, now: number): Promise<string> {
  const issuedAt = Math.floor(now / 1000);
  const tokenUri = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(
    JSON.stringify({
      iss: sa.client_email,
      scope: OAUTH_SCOPE,
      aud: tokenUri,
      iat: issuedAt,
      exp: issuedAt + 3600,
    }),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToPkcs8(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  return `${header}.${claims}.${base64Url(new Uint8Array(signature))}`;
}

async function accessToken(sa: ServiceAccount, store: QuotaStore, now: number): Promise<string> {
  const cached = await store.get(ACCESS_TOKEN_KEY);
  if (cached) return cached;

  const tokenUri = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const assertion = await signAssertion(sa, now);
  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`oauth ${response.status}`);

  const body = (await response.json()) as { access_token?: string };
  if (!body.access_token) throw new Error("oauth returned no access_token");

  await store.put(ACCESS_TOKEN_KEY, body.access_token, { expirationTtl: ACCESS_TOKEN_TTL });
  return body.access_token;
}

interface DecodedVerdict {
  requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string };
  appIntegrity?: { appRecognitionVerdict?: string };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  accountDetails?: { appLicensingVerdict?: string };
}

/** Pure verdict check, split out from the network calls so it can be tested directly. */
export function checkVerdict(
  payload: DecodedVerdict,
  expectedPackage: string,
  expectedHash: string,
  now: number,
): IntegrityResult {
  const request = payload.requestDetails ?? {};

  if (request.requestPackageName !== expectedPackage) return { ok: false, reason: "package_mismatch" };

  // Binds the token to this device id: a token minted on device A cannot authorise device B.
  if (request.requestHash !== expectedHash) return { ok: false, reason: "request_hash_mismatch" };

  const timestamp = Number(request.timestampMillis);
  if (!Number.isFinite(timestamp) || now - timestamp > MAX_TOKEN_AGE_MS) {
    return { ok: false, reason: "token_stale" };
  }

  if (payload.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") {
    return { ok: false, reason: "app_unrecognized" };
  }

  // deviceRecognitionVerdict is an array; empty means the device failed every check.
  const device = payload.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!device.some((v) => ACCEPTED_DEVICE_VERDICTS.includes(v))) {
    return { ok: false, reason: "device_untrusted" };
  }

  if (payload.accountDetails?.appLicensingVerdict !== "LICENSED") {
    return { ok: false, reason: "unlicensed" };
  }

  return { ok: true, reason: "ok" };
}

/**
 * Decrypts the integrity token via Google and checks the verdicts.
 * `expectedHash` is the SHA-256 hex of the device id the caller claims to be.
 */
export async function verifyIntegrityToken(
  env: IntegrityEnv,
  store: QuotaStore,
  integrityToken: string,
  expectedHash: string,
  now: number = Date.now(),
): Promise<IntegrityResult> {
  if (!integrityConfigured(env)) return { ok: false, reason: "not_configured" };

  let sa: ServiceAccount;
  try {
    sa = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON!) as ServiceAccount;
  } catch {
    return { ok: false, reason: "bad_service_account" };
  }

  const packageName = env.PLAY_PACKAGE_NAME!;
  let token: string;
  try {
    token = await accessToken(sa, store, now);
  } catch {
    return { ok: false, reason: "oauth_failed" };
  }

  const response = await fetch(
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(packageName)}:decodeIntegrityToken`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ integrity_token: integrityToken }),
    },
  );
  if (!response.ok) return { ok: false, reason: `decode_${response.status}` };

  const body = (await response.json()) as { tokenPayloadExternal?: DecodedVerdict };
  if (!body.tokenPayloadExternal) return { ok: false, reason: "empty_payload" };

  return checkVerdict(body.tokenPayloadExternal, packageName, expectedHash, now);
}
