# 小满（Xiaoman）

[English](README.en.md)

小满是一个跑在 Android 手机上的开源私人助手。你在它的 App 里聊天，它会记住你告诉它的事，
也能替你操作手机上的其他 App，比如打车、点外卖、改系统设置。新场景以**技能**的形式添加：一个技能
就是一个包含 `SKILL.md` 和可选 `flow.yaml` 的小文件夹，推到手机上即可生效，不需要重新打包 APK。

- 需要 Android 11（API 30）及以上。小满通过无障碍服务读取屏幕并执行操作。
- iOS 无法在手机上单独运行；可用 Mac + WebDriverAgent 的方式试用，见 [docs/ios.md](docs/ios.md)。
- 可接入 MCP 服务（高德地图、腾讯地图、滴滴、12306 等），查询和下单类的事情可以不操作手机，见 [docs/mcp.md](docs/mcp.md)；安卓 App 在「服务」页一键勾选。
- iPhone 不装 App 也能用：网页版添加到主屏幕后聊天、勾选 MCP 服务，见 [docs/web.md](docs/web.md)。
- 模型：任意兼容 OpenAI 接口的服务都可以，例如火山方舟、阿里云百炼、OpenRouter、vLLM、Ollama。
  需要配置两个模型：一个聊天模型，一个能看图的屏幕操作模型。

## 工作方式

```
你：帮我打车回家
 │
 ▼
聊天模型 ── 查记忆（家在哪）、挑技能（taxi-home）
 │
 └─ phone_task ──► 操作循环：截屏 + 节点树 → 操作模型给出一步 → Sentinel 审核 → 执行
                     │                                        │
                     │                         支付 / 发送 / 删除等先问你
                     ▼
               结果摘要回到聊天；成功的操作路径写入记忆，下次执行时参考
```

## 安全

每一步操作在真正落到手机之前，都要经过 Sentinel 策略检查。这一层在模型之外，模型无法绕过：

- **拒绝**：向密码框输入；在受保护（FLAG_SECURE）的页面上操作；技能声明了 `apps` 时操作其他 App。
- **询问**：点击「支付」「发送」「删除」「确认呼叫」等敏感按钮前，需要你确认（允许一次 / 本次任务都允许 / 拒绝）。
- 技能只能**追加**确认词，不能删除内置确认词。
- 每一次决策都会写入审计记录。

## 构建

需要 JDK 17 和 Android SDK 35。

```bash
scripts/gradle.sh --online test :app:assembleDebug   # 首次运行，会下载依赖
scripts/gradle.sh test :app:assembleDebug            # 之后可以离线构建
```

`scripts/gradle.sh` 封装了 `./gradlew`，构建产物放在仓库目录之外，保持工作区干净。可用的环境变量见脚本开头的注释。

## 使用

1. 安装 debug APK，打开小满。
2. 在「设置」里分别填写聊天模型和操作模型的 Base URL、模型名称和 API Key。
3. 按提示开启小满无障碍服务。
4. 回到「聊天」，试试：「帮我打车回家」「记住我家在……」「把 Wi-Fi 打开」。

以火山方舟为例，Base URL 填 `https://ark.cn-beijing.volces.com/api/v3`（也是默认值），模型填方舟上的模型或接入点 ID。

## 课堂实践

[学生动手手册](docs/classroom.md)：TRAE 训练营第二期“用 TRAE 搭建你的个人 Agent 助手”的配套手册。第一节装好小满并跑通淘宝任务，第二节蒸馏你自己，再用 TRAE 改外卖技能、车票技能和首页入口。

## 二次开发：Muse + Today 风格与日常便捷功能

本轮在 main 课程版上做了二次开发，界面融合 Open Muse 的蓝灰气泡与参考应用的淡蓝渐变、白色大圆角卡片，并加入以下能力：

