# Sarvam Voice Assistant (Android)

A trilingual (Gujarati · Hindi · English) push-to-talk voice assistant, built on Sarvam AI's
REST APIs. Native Kotlin, Jetpack Compose, Material 3.

```
🎤 Mic ─▶ saaras:v3 (STT) ─▶ chat LLM (auto-detected) ─▶ bulbul:v3 (TTS) ─▶ 🔊 Speaker
```

The assistant detects which language you spoke and replies in that same language, out loud.

## Requirements

- Android Studio (Ladybug or newer) with the Android SDK, **API 35** platform installed
- JDK 17 (bundled with Android Studio)
- A device or emulator running **Android 8.0 (API 26)** or newer
- A Sarvam API key from [dashboard.sarvam.ai](https://dashboard.sarvam.ai)

## Running it

1. Open the `SarvamVoiceAssistant` folder in Android Studio (`File → Open`).
   Let Gradle sync — it downloads the Android Gradle Plugin and dependencies on first run.
2. Press **Run** ▶ with a device connected (USB debugging enabled) or an emulator started.
3. On first launch the app opens **Settings** automatically. Paste your API key and tap **Save**.
4. Grant the microphone permission when prompted.
5. Tap the mic, speak, tap again to stop. The reply appears as text and is spoken aloud.

There is no code to edit and no key to hardcode — the key is entered in the app and stored
in `EncryptedSharedPreferences`, backed by the Android Keystore.

## Building an APK

Locally:

```bash
cd SarvamVoiceAssistant
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Or let CI do it: the `Build Android APK` GitHub Actions workflow builds on every push and
uploads `sarvam-voice-debug-apk` as a downloadable artifact. Open the workflow run, download
the artifact, unzip, and sideload the APK (enable "Install unknown apps" on your phone).

## Using it

| Control | What it does |
|---|---|
| Mic button | Tap to record, tap again to stop and send. Auto-stops at 25 s. |
| Language chips | `Auto` lets the model detect the language. Pick one to force it. |
| Text field | Type instead of speaking; the reply is still spoken aloud. |
| Settings | API key and voice selection. |
| Trash icon | Clears the conversation and the model's memory of it. |

## How it is put together

| File | Responsibility |
|---|---|
| `SarvamClient.kt` | The three REST calls, error mapping, bounded conversation history |
| `AudioRecorder.kt` | 16 kHz mono WAV capture, off the main thread, with a live level meter |
| `AudioPlayer.kt` | Plays the reply and suspends until playback genuinely finishes |
| `ApiKeyStore.kt` | Encrypted key storage, with a plain-preferences fallback |
| `ChatViewModel.kt` | Owns the STT → LLM → TTS pipeline and all UI state |
| `ui/ChatScreen.kt` | Compose chat UI, mic button, input bar |
| `ui/SettingsDialog.kt` | API key entry and voice picker |
| `Voices.kt` | The valid `bulbul:v3` speaker list and per-language defaults |

## Customising

- **Voice** — pick any of the 39 `bulbul:v3` voices in Settings. Voices are not
  language-locked; any voice can speak any supported language. Names are case-sensitive and
  lowercase, and a name outside `Voices.ALL` is rejected by the API with an HTTP 400.
- **Personality** — edit `SYSTEM_PROMPT` in `SarvamClient.kt`.
- **Chat model** — not hardcoded. The app queries `GET /v1/models` and picks one your key
  supports; pin a specific model in Settings if you prefer. Sarvam has retired chat models
  repeatedly (`sarvam-m`, then `sarvam-30b`), so anything pinned in code goes stale — see
  "Model selection" below.
- **Recording limit** — `MAX_RECORD_MS` in `ChatViewModel.kt`. The speech-to-text endpoint
  accepts at most 30 seconds of audio per request, so keep it under that.
- **Conversation memory** — `HISTORY_TURNS` in `SarvamClient.kt`.

## Looking things up

A language model only knows what it was trained on, so out of the box it cannot tell you
what happened last week — and will happily invent an answer. Two things address that:

- **The date is injected** into the system prompt on every turn, so it always knows what
  today is rather than guessing from training data.
- **Web search is offered as a tool.** The model calls it only when it decides it needs
  current information, using DuckDuckGo's Instant Answer API and Wikipedia — both keyless,
  so nothing extra needs configuring. The status line shows what it is searching for.

Turn it off in **Settings → Look things up** if you would rather have faster answers from
the model's own knowledge.

What this does *not* give you is a news feed. Wikipedia and DuckDuckGo are strong on
established facts and reasonably current on notable events, but they will not have this
morning's headlines, live scores or market prices. For those you would need a dedicated
provider per category, each with its own API key.

Search failures never break a turn — the model is told the lookup failed and answers anyway.

## Offline dictionary

Ask what a word means and the answer comes from a real dictionary on the device, not from
the model's memory. Asking a language model for a definition invites invention — it will
produce a confident meaning for a word that does not exist. A dictionary either has the
word or it does not, and *"not in the dictionary"* is a correct answer.

- **Princeton WordNet 3.1**: 147k words, 207k senses, definitions **and** synonyms, so it
  is a thesaurus too.
- **Fully offline.** Ships gzipped (~12 MB) in assets, expanded once on first use to
  ~30 MB in app storage. No network, no API key, no per-lookup cost.
- **Handles spoken forms**: `mice → mouse`, `ran → run`, `happiest → happy`. Irregular
  forms come from WordNet's own exception lists rather than guesswork, and carry their part
  of speech so "ran" leads with the verb rather than the baseball noun.
- Senses are returned in WordNet's frequency order, capped at three so a spoken answer
  stays short.

The model is instructed to read the definition as written and then explain it briefly in
your language, so you always hear the real wording first.

To regenerate the database (the committed asset is already built):

```bash
python3 tools/build_dictionary.py
```

WordNet is English only. Hindi and Gujarati word meanings would need a different source —
IndoWordNet is research-licensed and Wiktionary extracts are much rougher — so that is not
included rather than shipped in a state that would disappoint.

The WordNet licence requires its notice to travel with the data; it is in
`app/src/main/assets/WORDNET_LICENSE.txt`.

## Model selection

Sarvam retires chat models fairly often, and every retirement breaks clients that pin a
model name in code. This app does not pin one:

1. On startup (and whenever Settings opens) it calls `GET /v1/models` and keeps the chat
   models your key can actually use.
2. It prefers `sarvam-105b`, then `sarvam-30b`, then anything else the API offers — so a
   future model still works with no code change.
3. If a chat request is *still* rejected for the model, it reads the replacement name out
   of the error message, refreshes the list, and retries once automatically.
4. `FALLBACK_CHAT_MODEL` in `SarvamClient.kt` is used only if `/v1/models` is unreachable.

You can pin a specific model in **Settings → Chat model**. Leave it on **Automatic** unless
you have a reason not to — a pinned model is exactly what breaks when Sarvam retires it.

## Limitations

- Push-to-talk only. Interrupting the assistant mid-sentence (barge-in) needs Sarvam's
  streaming WebSocket APIs, which this app does not use.
- One request per turn, so there is a pause between speaking and hearing the reply.
- The debug APK is signed with the debug keystore — fine for sideloading, not for Play.
