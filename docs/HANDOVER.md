# 小满（Xiaoman）交接文档

> 面向接手者 / WorkBuddy。读完这一篇即可继续开发，不需要回看聊天记录。  
> 项目路径：`f:\gowork\xllyxx\xiaoman`  
> 交接时间：2026-11-15  
> 功能代码基线：`92d4657 perf: fold checkpoints into the step write`（工作区干净，可编译；本交接文档本身另有一次提交）  
> 长期目标：继续开发小满，路线见 [SuperAgent 功能开发计划](superagent-plan.md)。

---

## 0. 一分钟了解

小满是一个跑在 Android 上的私人 Agent：聊天、记忆、技能、MCP，以及通过无障碍服务替用户操作其他 App。

- 技术栈：Kotlin + Jetpack Compose（Material 3），多模块 Gradle，JDK 17，minSdk 30 / targetSdk 35。
- 模块：`core-api`、`core-llm`、`core-memory`、`core-skills`、`core-sentinel`、`core-agent`、`hands`、`core-mcp`、`app`，另有 `cli/`、`web-server/`、`ios-runner/`。
- `core-*` 是纯 JVM，不依赖 Android，因此单元测试可以脱离设备运行。
- 所有重要判断（可恢复性、检查点、队列、安全边界）都在 `docs/superagent-plan.md` 与 `.trae/specs/xiaoman-ui/` 三份 Spec 里。

**接手第一件事**：读 [superagent-plan.md](superagent-plan.md) 的「阶段 0」和 `.trae/specs/xiaoman-ui/spec.md` 的 FR-12 / FR-15 / FR-16。它们定义了当前行为。

---

## 1. 快速开始（Windows）

```powershell
$env:JAVA_HOME    = 'D:\Android\jdk17'
$env:ANDROID_HOME = 'D:\Android\sdk'

cd f:\gowork\xllyxx\xiaoman
.\gradlew.bat :core-llm:test :core-agent:test :core-skills:test :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug --offline
```

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 交付副本：`dist/xiaoman-muse-latest-debug.apk`（`dist/` 未纳入 git，避免把 65 MB 的包提交进去）
- 模拟器（已装好 App）：AVD `xiaoman35`，Android 15 / API 35 / x86_64，1080×2400，WHPX 加速。
- adb：`D:\Android\Sdk\platform-tools\adb.exe`
- 安装：`adb install -r dist\xiaoman-muse-latest-debug.apk`
- 启动：`adb shell monkey -p dev.xiaoman.app -c android.intent.category.LAUNCHER 1`

首次使用需要在小满「设置」里填聊天模型与操作模型（任意 OpenAI 兼容服务）。**仓库里没有任何 Key**，也不要提交 Key。

---

## 2. 当前已经完成什么

### 2.1 界面

| 能力 | 说明 |
|---|---|
| 全局 Dock | 输入框 + 导航合并为一张 96% 不透明卡片，外圈透明；所有页签常驻；键盘打开只留输入行 |
| 聊天 | Open Muse 风格气泡、任务卡、处理过程折叠、附件（拍照/相册/文本）、语音输入、搜索、清空会话 |
| 能力页 | 技能 / 服务分段切换，复用 `SkillsScreen` 与 `McpScreen` |
| 任务页 | 历史任务筛选 + 队列区（暂停/继续/取消）+「新建任务」 |
| 任务详情 | 目标/状态/步数/处理过程；底部 Dock 可直接继续该任务对话；运行中可停止 |
| 小满页 | 按天横向卡片日记 |
| 设置 | 服务商 → Key → 拉取 `/models` → 模型展开配置 → 设为聊天/操作模型；主题、语言即时生效；通知、历史上限、无障碍入口、清空轨迹 |

### 2.2 会话与任务（本阶段重点）

- **任务子对话**：`TaskThreadStore` 存 `chat/tasks.json`；主对话只保留 `ChatItem.TaskEntry` 卡片。
- **任务队列**：`TaskQueueStore` 存 `chat/queue.json`；进程内只有一个执行槽，任务串行执行；优先级 + 先入先出；暂停/继续/取消。
- **检查点**：每步把「已完成步数 + 最近 12 条步骤」写入 `TaskThread.checkpoint`；重启后：
  - 有检查点 → 队列行回到 QUEUED，显示「重启后继续」，续跑时注入 `TaskResume(steps, history)`；
  - 无检查点 → 队列行/线程/任务卡统一标记「应用重启，任务中断」；
  - 技能流程（非空 `flow.yaml`）暂不支持恢复，直接失败，避免重放已生效动作。
