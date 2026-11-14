---
name: health-alarms
description: 用手机自带的时钟 App 一次新建几个健康提醒闹钟（喝水、起来活动等），时间和标签由用户指定（只新增，不改动已有闹钟）
apps: [com.android.deskclock, com.google.android.deskclock, com.android.BBKClock]
channel: deeplink-then-gui
deeplinks:
  new_alarm: "xiaoman://alarm/set?hour={hour}&minute={minute}&label={label}"
---
# 健康闹钟

目标：按用户说的几个时刻，在系统时钟 App 里**逐个新建**闹钟，每个闹钟带上用户给的标签（如「喝水」「起来活动」）；不编辑、不删除、不开关任何已有闹钟。

1. 先把用户说的每个时间换算成 24 小时制时刻（例如「下午 3、4、5 点」= 15:00、16:00、17:00），列成清单，按顺序一个一个做。上午/下午不清楚时用 ask_user 问，不要猜。一次最多 6 个，超过就 ask_user 让用户精简。
2. 对清单里的每个时刻：
   - 用 deeplink 打开时钟的「新建闹钟」页，uri 写完整链接，例如 `xiaoman://alarm/set?hour=15&minute=0&label=喝水`（hour、minute 填数字，label 填标签；开头必须是 `xiaoman://alarm/set`）。打开后时间和标签已经填好。
   - 核对页面上的小时、分钟和标签都对，再点「完成」或「保存」。不对时改正再保存；滚轮只在对应那一列上慢慢滑（swipe 加 `"durationMs": 1000`），滑完先读中间的数字。
   - 保存后在清单里把它划掉，接着做下一个。已经划掉的不要再建。
3. 全部建好后启动时钟 App（vivo 为 com.android.BBKClock，原生为 com.android.deskclock，Pixel 为 com.google.android.deskclock），在「闹钟」页确认每个新闹钟都在（列表按时间从早到晚排，到对应时间的位置找，找不到再上下滚动）：看**这一行里显示的时间和标签文字**，不要用整行描述（例如「09:00,喝水,仅一次」），有些手机上它和这一行实际显示的内容对不上。
4. 全程不要点已有闹钟的开关，不要进入已有闹钟的编辑页；看到「删除」「批量管理」不要点。

停在闹钟列表并 finish，summary 逐条写新建的时刻和标签。某一个设不好时，用 ask_user 说明卡在哪一个，或 finish 且 success=false 写清已建好哪些。
