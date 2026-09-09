# GenKeyboard API (Cloudflare Worker)

Proxy between the keyboard and LLM vendors. Holds the API keys, enforces quota, applies prompt templates.
The app never sees a vendor key.

## Run locally
```
npm install
cp .dev.vars.example .dev.vars   # fill in at least one key
npm run dev                      # http://127.0.0.1:8787  (emulator reaches it as http://10.0.2.2:8787)
npm test
```

## Deploy
```
wrangler kv namespace create QUOTA          # paste ids into wrangler.toml
wrangler secret put XAI_API_KEY             # repeat per vendor you want
npm run deploy
```

## Play Integrity attestation

Off by default. `X-Device-Id` alone is self-asserted, so a script can mint device ids at will;
attestation makes the app prove it is a genuine Play build on a genuine device before it may
transform. Turn it on only after every step below is done, or live traffic will start failing.

1. **Play Console** — publish `com.genkeyboard.app` to at least internal testing. Sideloaded
   builds report `UNLICENSED` and are refused.
2. **Google Cloud** — link the project to Play Console, enable the Play Integrity API, then note
   the *project number* and put it in `PLAY_CLOUD_PROJECT_NUMBER` in `app/build.gradle.kts`.
3. **Service account** — create one with Play Integrity access, download its JSON key, then:
   ```
   wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON   # paste the whole JSON file
   wrangler secret put SESSION_SECRET                # openssl rand -base64 32
   ```
4. **Verify before enforcing** — deploy, install the Play build, and confirm `/v1/attest` returns
   a session. A refusal comes back as `403 attestation_failed` with a reason
   (`app_unrecognized`, `device_untrusted`, `unlicensed`, `request_hash_mismatch`, `token_stale`).
5. **Enforce** — set `REQUIRE_ATTESTATION = "true"` in `wrangler.toml` and redeploy.

To roll back, set it to `"false"` and redeploy; sessions already issued stay valid but stop being
checked. The `.debug`, `.beta` and `.bench` variants have suffixed application ids and can never
attest — keep enforcement off while testing those.

## API
`POST /v1/attest` with header `X-Device-Id: <uuid>` and body `{ "token": "<integrity token>" }`
returns `{ "session": "...", "expiresIn": 86400 }`. Only needed when `REQUIRE_ATTESTATION` is on.

`POST /v1/transform` with header `X-Device-Id: <uuid>` (plus `Authorization: Bearer <session>`
when attestation is enforced) and body
```json
{ "action": "tone", "text": "...", "tone": "genz" }
```
Actions: grammar, tone(tone), paraphrase, shorten, expand, translate(language), reply(sentiment),
summarize, emojify, humanize, custom(prompt).

Response: `{ "text": "...", "provider": "xai", "remaining": 49 }`.
Errors: `{ "error": { "code", "message" } }` with 400 / 401 / 429 / 502 / 503.

## Layout
- `src/actions.ts`   request validation + prompt templates (add a feature here)
- `src/providers.ts` Strategy over OpenAI-compatible vendors + fallback chain
- `src/quota.ts`     per-device daily counter on KV
- `src/integrity.ts` Play Integrity token decoding + verdict checks
- `src/session.ts`   stateless HMAC session tokens issued after attestation
- `src/index.ts`     routing, auth header check, error mapping