- **停止语义**：只停当前任务，队列继续；运行中任务不能从队列区取消，只能在详情页停止。
- **不学习半截路径**：续跑任务成功也不写 how-to 记忆。

### 2.3 国际化与文档

- 中英文资源 `values/strings.xml` 与 `values-en/strings.xml`，当前 **364/364**，脚本比对零缺失。
- Spec 三件套：`.trae/specs/xiaoman-ui/{spec,tasks,checklist}.md`，每次功能变更必须增量更新。
- 用户文档：`README.md` / `README.en.md` / `docs/ARCHITECTURE.md` / `docs/ui-interaction.md`。

### 2.4 最近的提交

| 提交 | 内容 |
|---|---|
| `92d4657` | 检查点与进度行合并为一次写盘（性能） |
| `879b20b` | 检查点与断点恢复（P0-2） |
| `f5b0839` | 模型未配置时队列保持排队 |
| `b4ba43b` | 持久化任务队列与任务中心（P0-1） |
| `06f834b` | 任务详情内继续对话、主对话精简 |
| `a2f6f7e` | 能力页合并、任务历史、统一 Dock |

---

## 3. 关键文件索引

| 关心什么 | 看哪里 |
|---|---|
| 依赖装配、生命周期、模型构建 | `app/src/main/kotlin/dev/xiaoman/app/AppGraph.kt` |
| 会话、队列、检查点、事件路由 | `app/src/main/kotlin/dev/xiaoman/app/session/AgentSession.kt` |
| 任务线程持久化 | `app/src/main/kotlin/dev/xiaoman/app/session/TaskThreadStore.kt` |
| 队列状态机 | `app/src/main/kotlin/dev/xiaoman/app/session/TaskQueueStore.kt` |
| 聊天 UI / 时间线 | `app/src/main/kotlin/dev/xiaoman/app/ui/ChatScreen.kt`、`ChatTimeline.kt` |
| Dock 输入与导航 | `app/src/main/kotlin/dev/xiaoman/app/ui/ChatDock.kt` |
| 任务页与队列区 | `app/src/main/kotlin/dev/xiaoman/app/ui/TaskHistoryScreen.kt` |
| 任务详情 | `app/src/main/kotlin/dev/xiaoman/app/ui/TaskDetailScreen.kt` |
| Agent 对话编排 | `core-agent/src/main/kotlin/dev/xiaoman/agent/XiaomanAgent.kt` |
| 操作循环（读屏 → 模型 → Sentinel → 执行） | `core-agent/src/main/kotlin/dev/xiaoman/agent/OperatorLoop.kt` |
| 提示词 | `core-agent/src/main/kotlin/dev/xiaoman/agent/Prompts.kt` |
| Agent / LLM 接口 | `core-api/src/main/kotlin/dev/xiaoman/api/{Agent,Llm}.kt` |
| OpenAI 兼容客户端 | `core-llm/src/main/kotlin/dev/xiaoman/llm/OpenAiCompatibleClient.kt` |
| 安全策略 | `core-sentinel/src/main/kotlin/dev/xiaoman/sentinel/PolicySentinel.kt` |

---

## 4. 数据文件（都在 `/sdcard/Android/data/dev.xiaoman.app/files/`）

| 文件 | 内容 |
|---|---|
| `chat/history.json` | 主对话，默认 500 条（设置可调 50–2000） |
| `chat/tasks.json` | 任务线程 + 每任务检查点 |
| `chat/queue.json` | 任务队列（QUEUED/RUNNING/PAUSED/DONE/FAILED/CANCELLED） |
| `chat/quick-commands.json` | 常用指令，最多 8 条 |
| `diary/diary.json` | 日记，最多 500 条 |
| `memory/*.md` | Basic Memory 格式记忆，可直接用 Obsidian 打开 |
| `traces/<run-id>/` | `trace.jsonl`、`audit.jsonl`、步骤截图 |

都是普通 JSON/Markdown，可备份、可删除；删除后 App 从空状态开始。

---

## 5. 正在进行的 P0-4：流式回复（未完成，已保存补丁）

### 5.1 当前状态

上一轮刚开始实现流式回复，改了 3 个文件但**没有完成、无法编译**。为了保持 main 可构建，改动已导出为补丁并从工作区撤回：

