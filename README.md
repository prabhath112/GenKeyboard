<img align="left" width="80" height="80" src=".github/repo_icon.png" alt="App icon">

# GenKeyboard

**GenKeyboard is an Android keyboard with built-in AI writing tools.** Fix grammar, change tone, paraphrase, shorten, expand, translate, draft a reply, summarize, emojify, all from a panel in the keyboard itself, in any app.

## Where the code comes from

GenKeyboard is a fork of [FlorisBoard](https://github.com/florisboard/florisboard) by Patrick Goldinger and the FlorisBoard contributors, licensed under Apache 2.0. Read this before assuming anything about the codebase:

| Part | Origin |
|---|---|
| Keyboard engine: layouts, touch handling, suggestions, glide typing, themes, clipboard, emoji, settings UI | FlorisBoard (upstream commit `5d6e1ef`, 2026-08-21). Lives under `dev.patrickgold.florisboard`. |
| AI writing tools in the keyboard: `app/src/main/kotlin/com/genkeyboard/` | GenKeyboard |
| AI backend, a Cloudflare Worker that holds the LLM keys, applies prompt templates and enforces a per-device daily quota: `backend/` | GenKeyboard |
| Branding, app id `com.genkeyboard.app`, GenKeyboard strings, this README, `PRIVACY.md` | GenKeyboard |

The FlorisBoard copyright headers, `LICENSE` and `NOTICE` stay in place. Apache 2.0 allows this fork and asks for exactly that attribution. Thanks to the FlorisBoard project for the keyboard engine.

## How the AI part works

1. You type in any app, open the AI panel from the smartbar, tap an action.
2. The keyboard sends the field text, the action and a random device id over HTTPS to the GenKeyboard API (`backend/`).
3. The worker picks the first healthy LLM provider (Gemini by default, OpenRouter as fallback), applies the prompt template and a "write like a real person" style rule, strips AI tells such as em dashes, and returns the text.
4. You see the result and choose Replace or Discard. Nothing is inserted without you.

Password fields are detected and the AI tools refuse to send them. The keyboard never holds an LLM key; the worker does. See [`PRIVACY.md`](PRIVACY.md).

## Build and run

Requirements: JDK 21, Android SDK 36, Node 20+.

```
# backend (local, http://127.0.0.1:8787; emulator reaches it as http://10.0.2.2:8787)
cd backend
npm install
cp .dev.vars.example .dev.vars   # fill at least GEMINI_API_KEY
npm run dev
npm test

# app (debug build points at the local worker)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable GenKeyboard under Settings > System > Languages & input, then pick it as the active keyboard.

## Deploy the backend

```
cd backend
npx wrangler login
npx wrangler kv namespace create QUOTA          # paste ids into wrangler.toml
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put OPENROUTER_API_KEY      # optional fallback
npm run deploy
```

Release builds use `AI_BACKEND_URL` from `app/build.gradle.kts`. Point it at your deployed worker.

## CI and releases

[![CI](https://github.com/prabhath112/GenKeyboard/actions/workflows/ci.yml/badge.svg)](https://github.com/prabhath112/GenKeyboard/actions/workflows/ci.yml)

- `CI` runs on every pull request and push to `main`: backend typecheck + tests, Android unit tests + debug APK.
- `Release` runs when a tag `v*` is pushed: builds the signed AAB and APK, verifies the signature, publishes a GitHub Release with SHA256 sums. Tags containing `-` (like `v0.2.0-beta01`) are marked pre-release.

```
git tag v0.2.0 && git push origin v0.2.0
```

## Release signing

Local: copy `keystore.properties.example` to `keystore.properties`, generate a keystore as described inside, then `./gradlew :app:bundleRelease`. Both files are gitignored. Back them up outside the repo.

GitHub Actions needs the same material as repository secrets (Settings → Secrets and variables → Actions, or the `gh` CLI):

```
gh secret set KEYSTORE_BASE64 --body "$(base64 -w0 genkeyboard-release.jks)"
gh secret set KEYSTORE_PASSWORD --body "<storePassword from keystore.properties>"
gh secret set KEY_ALIAS --body "genkeyboard"
gh secret set KEY_PASSWORD --body "<keyPassword from keystore.properties>"
```

LLM API keys are never GitHub secrets. They live only in Cloudflare: `wrangler secret put GEMINI_API_KEY` (and optionally `OPENROUTER_API_KEY`). CI runs the backend tests with mocked providers and needs no key.

## Secrets

Never committed: `backend/.dev.vars`, `keystore.properties`, `*.jks`, `local.properties`. `.env.example` lists every local secret and where to get it.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
