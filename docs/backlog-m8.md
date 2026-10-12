# M8 杂项 Backlog（终审 M8-F 遗留收编）

> 记录性质：本页只收编 M8 终审（APPROVED_WITH_ACTIONS，总纲 §4.9）遗留的 deferred 杂项
> （无归属、非阻塞、不联动验收）。每项一行 + 建议归属（落地时随对应任务/里程碑顺手修复，
> **本页不直接排期**），模式同 backlog-m6.md。
>
> 来源：总纲 §4.9 第 1/2/3/4 条 + M7 终审 riders（§4.8-1/2，M8 计划未排为显式任务行，继续挂账）。

| # | 事项 | 建议归属 | 状态 |
|---|------|----------|------|
| 1 | **RTC 凭据抓包回填**（用户域一次性，未闭合）：credentials 响应体字段名（appId/token/uid 反推）——spike 文档 §4 六步流程；回填后收紧 parseMeetingCredentials（现为宽容建模+cert-less 兜底） | 用户抓包 → M9+/任意触网任务顺手收紧 `core/meeting/MeetingCredentialsApi.kt` | 开放（用户域） |
| 2 | **视频帧上屏**（M8 范围外遗留）：tile 现呈参与者态——帧渲染需扩 AgoraRtcApi（setupRemoteVideo/setupLocalVideo canvas 面）+ 宫格接帧 | 会议视频域后续任务（RTC 引擎已就位） | 开放 |
| 3 | **abiFilters splits**（已裁定归 M11）：arm64-v8a 单 ABI 93.1MB；x86_64/Intel 模拟器需临时移行重建 | M11 发布清单（per-ABI 拆分评估） | 已裁定归 M11 |
| 4 | **M7-F-1 rider**：InAppWebIntercepts.kt:184 每 probe 新建 OkHttpClient——改 RocketHttp.newBuilder 共享连接池（一行） | feature/web 触碰时顺手（M7 终审 rider，M8 未排） | 挂账 |
| 5 | **M7-F-2 rider**：TodoListRepository 多实例语义文档化（现 refetch-on-entry 行为等价——补 KDoc） | feature/todo 触碰时顺手 | 挂账 |
| 6 | **MeetingHome data-key 碰撞**：MeetingHomeScreen.kt:133 LazyColumn key `meetingId ?: id ?: subject ?: hashCode()`——无 id 行退化为 subject/hashCode，同名主题跨页可撞 key | feature/meeting MeetingHomeScreen 触碰时改稳定复合 key | 开放 |
| 7 | **initial-handoff pop 未 success-gated**：InAppWebScreen.kt:369-373 初始 URL 命中 AntMeeting → onAntMeetingHandoff(url) 后无条件 onBack()——RN 是 openAntMeetingWeb 成功后才 goBack；openMeetingRoom 内 number 解析失败仅 toast（网页已出栈，用户停在上一页而非留在原网页） | feature/web InAppWebScreen（移交回调改带回执或 pop 延后到导航成功） | 开放（T7 seam 已换，仍待验） |
| 8 | **<1e9 数值阈值分歧**：MeetingPanelData.kt:64 `SEC_THRESHOLD = 1e9` 分界——RN meetingBookingDisplay.ts:48 是 `n > 1e9 && n < 1e12` 双边窗口（上界 1e12 拦毫秒误判）；AA 无上界，2001-09-09~2001-09-01 区间外的极大秒值会走 sec 分支 | feature/meeting MeetingPanelData parseBookingEpochSec（补上界对齐 RN） | 开放 |
| 9 | **卡片双解析不一致**：RoomAnnouncement.meetingBooking()（MeetingModels.kt:182）在 RoomMeetingScreen 列表项(:101)与 onJoin(:105)/MeetingCards(:99) 各自调用——同一 item 多次 JSON→Meeting 解析；结果一致但重复开销，且未来字段演化易分叉 | feature/meeting 触碰时收敛为 remember(item) 单次解析下传 | 开放 |
| 10 | **createTitle 终末回退测试**：MeetingPanelData.kt:397 标题三级回退（subject → message 首行 → meeting_createTitle）——终末回退分支无直接断言（MeetingPanelDataTest 17 例未覆盖第三级） | feature/meeting 测试补齐 | 开放 |
| 11 | **会议分组补至 8**：M8 计划 T2「分组排序 ≥8」实交付少于计划数（其余超额），组内排序语义已有覆盖 | feature/meeting 测试补齐（upcoming/past 边界日期各补一组） | 开放 |
| 12 | **Compose 冒烟缺口**：M8 计划 T2/T4/T5 各列「Compose 冒烟」，实际单测覆盖为主、UI 冒烟未逐屏补 | 会议域 UI 触碰时补（或 M9 收尾模拟器走查统一带） | 开放 |
| 13 | **消息内链接 openLink 接线**（总纲 §4.9-5，pre-existing）：InlineEnv.onLinkPress 未接线——T13 组装收口（接线后 AntMeeting 链接自动走会议移交+全部链接走 openLink 分流） | 聊天消息渲染域任务（非 M9） | 开放 |
| 14 | **RTC 真会议验收**（用户域）：Agora AppId+二设备+网络；协议十八节操作步骤已列 | 用户验收 | 开放（用户域） |
| 15 | **可带走低危**：T5 未用 import/T8 mojibake 备注/T9 uiMode 超集（future candidate）/各任务计数笔误 | 随触碰收敛 | 记录在案 |
