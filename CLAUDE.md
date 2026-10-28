# Nano Chat

An Android app for chatting with AI models that run on the phone. README.md is the product page: what it does, the pictures, and how to install it. Keep commands, runtimes, sizes, limits and anything else an agent needs out of it. docs/development.md holds all of that: the code's layout, building, the models and their runtimes, the emulator's fake model, Handset's tool rules, developer tools and scripted tests on a phone. docs/progress.html is the decision log.

## Commands

```bash
./gradlew :app:installDebug         # a debug build onto a connected phone
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest    # AGP 9 runs unit tests on the debug variant only
```

Releases: the `/release` skill in `.claude/skills/release` publishes a GitHub Release with the signed APK.

AGENTS.md is a symlink to this file. Edit CLAUDE.md only.
