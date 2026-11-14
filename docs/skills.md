# 编写技能

一个技能教会小满一个场景（打车、外卖、排今日计划、按记忆出题……）。技能是一个文件夹，包含 `SKILL.md`
和可选的 `flow.yaml`，不需要重新打包 APK。示例见 `skills/examples/`。

```
taxi-home/
├── SKILL.md      # frontmatter + 给操作模型或聊天模型的说明
└── flow.yaml     # 可选，固定步骤
```

## 技能放在哪里

手机上：`/sdcard/Android/data/dev.xiaoman.app/files/skills/<name>/`。App 也会把示例技能作为 assets
打包；手机目录里同 `name` 的技能会覆盖内置技能。App 启动时会重新加载技能，debug 包也可以通过
`dev.xiaoman.debug.RELOAD_SKILLS` 广播触发重新加载。

## 在手机上用一句话新建、修改技能

在聊天里直接说「帮我新建一个技能：……」或在「技能」页点 **新建技能** / 每张卡片上的 **用一句话修改**，
小满会追问补齐信息，然后写出完整 `SKILL.md` 并交给工具处理。模型侧有三个工具：

| 工具 | 作用 |
|---|---|
| `skill_read` | 读取技能全文，先查用户目录再查内置目录，返回 `origin`（user/bundled）、`skill_md`、`hasFlow` |
| `skill_save` | 先用本文档的规则校验 `SKILL.md`；有问题直接返回 problems，不打扰用户。通过后把完整草稿发到聊天里请你确认，回复「确认/是/好/可以/y/yes」才保存，其他回复取消 |
| `skill_delete` | 只能删除用户技能，删除前同样要确认；内置技能不能删除，只能写同名用户技能覆盖 |

保存只写用户技能目录 `<files>/skills/<name>/SKILL.md`（先写临时文件再 rename，不会动已有的
`flow.yaml`），保存或删除后会自动 `reload`，并确认重新加载后的列表里能取到这个技能。覆盖同名内置技能时，
内置文件保持不变，删除用户版本后内置技能自动恢复。

这和在电脑上用 `cli/xiaoman.py push` 推技能是**同一个用户技能目录**，后写入的覆盖先写入的：手机里一句话
保存的技能可以在电脑上改，推上去的技能也可以在手机里再改或删除。

## SKILL.md

```markdown
---
name: taxi-home
description: 用高德地图打车回家（呼叫前需用户确认）
apps: [com.autonavi.minimap]
channel: deeplink-then-gui
confirm_before: [确认呼叫]
memory: [places/家]
deeplinks:
  route: "amapuri://route/plan/?sourceApplication=xiaoman&dname={destination}&dev=0&t=0"
---
# 高德打车回家

1. 目的地取自记忆 `places/家`；没有地址时用 ask_user 询问，不要猜。
2. 点「确认呼叫」前必须得到用户确认。
```

| 字段 | 必填 | 取值 |
|---|---|---|
| `name` | 是 | `[a-z0-9-]+`，与文件夹同名且唯一 |
| `description` | 是 | 一行。聊天模型靠它挑选技能，写清楚做什么、用哪个 App |
| `apps` | 否 | 技能可以操作的包名。设置后，Sentinel 会拒绝操作其他包 |
| `channel` | 否 | `api`、`cli`、`deeplink`、`gui`（默认）、`deeplink-then-gui`、`mixed`、`chat` |
| `confirm_before` | 否 | 追加的确认词（列表或单个字符串） |
| `memory` | 否 | 加载进操作或聊天提示词的笔记 permalink，例如 `places/家` |
| `deeplinks` | 否 | `名称: "scheme://..."`；`{占位符}` 由操作模型填写 |

手机技能的 frontmatter 之后是写给操作模型的 Markdown。尽量简短具体：在 App 里怎么走、要给用户读出什么、
什么时候 `ask_user`（登录、验证码、商品售罄）、什么时候 `finish`。

