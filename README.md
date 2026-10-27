<img src="assets/icon.svg" width="64" height="64" alt="">

# nano-chat

Created in [T3 Code](https://t3.codes).

An Android app for chatting with AI models that run on the phone. Each companion is one ongoing chat, like a contact, and runs on either Gemini Nano (built into Android) or Gemma 4 E4B (a 3.7 GB download that also understands photos and voice notes). Developer tools (speed, exact prompts, model settings, stats) sit behind one switch on the profile page.

Gemini Nano needs a phone with Google AICore. Gemma 4 E4B needs about 5 GB of free memory, so a 12 GB phone or more. Emulators fall back to a fake model in debug builds.

```bash
./gradlew :app:installDebug      # or :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

## Use this phone

A companion can use the phone through [Handset](https://github.com/triptu/handset#readme), a separate app on the same phone. Install Handset, open a companion's edit sheet, and turn on **Use this phone**. Handset asks once what Nano Chat may read, such as notifications or location. After you tap Allow, every companion you switch on uses that one connection. Nano Chat talks to Handset only on the phone itself, at `127.0.0.1`, and the token is kept with a key held by Android's Keystore.

A companion can then search what you saw on screen and in notifications, catch up on messages, read a day's timeline, check the phone's status, open apps, set alarms and timers, switch Do not disturb, and see or control what's playing. Each step shows as a line under the reply. Tap it to see what came back. If Handset says no because a source is off, the chat tells you what to turn on in Handset.

Limits:
- At most 3 tool calls per reply. Results are cut to fit the model's context, and the reply says when something was cut.
- Screen-driving tools (reading the screen, tapping, typing) aren't offered. One screen takes more than Gemma's whole 2,048-token context, and small models drive screens poorly.
- Gemma 4 E4B calls tools natively through LiteRT-LM. Gemini Nano follows a JSON format in its prompt, with one retry when it gets the format wrong.
- Nano only answers while Nano Chat is on screen, so with Nano, opening an app ends the reply.
- Once a reply has read anything from the phone, it can't act in that reply: no opening apps, alarms, timers, Do not disturb or playback controls. Text from the phone might be someone else's words, so a message can't make a companion act. Ask again in a new message to have it done.
- Turning off Use this phone stops a reply that is using it. When no companion has it on, Nano Chat deletes its Handset token, and turning it on again goes through Handset's connect screen.
- If the model stops after a step worked, the reply keeps that step, and Try again goes on from it instead of doing it twice.

Progress and decisions: [docs/progress.html](docs/progress.html).
