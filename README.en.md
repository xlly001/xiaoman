# Xiaoman

[中文](README.md)

> This project is a derivative work based on [aduoge/xiaoman](https://github.com/aduoge/xiaoman),
> distributed under the same MIT license. The original work is copyright aduoge; modifications and
> additions in this repository are copyright xllyxx and contributors. See [LICENSE](LICENSE).

An open-source personal agent that lives on an Android phone. You chat with it in its app; it
remembers what you tell it and operates the other apps on the phone for you (打车、点外卖、改设置 ...).
New scenarios are **skills**: small `SKILL.md` + `flow.yaml` folders you can push without rebuilding
the APK.

- Android 11 (API 30) or newer. It uses an accessibility service to read the screen and act.
- iOS cannot run it on the phone alone (one app may not operate other apps). You can try it from a Mac
  that drives the iPhone through WebDriverAgent; see [docs/ios.md](docs/ios.md) (Chinese).
- MCP servers (Amap, Tencent Maps, DiDi, 12306 ...) can serve lookups and bookings without operating
  the phone; see [docs/mcp.md](docs/mcp.md) (Chinese).
- A web version (add to the iPhone home screen) offers chat and one-tap MCP services without an app;
  see [docs/web.md](docs/web.md) (Chinese).
- Models: any OpenAI-compatible endpoint (Volcano Ark, Bailian, OpenRouter, vLLM, Ollama ...). You
  configure a chat model and a screen ("operator") model that accepts images.

## Safety

Every action passes the Sentinel policy before it reaches the phone, outside the model's control:
typing into password fields and acting on secure (FLAG_SECURE) screens are denied; taps on things
like 支付 / 发送 / 删除 / 确认呼叫 need your approval; a skill may only operate the apps it lists.
Skills can add confirmation words but never remove the built-in ones. Every decision is audited.

## Build

JDK 17 and Android SDK 35.

```
scripts/gradle.sh --online test :app:assembleDebug   # first run fetches dependencies
scripts/gradle.sh test :app:assembleDebug            # later runs work offline
```

`scripts/gradle.sh` wraps `./gradlew` and keeps build output outside the checkout; see the script
header for its environment variables.

### Release build

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk` and is also copied to
`dist/xiaoman-muse-<versionName>-release.apk` for distribution. The release variant keeps code
unobfuscated (full stack traces for field issues) and only strips unused resources; the debug-only
command receiver is not packaged.

Signing material never enters the repository. Put a `keystore.properties` at the repo root
(both it and `/keystore/` are git-ignored):

```properties
storeFile=keystore/xiaoman-release.jks
storePassword=********
keyAlias=xiaoman
keyPassword=********
```

Without that file the release variant has no signing config and produces an unsigned, uninstallable
APK. Signing is v2 + v3 with v1 disabled (`minSdk 30`, so JAR signing buys nothing and v3 keeps key
rotation possible). **Losing the keystore means no future update can reuse this package name** — back
up `keystore/xiaoman-release.jks` and its passwords separately. Uninstall the old app before
installing a build signed with a different key, otherwise the install fails with a signature conflict.

## Use

1. Install the debug APK and open Xiaoman.
2. Settings: enter base URL, model and API key for the chat and operator models.
3. Turn on the Xiaoman accessibility service when the app asks.
4. Chat: "帮我打车回家", "记住我家在 ...", "把 Wi-Fi 打开".

## Secondary development: Open Muse UI and everyday conveniences

This fork builds on the main course version with an Open Muse inspired interface plus the soft blue
wash, white rounded cards and one-piece bottom dock of the reference app:

- **Global dock**: the composer and navigation are one floating card, available on every tab. With the
  keyboard open only the input row remains; the margins around the card stay transparent and page
  content scrolls behind it.
- **Attachments**: the plus button offers camera, gallery and text files. Images are sent to the model
  as image parts, text files are inlined, the send button is hidden while empty, and voice stays on the
  right.
- **Persistent conversations**: chat rows are stored in `chat/history.json` and survive a restart;
  the chat menu can clear them without touching memory or skills. The default cap is 500 rows and can
  be changed to 50–2000 in Settings.
- **Task sub-conversations**: every phone task gets its own thread in `chat/tasks.json`; the main chat
  only keeps one task card (running/done/failed) under the message. The task detail is a real
  conversation: the dock composer sends into that thread and Xiaoman's reply stays there.
- **Search**: the top-right menu searches the main conversation and every task thread; task hits open
  the thread directly.
- **Xiaoman diary**: the “Xiaoman” tab shows one horizontally swipeable card per day, fed by finished
  tasks and memory updates (`diary/diary.json`).
- **Quick commands**: six default cards (notebook, water reminder, preference, parcel tracking,
  daily summary, translate) and up to eight editable shortcuts; tapping one fills the dock composer.
- **Voice input**: the dock microphone goes through the built-in ASR engine; recording permission and
  recognizer failures produce readable messages.
- **Built-in TTS / ASR / OCR**: all three are pluggable engines (`core-api/Media.kt`). **With a DeepSeek
  model they default to the official `/v1/tools/{asr,tts,ocr}` endpoints**, derived from the chat base
  URL and sharing the same API key; other providers fall back to the platform engines
  (`SpeechRecognizer`, Android `TextToSpeech`, vision-model OCR). Settings can also pin “DeepSeek” or
  “System”. The chat dock offers microphone recording, an “Extract text from image” attachment action
  that pastes the result into the composer instead of sending it, and a “Read replies aloud” toggle that
  speaks the final reply once (never the streaming deltas). Voices are `stella` / `tide`; OCR can run in
  plain-text or rich-layout mode.
- **Human-friendly model setup**: pick a provider (Volcano Ark, OpenAI, DeepSeek, Alibaba Bailian,
  Zhipu, Kimi, SiliconFlow, OpenRouter or custom), enter one key, and tap “Fetch available models” to
  read the provider’s `/models`. Provider metadata (context window, max output, image modality) is used
  first, with model-family estimates only as a fallback. Each model expands to display name, max output,
  context window, temperature and vision support; editing the selected model updates the real chat or
  operator config, and saving persists the whole list.
- **Model self-check**: Settings can probe the chat and operator models separately. The operator probe
  includes a 1×1 PNG so a text-only model cannot pass; 401, 404, 429 and network failures get specific
  guidance.
- **Capabilities page**: Skills and Services now share one page with a segmented switch. Skills keeps search, create, edit and delete; Services keeps MCP selection, keys, testing and custom servers.
- **Task history**: the Tasks tab lists every past task newest first, with All/Running/Done/Failed filters; tapping a card opens its detail.
- **Task queue**: “New task” at the top of the Tasks tab queues a goal. One task runs at a time; when it
  finishes the next one starts automatically (higher priority first, then first in, first out). Queued
  rows can be paused, resumed or cancelled, and a running task can be stopped from its detail page. The
  queue lives in `chat/queue.json` and survives a restart.
- **Checkpoint recovery**: every executed step writes a checkpoint (completed steps plus the last 12 step
  lines). After the process is killed, a queued task with a checkpoint continues from step N: the
  operator looks at the current screen again before choosing the next action. Tasks without a checkpoint
  are marked “interrupted by app restart”. Skill flows (`flow.yaml`) are not resumed yet and fail with a
  clear reason instead of replaying actions that may already have taken effect.
- **Settings entry**: the gear at the top-left of the chat opens Settings. It covers theme (system/light/
  dark), language (system/Simplified Chinese/English), automatic voice send, notification toggle,
  request timeout, conversation cap, accessibility, clear conversation/traces and version information.
- **Skill and memory management**: search, details, create and one-sentence edit on the Skills screen;
  `skill_read` / `skill_save` / `skill_delete` write user skills only after an explicit chat confirmation.
  The Memory screen can create, edit and delete notes.
- **Internationalization**: every new UI string lives in resources, with Chinese in
  `app/src/main/res/values/strings.xml` and English in `values-en/strings.xml`; both files currently
  hold 388 matching keys, and the app language can be switched in Settings.
- **Streaming replies**: chat replies stream over SSE (OpenAI-compatible `/chat/completions` `stream`);
  text appears incrementally with auto-scroll and can be stopped mid-flight. Tool-call fragments are
  accumulated per index into complete calls. The draft bubble updates in memory only and is not written to
  disk; when the final reply arrives it replaces the bubble under the same id and is persisted, so exactly
  one reply bubble remains.
- **Evaluation harness**: a new pure-JVM `eval/` module drives the real agent through a scenario DSL
  with scripted fake LLM / device / memory and a fixed clock, asserting event sequences, approval
  requests and side effects. Fifteen built-in scenarios cover chat, memory, skill confirmation, tasks
  and high-risk blocking (pay / send / delete / password field / prompt injection). `./gradlew :eval:run`
  writes a text and JSON report and exits with code 1 on any failure, so CI can use it as a release gate;
  no key, network or Android device is needed.

The roadmap toward a SuperAgent (task queue, checkpoints, layered memory, skill ecosystem and
multi-agent execution) is in [docs/superagent-plan.md](docs/superagent-plan.md) (Chinese).
New contributors and agents should start with the [handover document](docs/HANDOVER.md) (Chinese).

### Build and test (Windows)

```powershell
$env:JAVA_HOME = 'D:\Android\jdk17'   # JDK 17
$env:ANDROID_HOME = 'D:\Android\sdk'  # Android SDK 35
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core-llm:test :core-agent:test :core-skills:test :app:testDebugUnitTest :eval:test
.\gradlew.bat :eval:run   # 15 eval scenarios, report under eval/build/reports/eval/
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug manifest allows cleartext
HTTP for local vLLM/Ollama endpoints (e.g. `http://127.0.0.1:11434/v1`); release keeps HTTPS only.

**JDK 17 is required**: Java 21+ (reproduced on 25.0.1) makes Kotlin 2.1's `JavaVersion.parse`
throw `IllegalArgumentException: 25.0.1` before compilation even starts.

## Emulator end-to-end checks (no real API key needed)

`scripts/e2e/` is a stdlib-only Python toolkit. With the `xiaoman35` AVD (API 35) it exercises
streaming chat, the task queue and checkpoint resume without any real model.

```bash
python scripts/e2e/fake_llm_server.py --port 8765 --log-file build/e2e/requests.jsonl
python scripts/e2e/setup_model.py          # inject http://10.0.2.2:8765/v1 and two fake models
python scripts/e2e/adb_ui.py a11y --on     # bind the accessibility service
python scripts/e2e/adb_ui.py dump          # locate Compose nodes by text / content-desc
```

- `fake_llm_server.py`: `/v1/models`, `/v1/chat/completions` (SSE or JSON), `/control`,
  `/control/reset`, `/log`, `/health`. `response_delay_ms` applies to **every** request,
  including non-streaming operator calls, so queued / paused / resumed states stay observable.
  API keys are redacted in the request log.
- `adb_ui.py`: `dump / tap / type / key / wait / shot / log / a11y`. **`uiautomator dump` resets the
  accessibility secure settings to 0/null and unbinds Xiaoman's service**; the helper re-enables it
  and waits for the system to rebind whenever it was enabled before the dump.
- Debug broadcasts (debug builds only): `dev.xiaoman.debug.ENQUEUE` (`--es goal`),
  `QUEUE_PAUSE` / `QUEUE_RESUME` / `QUEUE_CANCEL` (`--es id`), `QUEUE_STATUS`. Queue state can also
  be read directly from `chat/queue.json` and `chat/tasks.json`.

### Checking the built-in TTS / ASR / OCR

```bash
python scripts/e2e/setup_media.py --profile model    # also provider / system / forced
python scripts/e2e/verify_media.py                   # routing + three endpoints + parsing
```

- `setup_media.py` flips four routing profiles via `settings.xml`: `model` (`chat.model=deepseek-*`),
  `provider` (`provider.id=deepseek`), `system` (platform fallback) and `forced` (official endpoints
  even under a non-DeepSeek provider).
- `verify_media.py` first asserts each profile picks the right engine and endpoint, then triggers
  `MEDIA_ASR/TTS/OCR` for real and checks the request shapes the fake server received (the ASR `file`
  part must be RIFF/WAVE with real audio length, TTS `voice_id`/`format`, OCR `purpose`) plus the
  app-side parse results — 12 assertions in total.
- The fake server's `/v1/tools/{ocr,asr,tts}` mirror the official behaviour: OCR keeps the leading
  keep-alive newlines, ASR returns `request_id/text/duration_ms`, TTS returns a playable WAV.

> Avoid `uiautomator dump` while a task is running: unbinding the service makes a screen-reading
> step fail with "accessibility service not enabled". Prefer broadcasts + files + logcat for
> queue / stop / resume checks.

## Skills and developer CLI

- Format: [docs/skills.md](docs/skills.md); examples in [skills/examples/](skills/examples/).
- [cli/](cli/README.md) (Python 3, stdlib only) lints skills, pushes them over adb, runs tasks on a
  debug build and shows traces:

```
python3 cli/xiaoman.py lint skills/examples/*
python3 cli/xiaoman.py push skills/examples/taxi-home
python3 cli/xiaoman.py run "打开设置里的 Wi-Fi 页面" --skill open-settings-wifi --follow
python3 cli/xiaoman.py traces show <run_id>
```

## Layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): `core-*` are plain Kotlin/JVM modules (contracts,
LLM client, memory, skills, sentinel, agent), `hands` is the accessibility layer, `app` the UI.

## Memory

Notes are Markdown files in the Basic Memory format under
`/sdcard/Android/data/dev.xiaoman.app/files/memory/`, so you can read and edit them in Obsidian.
Forgetting deletes the file.

Secondary development adds these plain JSON files:

- conversation history: `/sdcard/Android/data/dev.xiaoman.app/files/chat/history.json`
- task threads: `/sdcard/Android/data/dev.xiaoman.app/files/chat/tasks.json`
- task queue: `/sdcard/Android/data/dev.xiaoman.app/files/chat/queue.json`
- Xiaoman diary: `/sdcard/Android/data/dev.xiaoman.app/files/diary/diary.json`
- quick commands: `/sdcard/Android/data/dev.xiaoman.app/files/chat/quick-commands.json`

They can be backed up or deleted; the app then starts with an empty history or the default shortcuts.

## License

MIT, see [LICENSE](LICENSE).
