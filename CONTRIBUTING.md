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

## Releases

Branches: `main` is development; `release/<version>` (e.g. `release/1.0.0`, `release/2.0.0`) is one immutable branch per shipped version. Everything else is a short-lived PR branch.

- Versions live in `gradle.properties` (`projectVersionName`, `projectVersionCode`). Bump both in a PR from a throwaway branch `bump/<version>` together with a new `## <version>` section at the top of `CHANGELOG.md`. The changelog section is the user-facing release note; write it for users, not developers.
- After the PR merges, tag `main`: `git tag -a v<version> -m "GenKeyboard <version>" && git push origin v<version>`. The Release workflow then: builds the signed AAB/APK, publishes the GitHub Release with that changelog section, creates `release/<version>` at the tagged commit, and deploys the worker with `wrangler deploy` (repo secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`). Tags containing `-` (e.g. `v1.1.0-beta01`) are marked pre-release.
- Nothing reaches users from `main`. Only a tag ships, and it ships app and worker together.
- `release/**` branches are locked by a repository ruleset: no updates, no force-push, no deletion. They are snapshots, not work branches.
- Hotfix for a shipped version while `main` has unreleased work: `git checkout -b hotfix/<version>.<patch> v<version>`, fix, PR into `main` if it applies there too, then tag `v<version>.<patch>` on the hotfix commit. The tag produces `release/<version>.<patch>` and deploys, exactly as above.

## Commit messages

Short imperative subject, blank line, then the why. No secrets, no personal data.
