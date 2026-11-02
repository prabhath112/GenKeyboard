# Changelog

All notable changes to GenKeyboard. Release notes on GitHub are taken from this file.

## 1.0.3 — 2026-11-02

### Added
- **Add filter** on the AI preview: keep a result and run another tool on it before replacing, so tools chain. The text in your app only changes when you tap Replace.
- **Replace** now goes straight back to the keyboard.
- **New prompt** in My prompts: write a saved AI prompt from the keyboard itself, with the real keys, instead of a settings page.
- A one-time disclosure before the first AI request explains what is sent and to whom. Nothing is sent until you tap Allow.
- **Report this result** on the AI preview opens a prefilled email, without your text.
- AI tools are now off in incognito mode as well as password fields.
- The disclosure and report text is translated into Arabic, Danish, German, Spanish, French, Italian, Japanese, Korean, Dutch, Norwegian, Portuguese, Brazilian Portuguese and Swedish.

### Fixed
- Opening the clipboard crashed the keyboard (#59).
- The Enable AI tools switch did nothing; it now turns the tools off.
- Replace could overwrite text you typed while the AI was working. It now stops and asks you to run the action again.
- Pasting while writing a prompt put the clipboard text into your message instead of the prompt.
- The physical keyboard screen did not update when a keyboard was attached or removed.
- The daily-limit message offered an upgrade that does not exist.

### Changed
- AI results read more like a person typed them: filler words such as "honestly" and "genuinely" and typical AI wording are removed.
- The in-app privacy policy link now opens GenKeyboard's own policy, which lists all five AI providers.

### Security
- The AI service now caps request size and requires JSON.
- Cleartext network traffic is blocked, the learned-word dictionary is no longer included in backups, and extension imports are limited in size and entry count.
- Dependencies updated (wrangler, sharp); no known vulnerabilities. GitHub Actions are pinned to commit SHAs.

## 1.0.1 — 2026-09-10

### Fixed
- No word suggestions or autocorrect in Instagram's message box. Instagram marks that field
  "no suggestions" (it has its own @mention/emoji autocomplete), and GenKeyboard was honoring
  that the same way every other keyboard does. Now overridden so GenKeyboard's own suggestions
  and autocorrect work there too. Password fields are unaffected.
- Autocorrect and word suggestions did nothing when the wrong letter was the *first* one
  (e.g. "qorld"), even though the rest of the word was typed correctly. Autocorrect had a
  filter that specifically excluded first-letter mistakes; suggestions were prefix-only, so a
  wrong first letter meant no dictionary word could ever match. Both now also fall back to
  finding the closest real word when nothing matches, without resurrecting a word you've
  blocked.
- Word suggestions could silently stop working until the keyboard was restarted, with no
  indication why. A local diagnostics log now records suggestion pipeline errors so this can
  be tracked down from a device that hit it: `adb shell run-as com.genkeyboard.app cat files/genkeyboard/suggestion-diagnostics.log`

## 1.0.0 — 2026-09-09

GenKeyboard is an Android keyboard with AI writing tools built in. It is based on the open-source [FlorisBoard](https://github.com/florisboard/florisboard) keyboard engine (Apache 2.0) and adds an AI layer on top.

### AI writing tools
Available from the sparkle button in the keyboard's top row, in any app:

- **Quick fixes**: Fix grammar, Paraphrase, Shorten, Expand, Summarize, Add emojis, Humanize
- **Change tone**: Professional, Casual, Polite, Romantic, Empathetic, Funny, Poetic, Sarcastic, Angry, Flirty, Gen Z, Witty
- **Smart reply**: draft a positive, neutral or negative reply to the message in the field
- **Translate**: English, Spanish, French, German, Danish, Norwegian, Swedish, Portuguese, Italian, Dutch, Hindi, Arabic, Japanese, Korean
- **My prompts**: your own instructions as one-tap chips, managed under Settings › AI writing tools

Results read like a person wrote them. You review every result and choose Replace or Discard; nothing is inserted automatically.

### Typing
- Word suggestions while you type
- Autocorrect on space, with the typed word kept one tap away; toggle it from the smartbar "A" button or under Settings › Typing
- Next-word prediction that learns from your own writing, on your device
- Personal dictionary that learns your words; long-press a suggestion to remove it
- Glide typing, themes, clipboard history, emoji and multi-language layouts from the FlorisBoard engine

### Privacy
- Everything you type stays on your phone. No account, no ads, no analytics.
- Text leaves your device only when you tap an AI action, and only that text, over HTTPS.
- Password fields are excluded from the AI tools.
- Full policy: [PRIVACY.md](PRIVACY.md)

### Early access
AI features are available to a limited number of devices during this phase, with a fair-use daily limit.

### Requirements
Android 8.0 or later.

### Install
Download the APK below, open it, then enable GenKeyboard under Settings › System › Languages & input › On-screen keyboard and select it as your keyboard.