- 补丁：`docs/handover/p0-4-streaming-wip.patch`（4.7 KB）
- 涉及文件：`core-api/Agent.kt`、`core-api/Llm.kt`、`core-llm/OpenAiCompatibleClient.kt`
- 应用补丁：`git apply docs/handover/p0-4-streaming-wip.patch`

补丁里已经完成：

1. `AgentEvent.ReplyDelta(taskId, text)`：增量文本事件。
2. `LlmClient.completeStreaming(request, shouldStop, onText)`：默认退回 `complete()`，因此所有现有 fake/测试无需改动。
3. `OpenAiCompatibleClient.completeStreaming` 的重试与错误骨架（429/5xx/`SetLimitExceeded`/`response_format` 回退）。
4. `buildBody(..., stream = true)`。

补丁里**缺失**：

- `stream()` 本身与 SSE 解析（`data:` 行、`[DONE]`、`delta.content`、`delta.tool_calls` 按 `index` 拼接、`usage`、非 SSE 返回时的 JSON 回退）。
- `StreamHttpError` 类。
- 缺少的 import：`CancellationException`、`Job/invokeOnCompletion`、`BufferedSource`。
- 新增事件导致的穷举 `when` 编译错误尚未处理。

### 5.2 继续实现的最短路径

1. **core-llm**
   - 补 `stream(...)`：`call.execute()` 读 `body.source()`，逐行解析；`shouldStop()` 为真时抛 `CancellationException`。
   - 工具调用增量按 `index` 累积 `id/name/arguments`，最后组装 `ToolCall`。
   - 若响应不是 SSE（首行不是 `data:`），把整段 body 交给现有 `parseResponse()`。
   - 用 `Job.invokeOnCompletion { call.cancel() }` 让协程取消能中断阻塞读。
2. **core-agent**
   - `XiaomanAgent.handle` 的聊天轮改为 `chat.completeStreaming(request, { stop.stopped }) { emit(AgentEvent.ReplyDelta(turnId, it)) }`。
   - 操作模型（屏幕 JSON）继续用 `complete()`，不要流式。
3. **app/session**
   - 主对话：收到 `ReplyDelta` 时按 turnId 记录一个 `Assistant` item id，**只更新内存、不写盘**；最终 `Reply` 到达时用同一 id 替换为完整文本并持久化。
   - 任务线程：`submitToThread` 的 threadListener 同样处理；给 `TaskThreadStore` 加 `upsertAssistant(threadId, itemId, text, persist)`，delta 时 `persist=false`。
   - 必须避免每个 token 写一次 `tasks.json`。
4. **穷举 when**
   - `AndroidStepText`、`StepText` 的 `chatItem` 加 `ReplyDelta -> null`。
   - `TraceFormat.kind/line` 加分支；`TraceWriter` 丢弃 delta，避免轨迹爆炸。
   - 检查 `ios-runner` 与 `app/trace` 下所有 `when (event)`。
5. **UI**
   - 气泡文本增长时自动滚动（当前只监听 `rows.size`）。
   - 可选：流式中显示一个轻量光标或「正在输入」。
6. **测试**
   - `OpenAiCompatibleClientTest`：MockWebServer 返回 SSE（文本 + 工具调用 + `[DONE]`）；断流；429；非 SSE JSON 回退。
   - `XiaomanAgent`：fake `LlmClient` 覆盖 `completeStreaming`，断言 `ReplyDelta` 序列与最终 `Reply`。
   - `AgentSessionTest`：delta 后只产生一个 Assistant 气泡，最终文本正确。
7. **文档**
   - Spec 新增 FR-17、`tasks.md`、`checklist.md`、README、ARCHITECTURE、计划里的 P0-4 状态。
8. **端到端验证（重要）**
   - 可在宿主机起一个假的 OpenAI 兼容 SSE 服务，模拟器通过 `http://10.0.2.2:<port>/v1` 访问，debug 清单允许明文 HTTP。这样即使没有真实 Key 也能验收“首 token 延迟、增量渲染、停止”。

---

## 6. 下一步路线

按 [superagent-plan.md](superagent-plan.md)：

1. **P0-4 流式回复**（上面的补丁与清单）。
2. **P0-3 统一工具注册表**：`ToolDescriptor`（名称/来源/风险/作用域/是否可后台/成本），Sentinel 读取描述；MCP 已有 `ConfirmPolicy`，需要与内置工具、技能统一。
3. **P0-5 评测底座**：新增 `eval/` JVM 模块，假 LLM + 假设备 + 场景 DSL，先覆盖聊天、记忆、技能确认、任务失败、高危拦截。
4. 阶段 1：分层记忆、混合检索、用户画像、每日简报。
5. 阶段 2：技能 v2、测试运行器、MCP 发现与权限、流程引擎 v2。
6. 阶段 3：规划器、子代理、校验器、预算与长任务。

