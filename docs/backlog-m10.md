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
| 6 | **KDoc 润色三组**（终审裁定 T1-4/T8-8/T5-1）：T1 三函数缺 RN :line 锚；T8 锚漂移 5-10 行；applyInCallAudio 音量 100 重置省略未补记 | 触碰对应文件时顺手 | 开放 |
| 7 | **isQueueExistFalsy 不把 JsonNull 判 falsy**（终审 T2-1）：仅服务器显式发 `exist:null` 分歧，尾部 shouldPresent 门槛掩蔽；升级路径一行（`if (exist is JsonNull) return true`） | 触碰 `ShouldQueueOtherIncoming.kt` 时 | 开放 |
| 8 | **ShouldEndCallFromCallMsg DM 路径 `localUsername == null` 应 isNullOrEmpty**（终审 T2-2）：空 username 会话为退化态，行为无差 | 触碰该文件时 | 开放 |
| 9 | **VoiceTokenCache.cached 未 @Volatile**（终审 T4-3）：跨协程可见性理论问题，最坏自愈型双取（40002 后再取一次） | 触碰 `VoiceTokenCache.kt` 时一词 | 开放 |
| 10 | **runEndCallCleanup 双读 sessionContext**（终审 T7-1）：纯性能微差（两次 runBlocking Room 读），行为无差 | 触碰 `VoiceSessionController.kt` 端清理逻辑时 | 开放 |
| 11 | **验证与工具链约束两项**（终审 T9）：模拟器 Telecom dumpsys 验证（reject 后无残留连接/后续来电旗标翻转）；migrate_i18n.py 重跑禁令（M4 起常设——会静默回滚手工迁移键，工具链跟进前有效） | 真机验收 / i18n 工具链任务 | 开放 |
| 12 | **测试外观两处**（终审 T10-5/T11-3）：PushClickRouterTest:107 陈旧测试名（断言仍真）；确认页按钮无 performClick 测试（全仓先例缺失） | 触碰对应测试时 | 开放 |
| 13 | **工具链两项**（终审 T12 concerns）：pre-commit 中文标点检查过严（U+3001 顿号也拦，`0x3001.toChar()` 规避先例已立）；RealtimeSessionManagerTest 全量负载时序 flake（隔离绿标准延续，缓解候选：加宽 awaitTrue 超时/拆并行度） | CI/工具链任务 | 开放 |
