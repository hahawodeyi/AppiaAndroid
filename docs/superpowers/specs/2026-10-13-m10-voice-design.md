# M10 语音通话设计（GREENFIELD，方案 A：移植决策核心 + 替换平台边缘）

> 2026-10-13 brainstorming 定稿。RN 语音域从未通过 M0 真机门禁（iOS pod install 失败；Android 代码就绪未验收），M10 为完整原生实现，总纲授权「方案可重做，不等 RN」。
>
> 范围裁定（用户）：①Telecom ConnectionService 系统通话 UI（GREENFIELD，RN Android 无）②录音/摘要缓 backlog-m10 ③跨 org 通话入主线 ④复用 M8 浮窗基建 ⑤真网关+双端互通入完成定义。

## 0. 方案裁定

**方案 A**：RN `lib/voice/` 89 个纯决策函数（~5080 行，Jest 全覆盖）+ 40+ 测试文件 1:1 移植为 Kotlin——RN 踩过的坑全在测试里，测试用例即规格书。平台边缘全部换原生：react-native-agora → AA Agora 引擎模式（M8 AgoraMeetingEngine 先例）、InCallManager → AudioManager、iOS CallKit → Telecom ConnectionService、RN 推送桥 → AA PushClickRouter 现有桩。

RN 关键事实（移植时不得失真）：
- `voiceCallKeep.ts:79` `Platform.OS !== 'ios'` 早退——**RN Android 从未接过系统通话 UI**；Telecom 为 AA 增量（增强非对齐）。
- onUserOffline 故意 no-op（对齐旧版：远端掉线不挂断，靠 exist/轮询收尾）。
- busy 相位存在但无信令来源（RN 未接线）——AA 如实保留相位+注释，不虚构信令。
- 主叫固定 Agora UID 8888（VOICE_HOSTER_AGORA_UID）；被叫 uid 从 receivers 找自己的 receiverUid，找不到拒绝接听，**绝不回退 8888**（同 uid = 双向无声）。
- 独立网关 `https://shengwang-server.appia.vip`（QA: `-qa` 后缀，serverUrl 含 `.dev.` 分流）；POST 用 `token` 头、GET 用 `X-Auth-Token` 头、notify 端点两头皆不带；businessToken 来自 Rocket `users.externalToken`（2 天 TTL 缓存）；`40002` = token 过期 → invalidate + 重试一次。
- org = `Enterprise_ID` settings 值 trim，空回退 `'bitmain'`（resolveVoiceOrg）。
- 时基 uid：`HHmmssSSS` 数字 + receiver index。
- 主动呼出不上报系统通话（RN 仅 iOS DM 接通后上报；AA 维持不上报，避免污染系统通话记录——裁定记录）。

## 1. 架构分层

```
core/voice/                    ← 纯协议层（无 Android 依赖，JVM 可测）
  VoiceTypes.kt                IVoiceCallMsg/IVCUser/IVoiceChatReceiver/IVoiceChatHoster + parseCallMsg（recordData/u 双层 JSON 解析）
  OncallApi.kt                 独立 OkHttp 客户端（shengwang 网关；POST=token/GET=X-Auth-Token/notify 免头）
  VoiceTokenCache.kt           businessToken 2 天 TTL（users.externalToken → RocketApi；失败不缓存）
  VoiceCallTimers.kt           30s 主叫提示/65s 主叫超时/65s 被叫超时（recordId 锚定防串）
  lib/ 决策函数包               RN lib/voice/ 89 函数 1:1（文件名对照，不合并不重组）
  rtc/VoiceRtcEngine.kt        Agora 语音引擎（AgoraMeetingEngine 模式：AgoraRtcApi 接口化+@Singleton+SharedFlow 事件）
  VoiceEnv.kt                  AGORA_APP_ID='fbb72b90d7f34c3487bf65d15c2b40bb' + apiHost 分流 + resolveVoiceOrg

domain/voice/
  VoiceSessionStateMachine.kt  RN voiceStateMachine.ts 直译（10 相位/9 事件/transition 纯函数）
  VoiceSessionStore.kt         StateFlow<会话快照> + 动作签名（zustand get/set 的 Kotlin 化）
  VoiceSessionController.kt    编排：会话动作 × Agora × oncall × Telecom × FGS × 浮窗接线（RN 2079 行 store 的宿主；Deps 注入缝保留）
  TelecomCallManager.kt        ConnectionService 桥（SELF_MANAGED 自管连接）
  CallSyncWatcher.kt           callMsg 三源汇入：Room observe / 推送 ingress / 轮询 → controller.syncCallMsgFromRoom

feature/voice/ui/
  VoiceCallOverlay.kt          全屏三态（回铃/接听确认/宫格+控制条）
  VoiceIncomingBanner.kt       顶部来电横幅（App 级装配于 MainActivity 根部）
  VoiceCallMiniBubble.kt       应用内拖拽气泡 + 系统浮窗（复用 M8 MeetingFloatWindow 基建与降级链）
  VoiceMemberPickerScreen.kt   群聊选人（复用 M4 选人器）
  RoomVoiceJoinBanner.kt       房间内「N 人通话中」横幅
  VoiceChatMessage.kt          calling/talking 消息卡片 + 回拨
```