## 小满内置链接

有些系统功能有稳定的入口，比一页页点更可靠。小满把下面的链接转成系统调用，技能里像普通深链一样声明和使用：

| 链接 | 作用 |
|---|---|
| `xiaoman://alarm/set?hour=15&minute=0&label=喝水` | 打开系统时钟的「新建闹钟」页，时间和标签已填好；仍需在页面上核对后点保存 |

hour 为 0–23，minute 为 0–59（缺省 0），label 可选。其他 `xiaoman://` 链接会被拒绝。示例见 `skills/examples/health-alarms`。

## 聊天工作流（channel: chat）

聊天工作流直接在对话里执行，适合排今日开工卡、根据记忆出题、按偏好整理 AI 热点。
用户的话符合 description 时，聊天模型会按正文说明做，可以配合 memory_*、reminder_set 和外部工具。
它不会交给 phone_task，也不能设置 apps、deeplinks、confirm_before 或 flow.yaml；即使字段为空也会报问题。
memory 可以引用笔记，加载时读到的笔记会附在该工作流说明下，读不到的引用忽略。

例如在 today-plan/SKILL.md 里写：

~~~markdown
---
name: today-plan
description: 帮我排今日开工卡，并设置开工提醒
channel: chat
memory: [偏好/工作]
---
1. 结合工作偏好，询问今天最想完成的事和可用时间。
2. 给出三件事的开工卡：任务、第一步、预计用时。
3. 问清提醒时间后调用 reminder_set，不要猜时间。
~~~

单个工作流正文最多注入 2000 个字符，所有工作流正文与引用笔记合计最多 8000 个字符。
超过总量的工作流只列名称和描述，因此说明尽量简短，把关键步骤放在前面。

网页版通过 --skills DIR 或 XIAOMAN_WEB_SKILLS 加载目录，只使用 chat 渠道。
未配置时没有技能；--local 会先尝试工作目录，再尝试 jar 目录及其上一级的 skills/examples、skills，
使用第一个存在的目录。修改技能后重启 web-server 重新加载。

## flow.yaml

固定步骤按顺序执行；`ai` 步骤把一个子目标交给自由操作循环。

```yaml
steps:
  - launch: com.autonavi.minimap               # 设置了 apps 时，必须在 apps 里
  - deeplink: route                            # deeplinks 中的键名，或字面的 scheme:// URI
  - wait: 1500                                 # 毫秒，0..600000
  - ai: 切换到打车页，选择快车并读出预估价
  - type: { target: 目的地, text: "{destination}" }
  - confirm: 确认呼叫快车回家？                  # 明确请用户确认
  - tap: 确认呼叫                               # 点击与该文字最匹配的元素
  - assert: 正在为您呼叫                        # 屏幕上必须出现这段内容，否则流程失败
```

每个步骤恰好是一个 `类型: 值` 键值对。

## 校验

技能在加载时校验。有任何问题的技能都不会被加载（其他技能照常加载），每个问题都会标出文件和行号。
唯一的例外是 `name` 与文件夹名不一致：会报告问题，但技能仍以 `name` 加载。未知字段也算问题，
这样 `confirm-before` 之类的拼写错误不会悄悄丢掉安全确认词。用户技能加载失败时，同名的内置技能保持生效。

在电脑上可以先用 CLI 校验：`python3 cli/xiaoman.py lint <技能目录>`。

## 安全

- Sentinel 在模型之外运行，技能无法关闭或放宽它。
- `confirm_before` 只能在内置列表（支付、转账、发送、删除、下单、提交订单、确认呼叫……）上追加，不能删除。
- `apps` 只会缩小技能可以操作的范围。`deeplink` 操作如果与技能的 `deeplinks` 都不匹配（scheme 和 host
  都相同才算匹配），需要用户批准。
- 密码框和受保护页面一律拒绝操作。付款请留给用户完成；不要把密码或 API Key 写进技能或 `memory`。
