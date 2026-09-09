/**
 * Stateless session tokens issued after a device passes Play Integrity.
 *
 * Format: `<b64url(deviceId:expiryEpochSeconds)>.<b64url(hmacSha256)>`
 *
 * ponytail: stateless HMAC, not a KV-backed session table. Keeps /v1/transform at the same
 * KV cost it had before attestation existed, and there is nothing to garbage collect.
 * The trade-off is no server-side revocation: a leaked session stays valid until it expires.
 * Upgrade path: a `rev:<deviceId>` KV key checked on verify, if you ever need to kick a device
 * before its token runs out.
 */

const encoder = new TextEncoder();

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text: string): Uint8Array {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(text.length / 4) * 4, "=");
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
    "verify",
  ]);
}

/** SHA-256 hex. The app hashes its device id the same way for the Play Integrity requestHash. */
export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(text));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function mintSession(
  secret: string,
  deviceId: string,
  ttlSeconds: number,
  now: number = Date.now(),
): Promise<string> {
  const expiry = Math.floor(now / 1000) + ttlSeconds;
  const payload = `${deviceId}:${expiry}`;
  const signature = await crypto.subtle.sign("HMAC", await hmacKey(secret), encoder.encode(payload));
  return `${toBase64Url(encoder.encode(payload))}.${toBase64Url(new Uint8Array(signature))}`;
}

/**
 * Returns the device id the token was minted for, or null if it is malformed, forged or expired.
 * Signature comparison goes through crypto.subtle.verify, which is constant time.
 */
export async function verifySession(
  secret: string,
  token: string,
  now: number = Date.now(),
): Promise<string | null> {
  const parts = token.split(".");
  if (parts.length !== 2) return null;

  let payload: string;
  let signature: Uint8Array;
  try {
    payload = new TextDecoder().decode(fromBase64Url(parts[0]));
    signature = fromBase64Url(parts[1]);
  } catch {
    return null;
  }

  const valid = await crypto.subtle.verify("HMAC", await hmacKey(secret), signature, encoder.encode(payload));
  if (!valid) return null;

  // Split from the right: a device id is a UUID, but never assume it holds no colon.
  const separator = payload.lastIndexOf(":");
  if (separator < 1) return null;
  const deviceId = payload.slice(0, separator);
  const expiry = Number(payload.slice(separator + 1));
  if (!Number.isFinite(expiry) || expiry * 1000 <= now) return null;

  return deviceId;
}
