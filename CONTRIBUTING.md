# Contributing

Thanks for looking. Small, focused pull requests get merged fastest.

## Ground rules

- Every change goes through a pull request to `main`. Direct pushes are blocked.
- CI must pass: backend typecheck + tests, Android unit tests + debug build.
- The repo owner reviews and merges. Squash merge only.
- Keep FlorisBoard copyright headers on files that came from upstream. New files you write can carry your own name under the same Apache 2.0 license.
- Never commit secrets. `.dev.vars`, `keystore.properties`, `*.jks` are gitignored on purpose. Push protection will reject known key formats.

## Setup

See the README: backend with `npm run dev`, app with `./gradlew :app:assembleDebug`.

## Where things live

- `app/src/main/kotlin/com/genkeyboard/` GenKeyboard AI features.
- `backend/src/actions.ts` prompt templates. Adding an AI action is one entry there plus one catalog entry in `AiActionCatalog.kt`.
- `backend/src/providers.ts` LLM vendors and fallback order.
- Everything under `dev.patrickgold.florisboard` is the FlorisBoard engine. Prefer fixing keyboard-engine bugs upstream when they are not GenKeyboard specific.

## Tests

```
cd backend && npm test
./gradlew :app:testDebugUnitTest
```

Add a test when you change backend logic. One small check that fails if the logic breaks is enough.

## Commit messages

Short imperative subject, blank line, then the why. No secrets, no personal data.
