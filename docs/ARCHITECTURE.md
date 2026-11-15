# 小满架构

小满是一个跑在 Android 手机上的开源私人助手：你在它的 App 里聊天，它替你操作手机上的其他
App。框架负责“手”（无障碍操作）、“眼”（读屏）、记忆、安全闸门和模型调用；具体场景（打车、外卖、
购票……）由用户以**场景技能**的形式添加。技能是 Markdown/YAML 小文件夹，不需要重新打包 APK。

需要 Android 11（API 30）及以上。iOS 不允许一个 App 操作其他 App，只能由 Mac 通过 WebDriverAgent 操作 iPhone（`ios-hands` + `ios-runner`），见 [ios.md](ios.md)。

## 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `core-api` | Kotlin/JVM | 下文所有接口与数据结构。其他模块都依赖它，且只依赖它 |
| `core-llm` | Kotlin/JVM | `OpenAiCompatibleClient`：对话补全、图片输入、工具调用、JSON 模式、重试 |
| `core-memory` | Kotlin/JVM | `FileMemoryStore`：磁盘上的 Basic Memory Markdown 笔记，加上支持中文的检索索引 |
| `core-skills` | Kotlin/JVM | `DirectorySkillRepository`：加载并校验 `SKILL.md` 和 `flow.yaml` |
| `core-sentinel` | Kotlin/JVM | `PolicySentinel`：允许 / 询问 / 拒绝，任务级授权，审计 |
| `core-agent` | Kotlin/JVM | `XiaomanAgent`：聊天编排、操作循环、技能流程、经验笔记 |
| `hands` | Android 库 | `XiaomanAccessibilityService`、`AccessibilityDevice`、状态悬浮窗 |
| `app` | Android 应用 | 聊天界面、设置、技能与记忆页面、授权确认、调试桥、轨迹 |
| `core-mcp` | Kotlin/JVM | `McpToolProvider`：把 MCP 服务器（高德、滴滴等）的工具交给聊天模型，带确认策略与密钥脱敏，见 [mcp.md](mcp.md) |
| `web-server` | Kotlin/JVM 应用 | 网页版：同源移动端网页 + `/api/*`（SSE 聊天、确认、MCP 目录与测试），每个会话一个不操作手机的 `XiaomanAgent`，见 [web.md](web.md) |
| `ios-hands` | Kotlin/JVM | `WdaDevice`：通过 iPhone 上的 WebDriverAgent 实现 `Device`（截图、界面树、点击、输入、返回） |
| `ios-runner` | Kotlin/JVM 命令行 | Mac 上的 `ios-runner`：把 `core-*` 与 `WdaDevice` 组装起来，终端确认、轨迹、提醒 |
| `cli/` | Python 3（仅标准库） | 面向技能作者的开发工具：通过 adb 做校验、推送、运行、日志、轨迹 |

五个 `core-*` JVM 模块不接触任何 Android API，可以在任意机器上跑单元测试。依赖方向：

```
app ──► core-agent / core-llm / core-memory / core-skills / core-sentinel ──► core-api
 └────► hands ─────────────────────────────────────────────────────────────► core-api
```

`app/AppGraph.kt` 是手写的依赖容器，把各模块的实现组装成 `XiaomanAgent`。

### core-agent 内部文件

| 文件 | 内容 |
|---|---|
| `XiaomanAgent.kt` | `Agent` 实现：一轮聊天（`handle`）、一次手机任务（`runPhoneTask`）、成功后的经验沉淀 |
| `AgentTools.kt` | 提供给聊天模型的工具定义（名称与 JSON Schema） |
| `ChatTools.kt` | 执行聊天模型发起的工具调用 |
| `TaskRun.kt` | 一次手机任务的运行状态，以及操作循环与流程共用的结果类型 |
| `OperatorLoop.kt` | 操作循环：观察 → 请求操作模型 → 解析 → Sentinel → 执行 → 卡死检测 |
| `ScreenText.kt` | 屏幕和操作的文字表示，用于提示词、历史记录和经验笔记 |
| `FlowRunner.kt` | 按顺序执行技能的 `flow.yaml` 步骤 |
| `OperatorReplyParser.kt` | 宽松解析操作模型的回复 |
| `Prompts.kt` | 所有面向模型的中文文本，集中管理便于调优 |
| `ReminderTimes.kt` | `reminder_set` 工具的本地 ISO-8601 时间解析与当前时间格式化 |
| `JsonText.kt`、`NoteText.kt` | JSON 读取与笔记渲染的小工具 |

## 一轮聊天

