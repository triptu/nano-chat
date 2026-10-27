# Nano Chat

An Android app for chatting with AI models that run on the phone. README.md covers what it does and how to install it. docs/development.md covers the code's layout, building, the models and scripted tests on a phone. docs/progress.html is the decision log.

## Commands

```bash
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest    # AGP 9 runs unit tests on the debug variant only
```

Releases: the `/release` skill in `.claude/skills/release` publishes a GitHub Release with the signed APK.

AGENTS.md is a symlink to this file. Edit CLAUDE.md only.
