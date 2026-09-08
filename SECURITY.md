# Security Policy

## Reporting a vulnerability

Do not open a public issue for security problems.

Use GitHub's private reporting: **Security** tab → **Report a vulnerability** on this repository. You get a private thread with the maintainer.

Please include: affected version or commit, steps to reproduce, impact. Expect a first reply within 7 days.

## Scope

- The Android app (`app/`).
- The GenKeyboard API worker (`backend/`).

Out of scope: vulnerabilities in the upstream FlorisBoard project that are not reachable through GenKeyboard, and issues in third-party LLM providers.

## What the app sends over the network

Only when you tap an AI action: the field text, the action name and a random device id, over HTTPS to the GenKeyboard API. Password fields are refused. See [PRIVACY.md](PRIVACY.md).

## Secrets

LLM keys live only in Cloudflare Worker secrets. The app binary contains no vendor key. `.dev.vars`, `keystore.properties` and `*.jks` are gitignored and GitHub secret scanning with push protection is enabled on this repository.