- **全局 Dock**：输入框与底部导航合并为一张悬浮卡片，聊天、能力、任务、记忆、小满、设置页都能直接输入；键盘打开时只保留输入行，卡片外圈透明，内容可滚到按钮周围。
- **附件**：输入框左侧加号支持拍照、相册、文件；图片会随消息进入模型请求，文本文件内容内联进消息；空内容时隐藏发送按钮，右侧固定语音入口。
- **会话历史持久化**：聊天记录写到 `chat/history.json`，杀进程重开仍在；聊天页右上角可清空，记忆和技能不受影响；默认保留 500 条，可在设置里调为 50–2000 条。
- **任务子对话**：派给小满的手机任务会新建独立子对话，主对话只在文本下保留一张任务卡（进行中/已完成/未完成）；点卡片进入任务详情，逐步记录默认展开。任务详情本身也是一段对话，底部输入框可以直接继续和这个任务说话，回复留在该任务线程。任务存在 `chat/tasks.json`。
- **搜索**：聊天页右上角菜单新增搜索，可搜主对话与所有任务子对话；任务结果可直接打开详情。
- **小满日记**：底部「小满」页按天展示横向滑动卡片，记录完成的任务和记忆更新，存 `diary/diary.json`。
- **常用指令卡片**：首页默认 6 条指令（选笔记本、喝水提醒、偏好记忆、查快递、总结今天、翻译一句话），可新建、编辑、删除最多 8 条；点卡片把内容填入 Dock 输入框。
- **语音输入**：Dock 麦克风调用系统语音识别，需要录音权限；设备没有识别服务或权限被拒绝时会给出提示。
- **人性化模型配置**：设置页先选服务商（火山方舟、OpenAI、DeepSeek、阿里百炼、智谱、Kimi、硅基流动、OpenRouter、自定义），填一个 Key，点「获取可用模型」自动拉取 `/models`；优先使用厂商返回的上下文窗口、最大输出、图片模态等元数据，缺失时按模型族估算；模型列表可展开配置显示名称、最大输出、上下文窗口、Temperature、是否支持图片，再分别设为聊天/操作模型；编辑已选模型会回写实际配置，保存时把全部模型和选择一起落盘。
- **模型连接自检**：设置页分别测试聊天模型和操作模型。操作模型会附带 1×1 PNG，验证它确实支持图片输入；401、404、429、网络错误分别给出可操作的诊断。
- **能力页**：原「技能」与「服务」合并为「能力」，顶部分段切换；技能支持搜索、新建、修改、删除，服务支持 MCP 勾选、Key、测试与自定义。
- **任务历史**：底部「任务」页展示全部历史任务，按开始时间倒序；支持全部/进行中/已完成/未完成筛选，点卡片进入任务详情。
- **设置入口**：聊天页左上角齿轮进入设置；主题（跟随系统/浅色/深色）、语言（跟随系统/简体中文/English）、语音自动发送、通知开关、请求超时、会话保留条数、无障碍入口、清空会话/轨迹、版本说明。
- **技能管理**：技能页支持搜索、详情、新建和「用一句话修改」；手机上可直接通过 `skill_read` / `skill_save` / `skill_delete` 读写用户技能，保存前必须在聊天里回复「确认」。
- **记忆管理**：记忆页支持搜索、新建、编辑、删除；编辑后按 Basic Memory 格式重新写入。
- **国际化**：新增文案全部走资源文件，中文在 `app/src/main/res/values/strings.xml`，英文在 `values-en/strings.xml`；两边各 351 个 key 且保持同步；设置页可直接切换应用语言。

后续演进（任务队列、检查点、分层记忆、技能生态、多智能体）见
[SuperAgent 功能开发计划](docs/superagent-plan.md)。

### 构建与测试（Windows）

```powershell
$env:JAVA_HOME = 'D:\Android\jdk17'   # JDK 17
$env:ANDROID_HOME = 'D:\Android\sdk'  # Android SDK 35
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core-llm:test :core-agent:test :core-skills:test :app:testDebugUnitTest
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。首次构建会下载 Gradle 依赖，之后可离线构建。
Debug 清单允许明文 HTTP，便于连接本机 vLLM/Ollama（例如 `http://127.0.0.1:11434/v1`）；Release 清单保持默认仅 HTTPS。

## 技能与开发者 CLI

- 技能格式见 [docs/skills.md](docs/skills.md)，示例在 [skills/examples/](skills/examples/)。
- [cli/](cli/README.md)（Python 3，仅依赖标准库）可以校验技能、通过 adb 推送到手机、在 debug 包上直接跑任务并查看轨迹：

```bash
python3 cli/xiaoman.py lint skills/examples/*
python3 cli/xiaoman.py push skills/examples/taxi-home
python3 cli/xiaoman.py run "打开设置里的 Wi-Fi 页面" --skill open-settings-wifi --follow
python3 cli/xiaoman.py traces show <run_id>
```

## 目录结构

```
xiaoman/
├── core-api/        # 接口与数据结构，其他模块只依赖它
├── core-llm/        # 兼容 OpenAI 的模型客户端
├── core-memory/     # 记忆：Basic Memory 格式的 Markdown 笔记 + 中文检索
├── core-skills/     # 技能加载与校验（SKILL.md / flow.yaml）
├── core-sentinel/   # 安全策略：允许 / 询问 / 拒绝 + 审计
├── core-agent/      # Agent：聊天编排、操作循环、技能流程、经验沉淀
├── hands/           # Android 无障碍层：读屏、点击、输入、悬浮停止按钮
├── ios-hands/、ios-runner/ # iOS WDA 设备接口 / Mac JVM 命令行（使用说明见 docs/ios.md）
├── core-mcp/              # MCP 客户端，把外部服务的工具交给聊天模型（见 docs/mcp.md）
├── mcp/                   # MCP 服务器示例清单 servers.example.json
├── web-server/            # 网页版：服务端 + 移动端网页（见 docs/web.md）
├── app/             # Android App：聊天、技能、记忆、设置界面
├── cli/             # 技能开发者 CLI
├── skills/examples/ # 示例技能，也会打包进 APK
├── docs/            # 架构与技能文档
└── scripts/         # 构建脚本
```

`core-*` 都是纯 Kotlin/JVM 模块，不依赖 Android API，可以在任何机器上跑单元测试。详细设计见
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 记忆

记忆是 Basic Memory 格式的 Markdown 文件，存放在
`/sdcard/Android/data/dev.xiaoman.app/files/memory/`，可以直接拷进 Obsidian 查看和编辑。
「忘记」会直接删除对应文件。

二次开发新增的本地文件：

- 会话历史：`/sdcard/Android/data/dev.xiaoman.app/files/chat/history.json`
- 任务子对话：`/sdcard/Android/data/dev.xiaoman.app/files/chat/tasks.json`
- 小满日记：`/sdcard/Android/data/dev.xiaoman.app/files/diary/diary.json`
- 常用指令：`/sdcard/Android/data/dev.xiaoman.app/files/chat/quick-commands.json`

都是普通 JSON，可备份或删除；删除后 App 会从空历史/默认指令开始。

## 许可证

MIT，见 [LICENSE](LICENSE)。