```
用户输入 ──► XiaomanAgent.handle
               │  系统提示词 = 人设 + 技能索引（name: description）+ 记忆检索命中
               ▼
           聊天模型（角色 CHAT），可用工具：
               memory_search / memory_read / memory_write / memory_forget / list_skills / reminder_set / phone_task
               skill_read / skill_save / skill_delete（配置了 UserSkillStore 时）
               │
               └─ phone_task(goal, skill?) ──► 操作循环（见下）──► 结果摘要返回给聊天模型
```

## 操作循环

```
for step in 0 until maxSteps:
    用户要求停止？                -> finish(false, "stopped")
    screen = device.observe()
    screen.secure？               -> ask_user(takeover = true)
    prompt = 目标 + 技能说明 + 技能引用的记忆 + "howto" 记忆命中
           + 最近 historyWindow 步的文字记录 + 精简节点列表（≤ maxNodes）+ 截图
    reply  = 操作模型（角色 OPERATOR，jsonMode）
    action = parse(reply)        # 宽松解析：代码块包裹的 JSON、多余字段、nodeId 或坐标
    finish / ask_user            -> 由循环处理，不经过设备
    sentinel.authorize(ctx, action) == false -> 发出 StepBlocked 事件，下一步告知模型
    result = device.perform(action)
    连续 stuckRepeats 次屏幕不变  -> 提示模型已卡住；再次卡住则失败
```

操作模型的回复格式（只有一个 JSON 对象）：

```json
{"thought": "目的地输入框在顶部", "action": {"type": "tap", "nodeId": 12, "target": "目的地输入框", "reason": "先输入目的地"}}
```

`action` 是 `core-api` 中的 `Action`，以 `type` 区分：`tap`、`long_press`、`swipe`、`type`、`back`、
`home`、`launch`、`deeplink`、`wait`、`ask_user`、`finish`。坐标归一化到 0..1000。点击可以指定
`nodeId`（节点列表中有该元素时优先使用）或 `point`。

开启 `learnFromSuccess` 时，任务成功后 Agent 会写入或更新笔记 `howto/<技能名或包名>`（类型 `howto`），
内容如 `- [path] 打开高德 → 打车 → 输入目的地 → 快车 → 停在确认呼叫 #taxi-home`。每次任务开始前，
Agent 会按目标检索 `howto` 笔记，把命中结果放进操作提示词。

## 技能

技能是一个文件夹：`SKILL.md`（frontmatter + Markdown 说明）和可选的 `flow.yaml`。格式定义见
`core-api/src/main/kotlin/dev/xiaoman/api/Skills.kt` 和 [`docs/skills.md`](skills.md)，示例在
`skills/examples/`。

手机上的技能目录：`<external files>/skills/<name>/`，其中 `<external files>` 为
`/sdcard/Android/data/dev.xiaoman.app/files`。App 也会把示例技能作为 assets 一起打包。

`skill_save` 只写用户技能目录，先写临时文件再 rename，不会改动同名内置技能和已有的 `flow.yaml`。
保存前会把完整 `SKILL.md` 草稿发到聊天里，只有用户回复「确认 / 是 / 好 / 可以 / y / yes」才落盘；
`skill_delete` 只能删除用户技能。实现见 `core-skills/UserSkillStore.kt` 和
`core-agent/ChatTools.kt`，UI 入口在「技能」页的「新建技能 / 用一句话修改」。

## 会话、快捷指令与模型自检（Muse UI 二次开发）