裁定：
- zustand 巨型 store 拆 **Store（纯状态 StateFlow）+ Controller（编排单例）**；RN VoiceSessionDeps 保留为 Controller 构造注入缝。
- 语音**独立 Agora 引擎实例**，不复用 AgoraMeetingEngine 单例本体——互斥由状态机层保证（blocksVoiceStart 已落地 M8），引擎层物理隔离避免 create/destroy 竞态。
- callMsg/onCallStatus 数据链已通（Chat/Room/Subscription 三表列 + ChatMerger:338/369 落库已存在，M2 移植时已带）。

## 2. 核心时序

**主叫**：入口 → startOutgoing(hoster)：守卫（phase!=idle / blocksVoiceStart / canStartOutgoing 房间 callMsg 活跃 / 麦克风权限）→ channelId=Date.now() 字符串 + receivers 补 uid/status → 占位 callMsg（outgoingRinging+dialing）→ fetchAgoraJoinCredentialsWithRetry（8888）→ oncallInformInit → recordId（失败 toast+reset；abort 则补发 status=cancel）→ joinAgoraChannel → onJoinSuccess 未接通则回铃 wav（assets）→ startHostOutgoingTimers（30s hint/65s end）→ 轮询（ACTIVE_CALL_POLL_PHASES 内 10s oncallGetRecordStatusV2 → syncCallMsgFromRoom）。

**被叫**：推送 → PushPayloadParser oncall 桩接管 → ingress 等价（未登录入队登录后 drain）→ resolveVoiceCallFromPush（payload callMsg 或拉 record）→ shouldPresentIncomingForUser → presentIncoming（铃声+Telecom reportIncomingCall+oncallNotifyHost+65s 超时+轮询）→ onAnswer/UI accept → resolveAgoraJoinUid → oncallJoin（org=记录 org——跨 org 关键）+ joinAgoraChannel（token 用记录 org 签发）→ AGORA_JOINED → inCall → Telecom setActive + microphone FGS。

**同步面**：syncCallMsgFromRoom(rid, raw, onCallStatus?) 内部裁定链逐函数移植：跨房间不干扰 → 他端已接听 resetSession → otherIncoming 入队（shouldQueueOtherIncoming）→ 群组 invitee 增量重置主叫计时 → 主叫群组进入资格 → idle 发现来电 presentIncoming → incomingRinging 残缺快照抑制（同 recordId calling/talking 缺 exist/userStatus 不重置）→ stale ended sync 抑制 → shouldEndCallFromCallMsg（单聊他端挂断/拒接、群组 exist 结束）→ endCall/resetSession。

**登出/切组织**：endCall('logout') 汇入 AuthRepository.logout 序列（mailBadge.reset 同位）；跨 org 接听 = endCall → 400ms 延迟 → OrgSwitchCoordinator.switchTo(目标 org) → presentIncoming(autoAccept)。

## 3. UI 形态与 Telecom

