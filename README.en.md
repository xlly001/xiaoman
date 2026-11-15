# Xiaoman

[中文](README.md)

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
- **Voice input**: the dock microphone uses the system speech recognizer; recording permission and
  recognizer failures produce readable messages.
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
- **Settings entry**: the gear at the top-left of the chat opens Settings. It covers theme (system/light/
  dark), language (system/Simplified Chinese/English), automatic voice send, notification toggle,
  request timeout, conversation cap, accessibility, clear conversation/traces and version information.
- **Skill and memory management**: search, details, create and one-sentence edit on the Skills screen;
  `skill_read` / `skill_save` / `skill_delete` write user skills only after an explicit chat confirmation.
  The Memory screen can create, edit and delete notes.
- **Internationalization**: every new UI string lives in resources, with Chinese in
  `app/src/main/res/values/strings.xml` and English in `values-en/strings.xml`; both files currently
  hold 351 matching keys, and the app language can be switched in Settings.

The roadmap toward a SuperAgent (task queue, checkpoints, layered memory, skill ecosystem and
multi-agent execution) is in [docs/superagent-plan.md](docs/superagent-plan.md) (Chinese).

### Build and test (Windows)

```powershell
$env:JAVA_HOME = 'D:\Android\jdk17'   # JDK 17
$env:ANDROID_HOME = 'D:\Android\sdk'  # Android SDK 35
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core-llm:test :core-agent:test :core-skills:test :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug manifest allows cleartext
HTTP for local vLLM/Ollama endpoints (e.g. `http://127.0.0.1:11434/v1`); release keeps HTTPS only.

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
- Xiaoman diary: `/sdcard/Android/data/dev.xiaoman.app/files/diary/diary.json`
- quick commands: `/sdcard/Android/data/dev.xiaoman.app/files/chat/quick-commands.json`

They can be backed up or deleted; the app then starts with an empty history or the default shortcuts.

## License

MIT, see [LICENSE](LICENSE).