**建议顺序**：先 P0-4（用户感知最强，且可用假服务验收），再 P0-5（为后续所有改动提供回归网），最后 P0-3。

---

## 7. 必须遵守的工程约定

1. **只做本地 git**：不 push、不删分支/节点、不重写历史；每次改动本地提交。
2. **每次代码改动同步文档**：Spec 三件套 + README + ARCHITECTURE；代码与文档不一致视为未完成。
3. **国际化**：任何新增按钮、标题、提示、弹窗文案都必须进 `values` 与 `values-en`，不得硬编码；提交前用脚本核对 key 数量与差异。
4. **不要提交 `dist/`**：里面有 65 MB APK 与验收截图；`git add` 用明确路径，不要 `git add -A`。
5. **不要提交 Key**：设置存在加密 SharedPreferences；轨迹与会话已做脱敏。
6. **安全边界**：付款/发送/删除必须确认；技能只能追加确认词；不要为了让测试通过而放宽 Sentinel。
7. **平台说明**：本仓库只有 Android 客户端；Windows/macOS/Linux 没有对应源码，因此“三端同步”不适用，不要虚构桌面端改动。打包只产出 Android APK。
8. **测试优先**：`core-*` 的纯 JVM 测试是主要回归手段；改了会话/队列/检查点必须补 `app` 单测。

---

## 8. 常见坑

| 现象 | 原因与处理 |
|---|---|
| Robolectric 报 `AndroidKeyStore not found` | `Application.onCreate` 不能读加密设置；会话初始恢复用固定 2000 上限，动态上限只在追加/保存时读 |
| 输入框文字和聊天内容叠在一起 | Dock 外圈透明，但卡片本身必须近不透明（96%）；不要把输入框做成全透明 |
| 任务页同一任务出现两张卡 | 队列区与历史列表要按 task id 去重（已在 `TaskHistoryScreen` 处理，改动时别破坏） |
| 重启后任务卡一直“进行中” | `AgentSession.init` 会收尾所有 stale running 线程；新增恢复逻辑时保持这个不变量 |
| `git add -A` 把 APK 加进去 | `dist/` 未忽略；只暂存明确路径 |
| 模拟器时间比本机慢 8 小时 | 模拟器时区是 UTC，不是 bug |
| adb 输入中文没进输入框 | Google 输入法处于组合态；先 `input keyevent 66` 提交，再用 `uiautomator dump` 找按钮 bounds 点击 |
| 离线构建失败 | 加 `--offline`；首次构建需要联网下载依赖 |
| 队列任务不启动 | 检查模型是否配置、无障碍是否开启；`resumeQueue` 在模型未配置时会保持排队 |

---

## 9. 验收现状

**已验证（模拟器，API 35）**

- 构建、单测、lint 全绿；中英文 key 364/364。
- Dock、聊天、能力、任务、记忆、小满、设置页布局正常。
- 队列：新建任务、暂停/继续/取消、重启持久化、RUNNING 无检查点失败/有检查点恢复、任务 id 去重。
- 任务详情内继续对话；主对话不显示会话列表。
- 搜索结果可直达任务详情；日记按天分页。

**仍需真实 Key / 真机**

- 真实模型流式回复（P0-4 完成后）。
- 真实任务执行、任务卡状态刷新、断点续跑。
- 附件图片发给视觉模型。
- 真实厂商 `/models`。
- 语音识别、无障碍读屏/点击、通知、TalkBack、大字体。

---

## 10. 接手后前 30 分钟建议

1. 读 `docs/superagent-plan.md` 阶段 0 + `.trae/specs/xiaoman-ui/spec.md` 的 FR-15/FR-16。
2. 跑一遍测试与 `assembleDebug`，确认本地环境。
3. 装 APK 到模拟器，走一遍：聊天 → 任务页新建任务 → 队列暂停/继续 → 任务详情。
4. 看 `docs/handover/p0-4-streaming-wip.patch`，决定是否继续 P0-4。
5. 开工前先在 `tasks.md` 认领任务，完成后按约定更新三份 Spec 与 README。

有问题先看 [ARCHITECTURE.md](ARCHITECTURE.md) 与 [ui-interaction.md](ui-interaction.md)，它们记录了为什么这样设计，而不只是做了什么。
