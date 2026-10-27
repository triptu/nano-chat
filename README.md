# Nano Chat

Nano Chat is an Android app for talking to AI models that run on the phone itself, so nothing you write leaves it. Each companion is one ongoing chat, like a contact, with its own name, colour and personality. A companion runs on Gemini Nano, the model built into recent Android phones, or on Gemma 4 E4B, a larger model you download once that also understands photos and voice notes. With [Handset](https://github.com/triptu/handset) installed, a companion can also use the phone: search what you saw, catch up on messages, open an app, set an alarm.

<p>
<img src="docs/shots/handset_chat-light.png" width="240" alt="A chat with Nano. It searched the phone for a word, then opened Clock, with a line under each reply for the step it took.">
<img src="docs/shots/fold_gemma_photo.jpg" width="240" alt="Basil, on Gemma 4 E4B, answering a photo of a fridge with two dinner ideas.">
<img src="docs/shots/handset_edit2.png" width="240" alt="The edit sheet for a companion: its personality, the model it runs on, and the Use this phone switch.">
</p>

Left to right: a companion using the phone through Handset, Gemma 4 E4B answering a photo on a Galaxy Z Fold 7, and a companion's edit sheet.

## The two models

| | Gemini Nano | Gemma 4 E4B |
|---|---|---|
| Runs through | ML Kit's Prompt API, inside Android's AICore | LiteRT-LM, inside the app, on the GPU when it can |
| Needs | A phone with Google AICore, such as recent Pixel and Galaxy phones | About 5 GB of free memory, so a phone with 12 GB or more |
| Download | Managed by AICore, outside the app | 3.7 GB from Hugging Face, in the app, once |
| Photos | One per message | Yes |
| Voice notes | No | Yes |
| Uses the phone | Through a JSON format in the prompt | Through native tool calling |

Gemini Nano is the default. It needs no download in the app and costs the app no memory. Gemma 4 E4B replies better and follows a personality more closely. The price is a long download, about ten seconds of loading each time the app comes back, and most of the phone's memory while it is loaded. Nano Chat frees Gemma a few seconds after the app leaves the screen, so the phone has its memory back. The exact sizes and context windows are in `app/src/main/java/ai/tushar/nanochat/litert/LiteRtModels.kt`.

The default model has a switch on the profile page. Each companion can pick its own model in its edit sheet, and a chat whose model isn't ready shows the download in its place.

## Install

Nano Chat needs Android 12 or later. On the phone, download [nano-chat.apk](https://github.com/triptu/nano-chat/releases/latest/download/nano-chat.apk), the newest release, and open it. Android asks once to allow installs from your browser or files app. It isn't on Play yet. Each [release](https://github.com/triptu/nano-chat/releases) lists the APK's SHA-256 and its signing certificate, so you can check what you install. A newer APK installs over the old one and keeps your chats.

To build it yourself:

```bash
./gradlew :app:installDebug        # a debug build onto a connected phone
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest
```

An emulator has no Gemini Nano. Debug builds fall back to a fake model there, which streams canned replies, so you can still try the app. Release builds don't.

## Companions

A fresh install has three companions: Nano, a general helper; Basil, a home cook; and Sunny, who gives pep talks. New friend makes another. Each has a name, a colour, a personality written in plain words, and a model. The personality goes to the model with every reply. Clearing a chat or deleting a companion also deletes its photos and voice notes.

Replies keep running when you leave a chat. Stop keeps what was written so far. If the app is closed before a reply arrives, the message shows a Try again card the next time the app opens.

## Photos and voice notes

The attach button offers Photo and Camera, and on Gemma 4 E4B a voice note too. Voice notes are up to 30 seconds. Tap the mic to start, then cancel, stop to keep it as a draft, or send. Photos and voice notes stay in the app's private storage, never in shared storage. The model gets a smaller copy of each photo, and older photos drop out of its context before text does.

## Using the phone through Handset

[Handset](https://github.com/triptu/handset) is a separate app that lets AI agents operate the phone. Nano Chat connects to it as one of those agents.

Install Handset, open a companion's edit sheet, and turn on **Use this phone**. Handset shows what Nano Chat asks for, with every source off until you switch it on. After you tap Allow, every companion you switch on uses that one connection.

A companion can then search what you saw on screen and in notifications, catch up on messages, read a day's timeline, check the phone's status, open apps, set alarms and timers, switch Do not disturb, and see or control what's playing. Each step shows as a line under the reply. Tap it to see what came back. If Handset says no because a source is off, the reply says what to turn on.

What a companion may do is limited on purpose:

- At most 3 phone steps per reply. Results are cut to fit the model's context, and the reply says when something was cut.
- It can't read the screen, tap or type. One screen takes more than Gemma's whole context, and small models drive screens poorly.
- Once a reply has read your content from the phone, it can't act in that reply. Text from the phone might be someone else's words, so a message can't make a companion open an app or set an alarm. Ask again in a new message.
- With Gemini Nano, opening an app ends the reply, since Nano only answers while Nano Chat is on screen.
- Turning off Use this phone stops a reply that is using it. When no companion has it on, Nano Chat deletes its Handset token.

Handset's own rules apply on top. Its README covers what it records and how it approves each agent.

## Privacy

Chats run on the phone. The app has no account, no analytics and no server of its own. It uses the network for two things: downloading Gemma 4 E4B from Hugging Face, and talking to Handset on the phone itself at `127.0.0.1`. Plain HTTP is allowed only to that address, in `app/src/main/res/xml/network_security_config.xml`. The Handset token is encrypted with a key held in the Android Keystore. The app asks for the camera and microphone the first time you tap that option.

Turning on Use this phone gives a companion whatever you allow in Handset, such as notifications or location. The model reads that text to answer you. It stays on the phone, but it is in the chat from then on.

## Limits

- Gemma 4 E4B runs with a small context window, so it remembers the last few messages and at most four photos. Gemini Nano sees only the newest photo and no audio.
- Gemma's download runs only while the app is open. It resumes where it stopped.
- With Gemma loaded, Android may still close the app in the background on a 12 GB phone. The next start shows Try again on the unanswered message.
- English only. Interface text is in code, not in resources.

## Developer tools

A switch on the profile page, on by default, shows speed under each reply, the exact prompt behind a chat, each companion's model settings, and a stats page with a card per model. The stats page can also force the real or the fake model. [docs/development.md](docs/development.md) covers the adb helpers for scripted tests, and [docs/progress.html](docs/progress.html) is the decision log, with measurements on a real phone.