| 内容 | 文件 | 手机位置 / 说明 |
|---|---|---|
| 会话历史 | `app/session/ChatHistoryStore.kt` | `<external files>/chat/history.json`，默认 500 条，可在设置中调为 50–2000；原子写入；「清空会话」只删这个文件 |
| 任务子对话 | `app/session/TaskThreadStore.kt` | `<external files>/chat/tasks.json`；每个手机任务一个线程，最多 100 个任务、每任务 200 条记录；主对话只保留 `ChatItem.TaskEntry` 卡片 |
| 小满日记 | `app/diary/DiaryStore.kt` | `<external files>/diary/diary.json`；任务完成/失败与记忆更新写入，最多 500 条，按天分页 |
| 全局 Dock | `app/ui/ChatDock.kt` | 输入框 + 导航合并为一张近不透明卡片，外圈透明；所有页签共用草稿；附件、语音、发送/停止都在这张卡里 |
| 能力页 | `app/ui/CapabilitiesScreen.kt` | 「技能 / 服务」分段切换；复用 `SkillsScreen` 与 `McpScreen`，隐藏重复标题 |
| 任务历史 | `app/ui/TaskHistoryScreen.kt` | 全部历史任务按时间倒序；全部/进行中/已完成/未完成筛选；点击进入任务详情 |
| 搜索 | `app/ui/SearchScreen.kt` | 右上菜单进入；搜索主对话与任务线程，任务命中可直接打开详情 |
| 任务详情 | `app/ui/TaskDetailScreen.kt` | 目标、状态、步数与处理过程（默认展开）；返回来源页（主对话或任务列表） |
| 常用指令 | `app/quick/QuickCommandStore.kt` | `<external files>/chat/quick-commands.json`，最多 8 条，默认六条由多语言资源生成 |
| 模型自检 | `core-llm/ModelProbe.kt` | 设置页「测试连接」发一条最小请求；操作模型会附带 1×1 PNG，验证图片输入 |
| 模型目录 | `core-llm/ModelCatalogClient.kt` | 调厂商 OpenAI 兼容的 `GET {baseUrl}/models`；解析 `data[]`/`models[]` 与上下文/最大输出/图片模态元数据，401/404/429 交给 UI 诊断 |
| 服务商预设 | `app/settings/ModelCatalog.kt` | 火山方舟/OpenAI/DeepSeek/百炼/智谱/Kimi/硅基流动/OpenRouter/自定义；`ModelDefaults` 只在厂商未返回元数据时估算 |
| 语音输入 | `app/ui/VoiceInput.kt` | 系统 `SpeechRecognizer`；只接受最终识别结果，权限被拒绝时给出可读提示 |
| 应用语言 | `app/AppLocale.kt` | 读普通设置 `appearance.language`，`MainActivity.attachBaseContext` 覆盖资源；切换后重建 Activity |
| 视觉主题 | `app/ui/Theme.kt` | Open Muse 风格：近白纸面、灰色气泡、蓝色强调、22dp 圆角、系统无衬线字体 |

### 模型目录与设置持久化

- `ModelProfile` 是用户可编辑的模型条目：`id / displayName / maxTokens / temperature /
  contextWindow / supportsVision / chatCapable`，以 JSON 存在 `SharedPreferences` 的
  `provider.models`；`provider.id` 记录上次选择的服务商。
- `chat`、`operator` 仍是运行时真正使用的两个 `ModelSettings`；保存时把 `ModelProfile` 的
  配置写进对应的 `ModelSettings`，因此 `core-agent` 和 `core-llm` 不需要知道目录的存在。
- 服务商切换会清空旧模型列表并填入该厂商的 Base URL；Key 只写 `EncryptedSharedPreferences`，
  切换服务商时同一个 Key 同时用于聊天和操作模型。
- 旧版本升级时如果没有 `provider.models`，会用现有的 chat/operator 两个模型生成初始目录；
  如果用户主动把目录保存为空，则保持为空，不自动回填。
- 拉取失败时：401/403 提示 Key 或开通问题；404 提示该厂商没有 `/models`，引导手动添加模型 ID；
  429 提示额度；网络错误提示检查网络。手动添加的模型走同一套 `ModelProfile` 和保存逻辑。
- 厂商若返回 `context_length`/`context_window`/`max_context_length`、
  `max_completion_tokens`/`max_output_tokens`、`architecture.input_modalities` 或
  `supports_vision`/`capabilities.vision`，`ModelDefaults.profile(RemoteModel)` 优先采用；
  缺失时才回退到模型族估算（未知族为 8192 / 32768 / 0.7）。
- 展开模型行编辑已选中的聊天/操作模型时，`updateProfile` 会把改动同步回写 `form.chat` /
  `form.operator`，避免「看起来改了、保存后没生效」。
- 设置页其他项：主题 `appearance.theme`（system/light/dark，选择即保存）、语言
  `appearance.language`（system/zh/en，选择即保存并重建 Activity）、语音识别后自动发送
  `chat.voiceAutoSend`、通知开关 `notifications.enabled`、请求超时 `model.requestTimeout`
  （10–300 秒，写进两个 `ModelConfig`）、会话保留条数 `chat.historyLimit`（50–2000）。
- 会话初始恢复使用固定 2000 条上限，不在 `Application.onCreate` 阶段读加密设置；动态上限在
  每次追加/保存时裁剪，避免 Robolectric/恢复场景触发未就绪的 `AndroidKeyStore`。
- 手机任务事件按 `taskId` 路由：`PhoneTaskStarted` 创建线程并向主对话加入 `TaskEntry`，
  `Step*`/`NeedsUser`/`Failed` 只写线程，`PhoneTaskFinished` 完成后把同一张任务卡更新为完成/失败。
  `SkillChosen` 早于 `PhoneTaskStarted`，因此不单独写入主对话。