- **Overlay 三态**：outgoingRinging（回铃+30s 提示）/incomingUiExpanded（全屏接听确认）/inCall（宫格+isSpeaking 波纹+静音角标+控制条：静音/扬声器/最小化/挂断+计时）；ending 保持 ~800ms 再消失。
- **横幅**：任意页顶部黑条，点击展开确认态；App 级装配（MainActivity 根部，非路由级）。
- **第二路来电**：otherIncoming → VoiceOtherCallBanner → 三型弹窗（getOtherIncomingAlertType：1=同 org 接听确认/2=加入确认/3=跨 org 切主体）；confirmOtherIncomingAlert/switchToOtherIncoming/rejectOtherIncoming 全动作移植。
- **Telecom（GREENFIELD）**：VoiceConnectionService SELF_MANAGED——锁屏接听/挂断、系统通知、音频焦点、蓝牙线控；onAnswer/onReject/onDisconnect 回调直达 Controller；**降级链**：phone account 未授权/异常 → 静默跳过，应用内横幅照常（语音域永不因 Telecom 挂死）。主动呼出不上报（§0 裁定）。
- **最小化**：应用内拖拽气泡（M8 拖拽累积+持久化复用）；出应用 = MeetingFloatWindow 同机制换语音皮肤 + microphone FGS 通知（点击回通话页）。
- **房间入口**：RoomVoiceJoinBanner（onCallStatus/callMsg 驱动）+ 附件菜单「语音通话」（单聊直拨/群聊选人页）；MemberProfile 语音按钮解除禁用桩。
- **消息卡片**：calling/talking 消息 → VoiceChatMessage（状态+成员摘要+录音入口缓）→ startFromMessage（活跃=加入/结束=回拨 userStatus）。

## 4. 错误处理与竞态

- 40002 → invalidate+重试一次；再失败 toast token_failed+resetSession。HTTP≥400 → oncall_http_error 前缀拆分映射 init_failed/generic；有 recordId 走 endCall（留痕），无则 resetSession。externalToken 失败不缓存。
- Agora join 失败 → toast+endCall/reset；onError 仅日志。uid 解析失败拒绝接听（不回退 8888）。
- **竞态五件套全移植**：endCallInFlight 单飞（重复调用返回同一 Deferred）、sessionGeneration 代数（旧异步链对代丢弃+abort 补发 cancel）、agoraGeneration（release 后回调丢弃）、残缺快照抑制（:905-936 整段）、stale ended sync 抑制。
- 500ms 音频路由延迟：Android 保留（真机验证前不删，标注「iOS 补偿，Android 待真机裁定」）。
- 权限链：RECORD_AUDIO 运行时（拒=发起静默止/接听 toast）；phone account 引导一次+降级；悬浮窗复用 M8 降级链。
- 进程死亡：无需持久化（RN 同裁定）——FGS+推送 isCallEnd 冷启动接管（PushClickRouter 已有桩）+ 服务端轮询超时收尾。

## 5. 测试与验收

分层：①lib 决策函数测试 1:1（RN 测试名保留为注释锚）②状态机转移矩阵表驱动 ③OncallApi MockWebServer 全端点（header 语义/40002/org 透传）④Controller fake deps 全时序（竞态五件套）⑤CallSyncWatcher 三源去重 ⑥TelecomCallManager Robolectric ShadowTelecom ⑦UI 装配 testTag 可达性（MeetingRoomWiringTest 同款）⑧门禁×3 + Maestro 回归（1.1.0）。

验收：单测全绿+门禁×3 零新增 flake+模拟器零崩溃+Maestro 双绿；**真机双端互通**（用户域，协议第二十二节）：单聊全链/群聊宫格+加入/第二路弹窗/跨 org 切主体/最小化+系统浮窗/锁屏接听。外部依赖：两个可互通测试账号（用户提供）+ shengwang 网关可达 + Agora AppId（硬编码沿用）+ Telecom phone account 授权一次。

已知差异（协议第二十三节）：录音/摘要缓 backlog-m10、busy 不接线（RN 同）、主动呼出不上报 Telecom（裁定）、500ms 延迟待真机裁定、Telecom 为增强非对齐。

规模预估 ~9900 行 / 12-14 任务（M8 体量相当）。
