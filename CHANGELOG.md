# Changelog

All notable changes to GenKeyboard. Release notes on GitHub are taken from this file.

## 1.0.1 — 2026-09-10

### Fixed
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
