# Play Store listing kit

Everything Play Console asks for, ready to paste. Keep this folder in sync with what is live in the console.

## App details

- **App name**: GenKeyboard
- **Package**: `com.genkeyboard.app`
- **Category**: Tools
- **Tags**: keyboard, AI writing, grammar
- **Contact email**: your Play developer email
- **Privacy policy URL**: https://github.com/prabhath112/GenKeyboard/blob/main/PRIVACY.md
- **Ads**: No
- **In-app purchases**: No (for now)
- **Target audience**: 18+ (avoids the Families policy questionnaire)
- **Content rating**: fill the IARC questionnaire; no violence, no user-generated content shared publicly → Everyone

## Short description (max 80 chars)

See `short_description.txt`.

## Full description (max 4000 chars)

See `full_description.txt`.

## Graphics

| Asset | Requirement | File |
|---|---|---|
| App icon | 512×512 PNG, no alpha | `graphics/icon-512.png` |
| Feature graphic | 1024×500 PNG/JPG | `graphics/feature-1024x500.png` |
| Phone screenshots | 2–8, PNG/JPG, 16:9 or 9:16, ≥320px, ≤3840px | `screenshots/*.png` |

Screenshots are raw captures from a OnePlus (1272×2800). Play accepts them as-is.

## Data safety form

See `data-safety.md`. Answer exactly that; it matches `PRIVACY.md` and the code.

## Release flow

1. Tag a version (`git tag v0.2.0 && git push origin v0.2.0`). The Release workflow publishes a signed AAB.
2. Play Console → Testing → Closed testing → create track → upload the AAB from the GitHub release.
3. First upload: opt in to **Play App Signing**. Google keeps the app signing key; your `genkeyboard-release.jks` becomes the upload key.
4. New personal developer accounts must run a closed test with ≥12 testers for 14 days before production is unlocked.
5. Release notes: copy the GitHub release notes.

## Keyboard-specific review notes

Play reviewers test IMEs manually. In "Release notes / instructions for review":

> GenKeyboard is an input method. After install: Settings → System → Languages & input → On-screen keyboard → enable GenKeyboard, then select it. Type in any text field; tap the sparkle icon in the top row for AI writing tools. AI features send the field text to our server only when an action is tapped (see privacy policy). Password fields are excluded.
