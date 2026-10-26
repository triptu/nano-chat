<img src="assets/icon.svg" width="64" height="64" alt="">

# nano-chat

Created in [T3 Code](https://t3.codes).

An Android app for chatting with Gemini Nano on the phone. Each companion is one ongoing chat, like a contact. Developer tools (speed, exact prompts, model settings, stats) sit behind one switch on the profile page.

Needs a phone with Google AICore and Gemini Nano. Emulators fall back to a fake model in debug builds.

```bash
./gradlew :app:installDebug      # or :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

Progress and decisions: [docs/progress.html](docs/progress.html).
