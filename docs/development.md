# Development

Everything about the code that the README leaves out. The code is one Gradle module, `app`, package `ai.tushar.nanochat`. Versions are in `gradle/libs.versions.toml`.

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
./gradlew :app:installDebug        # a debug build onto a connected phone
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest   # AGP 9 runs unit tests on the debug variant only
```

`minSdk` is 31 (Android 12). Release builds are minified. They sign with the key in `~/.config/nano-chat/` when that folder exists, or with the `NANOCHAT_KEYSTORE` and `NANOCHAT_KEYSTORE_PASSWORD` variables, and with the debug key otherwise. Android refuses an update signed with a different key. The version code is the minutes since the start of 2026, so a newer build always installs over an older one. The release workflow lists the APK's SHA-256 and the signing certificate's SHA-256 in each release's notes, so a user can check a download. ML Kit needs the R8 keep rules in `app/proguard-rules.pro`, since the beta crashes on launch without them.

## Models

| | Gemini Nano | Gemma 4 E4B |
|---|---|---|
| Runs through | ML Kit's Prompt API, inside Android's AICore | LiteRT-LM, inside the app, on the GPU when it can |
| Needs | A phone with Google AICore, such as recent Pixel and Galaxy phones | About 5 GB of free memory, so a phone with 12 GB or more |
| Download | Managed by AICore, outside the app | 3.7 GB from Hugging Face, in the app, once |
| Photos | One per message, the newest only | Up to the `maxImages` in `LiteRtModels.kt` |
| Voice notes | No | Yes |
| Uses the phone | Through a JSON format in the prompt | Through native tool calling |

Gemini Nano is the default. It costs the app no memory. Gemma replies better and follows a personality more closely, at the price of a long download, about ten seconds of loading each time the app comes back, and most of the phone's memory while loaded. `Models` frees Gemma a few seconds after the app leaves the screen. With Gemma loaded, Android may still kill the app in the background on a 12 GB phone; the next start shows Try again on the unanswered message.

The exact file size, context window, image budget and audio rate are the one row in `LiteRtModels.kt`. The context window was chosen against a 12 GB phone; the trade-offs are on the progress page. With that window Gemma remembers the last few messages. The model gets a smaller copy of each photo, and older photos drop out of the context before text does.

Gemma's file goes in the app's external files folder, under `models`, so `adb push` to the same path the app downloads to also works. The download is only valid at the exact size in `LiteRtModels.kt`. It runs only while the app is open and resumes where it stopped.

Gemini Nano is only on a phone with AICore. On an emulator, a debug build switches to the fake model, which streams canned replies and can pretend to need a download, be busy, be out of quota or be unsupported. The stats page picks the scenario. Release builds have no fake model.

The default model has a switch on the profile page. Each companion can pick its own model in its edit sheet, and a chat whose model isn't ready shows the download in its place.

## Replies and attachments

Replies keep running when the user leaves a chat. Stop keeps what was written so far. If the app is closed before a reply arrives, the message shows a Try again card the next time the app opens.

The attach button offers Photo and Camera, and on Gemma a voice note too. Tap the mic to start, then cancel, stop to keep it as a draft, or send. Voice notes are capped at `MaxVoiceNoteMs` in `VoiceRecorder.kt`. Photos and voice notes live in app-private storage, never shared storage. Clearing a chat or deleting a companion also deletes its photos and voice notes. The app asks for the camera and microphone the first time the user taps that option.

## Handset

Nano Chat connects to [Handset](https://github.com/triptu/handset) as an MCP agent. Turning on Use this phone in a companion's edit sheet asks Handset for a token; after the user taps Allow, every companion with the switch on shares that one connection. When no companion has it on, the app deletes the token. The token is encrypted with a key in the Android Keystore. Plain HTTP is allowed only to `127.0.0.1` and `localhost`, in `app/src/main/res/xml/network_security_config.xml`.

The tools are listed in `PhoneTools.kt`. The rules in `ReplyLoop.kt` and `PhoneRounds.kt`:

- At most `MaxToolCalls` phone steps per reply. Results are cut to fit the model's context, and the reply says when something was cut.
- No screen reading, tapping or typing. One screen takes more than Gemma's whole context, and small models drive screens poorly.
- Once a reply has read content from the phone, acting tools are withheld for the rest of that reply (`readOnlySpecs`), and a call to one is refused. Text from the phone might be someone else's words, so a message can't make a companion open an app or set an alarm. The user asks again in a new message.
- With Gemini Nano, opening an app ends the reply, since Nano only answers while Nano Chat is on screen.
- Turning off Use this phone stops a reply that is using it. If Handset refuses because a source is off, the reply says what to turn on.

Handset's own rules apply on top. Its README covers what it records and how it approves each agent.

## Developer tools

A switch on the profile page, on by default, shows speed under each reply, the exact prompt behind a chat, each companion's model settings, and a stats page with a card per model. The stats page can also force the real or the fake model.

## Scripted tests on a phone

Debug builds have two broadcast receivers that drive the app without the keyboard, for screenshots and tests. The adb commands are in the comments at the top of `app/src/debug/java/ai/tushar/nanochat/DebugSendReceiver.kt` (send a message, a photo or a voice note to a companion) and `DebugModelReceiver.kt` (download a model, set a companion's model, copy a companion, save a Handset token). Neither is in release builds.

## Known limits

- English only. Interface text is in code, not in resources.
- Gemini Nano sees only the newest photo and no audio.

## Decisions

`docs/progress.html` records what was measured on a real phone, what was decided and why, and what is still open. Add to it when a choice is made, rather than to this file.
