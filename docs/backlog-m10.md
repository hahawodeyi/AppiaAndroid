# M10 杂项 Backlog（语音域遗留收编）

> 记录性质：本页收编 M10 语音通话终审遗留的 deferred 杂项与用户域真机验收项
> （非阻塞、不联动验收）。每项一行 + 建议归属（落地时随对应任务/里程碑顺手修复，
> **本页不直接排期**），模式同 backlog-m6.md / backlog-m8.md。

| # | 事项 | 建议归属 | 状态 |
|---|------|----------|------|
| 1 | **通话录音与摘要**：RN 通话内录音（服务端侧录音文件）+ 摘要展示——i18n key 已迁移（`I18nKeys.kt` 的 `voicecall_recordnotfound`/`voicecall_recordsaved`/`voicecall_recordtitle`/`voicecall_sharerecord`/`voicecall_viewrecord`/`voice_chat_record_saved`/`voice_chat_recording`/`voice_chat_start_record`），UI 面缓 | 语音域后续任务（`feature/voice/ui/VoiceCallOverlay.kt` 录音入口 + `core/voice/OncallApi.kt` 录音端点） | 开放 |
| 2 | **系统级通话浮窗**：v1 出应用由 FGS 通知承载（CATEGORY_CALL 常驻通知，点击回通话页——`VoiceForegroundService.kt`）；系统悬浮窗形态（对位 RN iOS CallKeep 浮窗体验）缓 | 语音域后续任务（`feature/voice/ui/VoiceCallMiniBubble.kt` 出应用分支 + `core/voice/platform/VoiceForegroundService.kt`） | 开放 |
| 3 | **busy 信令未接线**：服务端无 busy 信令源（RN 同构）——对端占线靠 65s 超时兜底，无「对方忙」即时反馈；服务端补信令后接 `RemoteBusy` 事件（状态机已备） | 服务端信令 → 语音域接线（`domain/voice/VoiceSessionStateMachine.kt` RemoteBusy 事件已在位） | 开放（依赖服务端） |
| 4 | **onUserOffline no-op**：RTC 对端掉线回调不直接 endCall（RN 同构，commit 096dd3a 后对齐）——靠 callMsg 状态同步/计时器兜底 | 语音域观察项（真机验收后裁定是否收紧 `core/voice/rtc/VoiceRtcApi.kt`） | 开放（观察） |
| 5 | **Telecom OEM 兼容清单**：自管 ConnectionService 在各 OEM ROM（MIUI/鸿蒙/ColorOS/OriginOS 等）的系统来电界面行为差异未真机覆盖——`core/voice/platform/VoiceConnectionService.kt`/`TelecomCallBridge.kt` 需按机型建立兼容清单 | 用户域真机验收（多机型）→ 兼容清单固化到协议第二十二节 | 开放（用户域） |
