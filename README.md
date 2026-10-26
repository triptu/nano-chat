<img src="assets/icon.svg" width="64" height="64" alt="">

# nano-chat

Created in [T3 Code](https://t3.codes).

An Android app for chatting with AI models that run on the phone. Each companion is one ongoing chat, like a contact, and runs on either Gemini Nano (built into Android) or Gemma 4 E4B (a 3.7 GB download that also understands photos and voice notes). Developer tools (speed, exact prompts, model settings, stats) sit behind one switch on the profile page.

Gemini Nano needs a phone with Google AICore. Gemma 4 E4B needs about 5 GB of free memory, so a 12 GB phone or more. Emulators fall back to a fake model in debug builds.

```bash
./gradlew :app:installDebug      # or :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

Progress and decisions: [docs/progress.html](docs/progress.html).