- 附件链路：`ChatDock` 读图/拍照 → 最长边 1280px PNG → `AgentSession.submit(images)` →
  `Agent.handle(..., images)` → `ChatMessage.user(text, images)` → `ContentPart.ImagePng`。
  文本文件在客户端读取（≤20KB）后内联进消息；历史只保存 `ChatItem.User.imageCount`。

新增界面文案全部走 `app/src/main/res/values/strings.xml`，英文翻译在
`values-en/strings.xml`；新增文案时必须同时补两边，避免中文写死在 Compose 代码里。
当前两边各 350 个 key。

真机验收：在聊天页输入一句话 → 杀进程重开，历史仍在；新建一条常用指令 → 重开仍在；
点麦克风授权后说一句话 → 文字进入输入框；设置页分别测试聊天/操作模型 → 成功显示延迟，失败显示
401/404/429/网络诊断；技能页「新建技能」→ 聊天页自动填入作者提示词。

## 记忆

笔记是 `<external files>/memory/` 下的 Markdown 文件，采用 Basic Memory 格式：frontmatter 包含
`title/type/permalink/tags`；`## Observations` 下每行为 `- [category] content #tag (context)`；
`## Relations` 下每行为 `- relation [[Target]]`。整个目录可以直接拷进 Obsidian 仓库。「忘记」会删除文件。
小满自行实现这一格式，没有内嵌 Basic Memory（AGPL-3.0）的代码。

## 安全（Sentinel）

每个操作都在模型之外评估：

1. `deny`（拒绝）：向密码框输入；在受保护（FLAG_SECURE）页面上操作；技能声明了 `apps` 时操作清单之外的
   包（桌面启动器和小满自身除外）。
2. `ask`（询问）：`tap` / `long_press` / `type` 的目标文字或被操作节点的文字包含确认词。确认词包括内置列表
   （支付、付款、确认支付、立即支付、转账、发送、删除、下单、提交订单、确认呼叫、呼叫、购买、
   pay、send、delete、purchase、order）以及技能的 `confirm_before`。在技能内，`deeplink` 与技能的
   `deeplinks` 模板都不匹配（scheme 和 host 都相同才算匹配）时也要询问。
3. `allow`（允许）：其他所有操作。

授权分为“仅一次”（ONCE）和“本次任务”（TASK）两种范围。每一次决策都写入审计记录。

## 调试桥（仅 debug 包）

App 注册了 `dev.xiaoman.app.debug.DebugReceiver`（只在 debug 清单中导出）：

| 广播 action | 参数 | 作用 |
|---|---|---|
| `dev.xiaoman.debug.RUN` | `goal`（字符串）、`skill`（可选）、`run_id`（可选） | 在前台服务中执行 `Agent.runPhoneTask` |
| `dev.xiaoman.debug.STOP` | 无 | 给正在运行的任务发送停止信号 |
| `dev.xiaoman.debug.RELOAD_SKILLS` | 无 | 调用 `SkillRepository.reload()` 并记录加载问题 |

示例：`adb shell am broadcast -n dev.xiaoman.app/.debug.DebugReceiver -a dev.xiaoman.debug.RUN --es goal "打开设置" --es run_id r1`

日志：logcat 标签 `小满`，每个事件一行：`小满: <run_id> <EventKind> <json>`。

轨迹：`<external files>/traces/<run_id>/trace.jsonl`，每行一个 JSON 对象：

```json
{"time": 1761830000000, "run": "r1", "kind": "StepPlanned", "step": 3, "action": {"type": "tap", "nodeId": 12}, "text": null, "ok": null, "decision": null}
```

`kind` 是 `AgentEvent` 的类名。`text` 存放 Reply / NeedsUser / finish 的摘要和失败信息；`ok` 存放
`ActionResult.ok` 或 `success`；`decision` 为 `allow|ask|deny: <原因>`。每一步的截图保存在同目录下的
`step-<n>.png`。

## 模型

任意兼容 OpenAI 接口的服务都可以。需要两组设置：聊天模型和操作（读屏）模型，后者必须支持图片输入。
以火山方舟为例：Base URL 为 `https://ark.cn-beijing.volces.com/api/v3`；开发机上 Key 放在环境变量
`ARK_API_KEY` 中，手机上则填写到「设置」里。API Key 不会写入日志、轨迹或记忆。

## 构建

`scripts/gradle.sh <tasks>` 会把构建产物放到仓库目录之外。需要 JDK 17 和 Android SDK 35。
