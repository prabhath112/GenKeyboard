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

## API
`POST /v1/transform` with header `X-Device-Id: <uuid>` and body
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
- `src/index.ts`     routing, auth header check, error mapping
