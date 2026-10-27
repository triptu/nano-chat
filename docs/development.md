# Development

What a developer needs beyond the README. The code is one Gradle module, `app`, package `ai.tushar.nanochat`. Versions are in `gradle/libs.versions.toml`.

## Layout

- `model/`: the model behind one interface. `ModelBackend` has the ML Kit, LiteRT and fake implementations. `Models` picks real or fake per model and holds at most one heavy engine in memory. `PromptWindow` fits the personality and recent turns into the token budget. `ChatEngine` runs one reply at a time; `ReplyLoop` runs the tool rounds inside a reply.
- `litert/`: Gemma through LiteRT-LM. `LiteRtModels.kt` is the catalogue, one row per model file, with its size and context window. `ModelDownload.kt` resumes a cut-off download from a `.part` file.
- `handset/`: the MCP client for Handset, the connection and its token, and `PhoneTools.kt`, the tools a companion is offered.
- `attachments/`: photos and voice notes in app-private storage, and the WAV recorder.
- `data/`: the Room database and the starter companions.
- `devtools/`: every developer surface, shown only when `LocalDevTools` is true.
- `chat/`, `home/`, `agents/`, `profile/`, `gate/`: the screens.

## Building

```bash
./gradlew :app:installDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest    # AGP 9 runs unit tests on the debug variant only
```

Release builds are minified. They sign with the key in `~/.config/nano-chat/` when that folder exists, or with the `NANOCHAT_KEYSTORE` and `NANOCHAT_KEYSTORE_PASSWORD` variables, and with the debug key otherwise. Android refuses an update signed with a different key. The version code is the minutes since the start of 2026, so a newer build always installs over an older one. ML Kit needs the R8 keep rules in `app/proguard-rules.pro`, since the beta crashes on launch without them.

## Models

Gemini Nano is only on a phone with AICore. On an emulator, a debug build switches to the fake model, which streams canned replies and can pretend to need a download, be busy, be out of quota or be unsupported. The stats page picks the scenario.

Gemma's file goes in the app's external files folder, under `models`, so `adb push` to the same path the app downloads to also works. The download is only valid at the exact size in `LiteRtModels.kt`. The context window is set there too, and the number was chosen against a 12 GB phone; the trade-offs are on the progress page.

## Scripted tests on a phone

Debug builds have two broadcast receivers that drive the app without the keyboard, for screenshots and tests. The adb commands are in the comments at the top of `app/src/debug/java/ai/tushar/nanochat/DebugSendReceiver.kt` (send a message, a photo or a voice note to a companion) and `DebugModelReceiver.kt` (download a model, set a companion's model, copy a companion, save a Handset token). Neither is in release builds.

## Decisions

`docs/progress.html` records what was measured on a real phone, what was decided and why, and what is still open. Add to it when a choice is made, rather than to this file.
