# M2 会话列表+单聊 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的会话列表（三段分组/排序/左右滑操作/未读徽标）与单聊（文本消息收发/历史分页/已读未读/连接状态横幅），真实凭证的双端收发一致性由用户验收。

**Architecture:** 在 M1 的 session/DDP 订阅分发接口/RoomsSync/chats 表之上构建：列表层（Room Flow → 分类/排序纯函数 → LazyColumn 分段渲染）、消息层（RoomStreamManager 进房订阅 → MessageUpsert → 分页窗口 → SendOrchestrator 状态机）。数据流与 RN 一致：notify-user(500ms 合并)→chats 表→列表；房间流+history REST→messages 表→聊天页。

**Tech Stack:** 既有栈 + Compose Foundation 滑动手势、Coil（头像）。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（总纲；§4.2 M2 前置 6 项在本计划 Task 1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（下称 RN，file:line 基于调研报告 2026-09-15）

## Global Constraints

- **基线修正**：总纲写「左滑含删除」与 RN 实际不符——RN 无删除会话操作（移除仅由服务端 `removed` 事件驱动）。以 RN 为准：左滑=标未读+置顶切换、右滑=标已读。本计划提交时同步修订总纲 M2 行。
- 纯函数优先：分类/排序/预览/横幅策略/系统消息文案映射全部抽纯函数 + 单测移植 RN 行为（TDD）
- `status` 列只许 SendOrchestrator 写（RN persistMessagesFromRocketApi.applyApiFields 不含 status——DDP echo/历史拉取不得重置发送状态）
- 已读必须双表写（subscriptions + chats：`open=true, alert=false, unread=0, userMentions=0, groupMentions=0, ls=now`），否则 DDP 增量把 chats 写回假未读
- 所有 DB 回调先校验 active 库 == 当前 auth server（多组织隔离，RN `activeDbMatchesAuth`）
- **M3 边界（M2 不做）**：富文本编辑器 WebView、Markdown 全渲染（M2 正文单一样式 + mention 高亮 + 链接可点）、附件、回复引用、编辑/撤回、AI 触发、已读回执细节、线程、跳转消息、`room.firsUnread` 横幅（可选）
- 真凭证双端收发验收由用户执行（同 M1 分工）；agent 侧 = MockWebServer 全链单测 + 模拟器无凭证 UI 走查
- 凡用户可见文案走 i18n（key 沿用 RN 原名）；提交中文；每任务测试绿+提交
- 性能基线：消息列表 `LazyColumn(reverseLayout=true)` key=msgId、contentType 分型、首页 12/每批 5（对照 RN FlatList 参数）；列表行 data class 精确 equals（对照 RN reconcile 思路）

---

### Task 1: M2 前置收尾（总纲 §4.2 六项）

**Files:**
- Modify: `core/database/DatabaseManager.kt`（resetDatabase 内部切 `Dispatchers.IO` 或调用方下沉——以「logout 编排处不落主线程」为准，报告说明落法）、`domain/session/`（手动登出豁免）、`MainActivity.kt`/导航（testTag 基建）、`feature/org/`（orgCandidates 两段式）、`feature/main/ui/MainNavigationFlowTest.kt`（shutdown）
- Modify: `maestro/*.yaml`、Compose 关键节点加 `testTag`
- Test: 对应单测

**Interfaces:**
- Produces:
  - resetDatabase/登出链路全部文件操作脱离主线程（含 M1 已知偏差项闭环）
  - 组织切换中手动登出：`MainScreen.onLogout` 加 `OrgSwitchState.isInProgress()` 守卫（与总线收集器一致）
  - orgCandidates 两段式：缓存即时返回 + REST 到后刷新（StateFlow 两阶段发射，对照 RN MineMenu 行为）
  - `testTagsAsResourceId = true`（Compose 测试配置）+ Main/Login/Enterprise 关键节点 testTag；Maestro 断言换 testID（resource-id 匹配）
  - MainNavigationFlowTest 补 `server.shutdown()`
- [ ] 每项先测试后实现（或行为等价改写+验证），全量绿
- [ ] Commit: `chore(m2): 前置收尾六项（总纲 §4.2）`

---

### Task 2: 会话列表数据层（分类/排序纯函数 + Room Flow）

**Files:**
- Create: `feature/chatlist/ChatListSectioner.kt`、`feature/chatlist/ChatListSorter.kt`、`feature/chatlist/ChatListViewModel.kt`
- Test: `ChatListSectionerTest.kt`、`ChatListSorterTest.kt`（移植 RN 行为）

**Interfaces:**
- Produces:
  - 查询（RN useRoomListChats.ts:38-43）：`chats WHERE archived=false AND open!=true为false AND bot!=true ORDER BY room_updated_at DESC`（注意 bot 列在 chats 表是否存在——M0 八表转录时若未含需补列+schema v2 迁移，报告说明）
  - `buildRoomListSections(chats, currentUserId, searchText)`：三分——个人助手（`t=='d'` 且 uids 仅自己 / usernames 含 `agent.bot`）→ 待办（`todoCount>0`，段内四分：高优+草稿>高优>普通+草稿>普通）→ 普通频道；空段不返回；搜索按 name/fname 过滤
  - `compareChatsRoomList`（RN sortRoomListChats.ts:39-56）：置顶 `f` 优先（**不看 like**）→ 草稿 trim 非空 → 未读（`effectiveUnread = hideUnreadStatus?0:unread`；**双方都有未读落时间比较，单方有才按未读**）→ 时间 `max(lm??ts??updatedAt, tSearch)` 倒序
  - `ChatListViewModel`：Room Flow（`db.chatDao().observeList()`）→ 分类+排序 → `StateFlow<List<ChatListSection>>`；切组织时旧库回调不写新 UI（activeDbMatchesAuth）
- [ ] TDD：排序三分支（双未读落时间/单方未读/置顶/草稿/tSearch bump）≥10 用例；分类三分+待办四分 ≥6 用例
- [ ] Commit: `feat(chatlist): 会话分类与排序纯函数（对照 sortRoomListChats/groupChats）`

---

### Task 3: 列表行渲染（预览规则/时间/徽标）

**Files:**
- Create: `feature/chatlist/ui/ChatRow.kt`、`feature/chatlist/LastMessagePreview.kt`、`feature/chatlist/RoomListTime.kt`
- Test: `LastMessagePreviewTest.kt`、`RoomListTimeTest.kt`

**Interfaces:**
- Produces:
  - `resolveLastMessagePreview(chat, currentUserId)`（RN resolveLastMessagePreview.ts:92-124 全规则）：草稿优先→无 `lastMessage.u` 显「还没有消息」→特殊消息（pinned/jitsi_call_started/attachments image_url 分图片文件/docCloud/oncall `[语音通话]`/meeting_room/forwardMergeMessage 带或不带发送人前缀的精确规则）→普通消息发送人前缀（自己无前缀/rollback 无前缀/他人 `名字：`）→正文取 md AST 首个可见 block inlines（列表/quoted 取 `• item`），无 md 时 `msg.replace(\n,' ')`——**md AST 解析 M2 简化为「无 md 走纯文本」+ 预览仍需读 md 的场景按 RN lastMessagePreviewInlines 关键分支移植**
  - `formatRoomListTime`：刚刚/N 分钟/N 小时/N 天/超 7 天「M月d日」（zh/en 双语）
  - `ChatRow` Compose：头像+置顶星标、标题（未读加粗 `alert && !hideUnreadStatus`）、相对时间（未读主题色）、草稿前缀 `[roomItem_draft]` 优先、提及前缀 `[roomItem_someoneCalled]`（userMentions/groupMentions>0）、静音图标（`hideUnreadStatus||disableNotifications`，未读深蓝否则灰）、未读徽标三档宽度 16/24/28、>99 显 `99+`、`tunread>0` 只加粗不单独徽标
- [ ] TDD：预览规则 ≥12 用例（特殊消息每种+前缀规则+草稿优先）；时间 5 档
- [ ] Commit: `feat(chatlist): 列表行渲染与最后消息预览（对照 resolveLastMessagePreview）`

---

### Task 4: 列表操作（左右滑 + 已读未读/置顶端点）

**Files:**
- Create: `core/network/api/SubscriptionsApi.kt`（read/unread/favorite）、`feature/chatlist/ChatRowActions.kt`
- Modify: `ChatListScreen`（滑动手势）
- Test: `SubscriptionsApiTest.kt`、`ChatRowActionsTest.kt`

**Interfaces:**
- Produces:
  - `markRoomUnreadOnServer(rid)` = `POST /api/v1/subscriptions.unread {roomId}`（**本地不改，等 stream 回推**）
  - `setRoomFavorite(rid, favorite)` = `POST /api/v1/rooms.favorite {roomId, favorite}` + 本地 `chats.f` update
  - `markRoomRead(rid, now)` = `POST /api/v1/subscriptions.read {rid}` + **双表写**（subscriptions 与 chats：`open=true, alert=false, unread=0, userMentions=0, groupMentions=0, ls=now`）
  - 已读态判定 `subscriptionAppearsRead`：`!(archived!==true && open===true && (unread>0 || alert===true))`
  - 手势：左滑两钮（标未读——仅已读态显示；置顶切换）、右滑一钮（标已读——仅未读态显示）；侧滑壳常驻、按钮内容延迟挂载（RN 性能细节）
  - `removed` 事件（notify-user）：物理删 chats 行 + 退订该房间流 + 访问丢失处理（若正在房间内则退出到列表）
- [ ] TDD：三端点 wire + 双表写字段组 + removed 删行链
- [ ] Commit: `feat(chatlist): 左右滑操作与已读未读/置顶端点（对照 subscriptionSwipeActions）`

---

### Task 5: 连接状态横幅

**Files:**
- Create: `core/realtime/ConnectionBannerPolicy.kt`、`core/realtime/NetworkMonitor.kt`、`feature/chatlist/ui/ConnectionBanner.kt`
- Test: `ConnectionBannerPolicyTest.kt`

**Interfaces:**
- Produces:
  - `resolveConnectionBannerPresentation(networkOnline, phase, coldStartGrace)` 纯函数：networkOnline=false → 网络断开；connected → 隐藏；connecting → 正在重连（2s 防抖；冷启动 4s 宽限内有本地房间不显示）；disconnected → 未连接标题 + 「重试」按钮 → `manager` 手动重连（RN requestManualRealtimeReconnect 等价：checkAndReopen + 立即 connect）
  - `NetworkMonitor`：ConnectivityManager networkCallback → `StateFlow<Boolean?>`（RN NetInfo 等价）
  - 防抖实现：connecting 态持续 2s 才显示（Handler/coroutine delay）；文档标注与 RN 一致
- [ ] TDD：策略纯函数全分支 ≥6 用例；模拟器走查（飞行模式）
- [ ] Commit: `feat(chatlist): 连接状态横幅（对照 connectionBanner 全策略）`

---

### Task 6: 房间流订阅与消息落库

**Files:**
- Create: `core/realtime/RoomStreamManager.kt`、`core/messaging/MessageUpsert.kt`
- Modify: `RealtimeSessionManager`（M1 分发接口接第一个真实 handler——**主线程 hop**：handler 收到后切主线程/或 ViewModel 侧收 Flow，KDoc 契约兑现）
- Test: `RoomStreamManagerTest.kt`、`MessageUpsertTest.kt`

**Interfaces:**
- Produces:
  - `subscribeRoom(rid)`：三条订阅——`stream-room-messages {rid}`、`stream-notify-room {rid}/user-activity`（typing，M2 只注册不分发 UI）、`stream-notify-room {rid}/deleteMessage`；幂等（重复 sub 前 unsub）；退房 unsub；**重连后重订全部活跃房间流**（挂 manager 重连 tail）
  - `stream-room-messages` 回调：过滤 `args[0].rid==rid` → `persistRocketChatMessageFromUnknown` upsert messages → 通知已读 debounce
  - `MessageUpsert.applyApiFields`（RN persistMessagesFromRocketApi.ts:66-121 **逐字段对齐**）：string 化 JSON/ts 三态解析（秒级数字<1e12 ×1000/ISO 字符串/{$date:number}，失败回退 Date.now()）/boolean undefined 化/messageUpdatedAt=now；**status 不在字段表**；`_id` 校验、`lastWinsByKey` 去重、批量单事务（Room `@Upsert`+`@Transaction`）
  - 消息 DAO：按 rid 查询（`ORDER BY ts DESC LIMIT :limit`）、upsert
- [ ] TDD：字段收敛表逐字段 ≥15 用例（含 ts 三态/status 不写/JSON 字符串化）；订阅幂等/重订
- [ ] Commit: `feat(chat): 房间流订阅与消息落库（对照 roomStreams/persistMessages）`

---

### Task 7: 历史分页

**Files:**
- Create: `core/messaging/RoomHistoryRepository.kt`、`feature/chat/RoomMessagesViewModel.kt`
- Test: `RoomHistoryRepositoryTest.kt`、`RoomMessagesViewModelTest.kt`

**Interfaces:**
- Produces:
  - `loadRoomHistory(rid, latest?, count=50)`：`GET /api/v1/{prefix}.history`——prefix 映射 `c/l→channels, d→im, p→groups`，未知 t 返回空（不调网）；参数 `roomId` + `latest`（**本地最旧一条 ts 的 ISO 字符串**，用 `latest` 不用 `oldest`）；瞬时失败重试 2 次（1s/2s），失败返回 0 但**不置 hasMore=false**
  - `RoomMessagesViewModel`：本地窗口查询（LIMIT windowSize，50 起）+ 进房 `Promise.all 等价`（本地 + 远程 50，5s 兜底超时，同代只 settle 一次）；`loadEarlier`：`latest=最旧一条 ts`、返回 0 或 <count → 无更多、否则 windowSize+50；**noGrowthStreak≥2 启发式**（服务端有返回但本地窗口未增长连续 2 次 → 无更多）；下拉刷新 = 重拉最近 50 靠 upsert 去重
- [ ] TDD：prefix 映射、游标格式、无更多三分支、noGrowthStreak、进房并发
- [ ] Commit: `feat(chat): 历史分页（对照 usePaginatedRoomMessages/loadRoomHistory）`

---

### Task 8: SendOrchestrator（发送状态机）

**Files:**
- Create: `core/messaging/SendOrchestrator.kt`、`core/messaging/MessagesApi.kt`（chat.sendMessage）、`core/messaging/MessageStatus.kt`
- Test: `SendOrchestratorTest.kt`

**Interfaces:**
- Produces:
  - status 常量数值保持：`SENT=0 / QUEUED=1 / ERROR=2 / SENDING=3`；服务端消息 status 恒为 null
  - `enqueueTextMessage(rid, msg)`：`randomMessageId()` tempId → messages 表建 QUEUED 行（rid/msg/ts/u JSON/mentions）→ per-rid 串行队列 → dequeue
  - `sendOne`：标 SENDING → `POST /api/v1/chat.sendMessage {message:{_id, rid, msg}}`（`md` 省略即 wire 兼容纯文本；`_id` 幂等键）→ **serverId 迁移**（服务端可能忽略客户端 `_id`：serverId 行已存在则复制 status 删 temp 行；否则全字段迁建删 temp——防 DDP echo 双条）→ SENT
  - 失败：`success:false` 或 4xx → 直接 ERROR；网络/超时/5xx → 重试退避 1s/2s/4s，3 次后 ERROR；ERROR 行点击重发（作为新 job 推队）
  - 单例 + 用户/组织切换 `reset()`（M1 resetSendOrchestrator 等价）
  - **status 只许 Orchestrator 写**（约束在 Code review 层+单测钉：upsert 不触 status）
- [ ] TDD：状态机全迁移路径（含 echo 先到/后到两序）、重试/退避/上限、重发、per-rid 串行
- [ ] Commit: `feat(chat): SendOrchestrator 发送状态机（对照 SendOrchestrator/retryPolicy 含 serverId 迁移）`

---

### Task 9: RoomScreen UI（消息列表/输入/头部）

**Files:**
- Create: `feature/chat/ui/RoomScreen.kt`、`feature/chat/ui/MessageRow.kt`、`feature/chat/ui/SystemMessageText.kt`、`core/messaging/SystemMessageTexts.kt`、`feature/chat/ui/RoomHeader.kt`、`core/util/TimeFormats.kt`、`feature/chat/DraftRepository.kt`
- Test: `SystemMessageTextsTest.kt`、`TimeFormatsTest.kt`、Compose 测试（输入/发送/状态徽标）

**Interfaces:**
- Produces:
  - 消息列表：`LazyColumn(reverseLayout=true)` key=msgId、contentType 三型（普通/系统/日期分隔）、`日期分隔`（与上一条非同天插 separator）、空态（加载/`room_no_messages`）、滚到底按钮（跳转消息 M3 不做）
  - 消息行：**整体左对齐布局**（头像列 36dp 固定，无右对齐气泡）、自己 `#CCE6FF` 浅蓝底、发送者名（alias>`@loginName` / name/username）、头像 URL `/avatar/{username}?etag&size=36` 带鉴权参数、时间戳 `MM/DD HH:mm`，跨年 `YYYY/MM/DD HH:mm`，**后缀 `(UTC+8)` 本地偏移**、状态徽标（QUEUED/SENDING 菊花、ERROR 红叹号可点重发）、已读回执占位不渲染（M3）
  - 系统消息：`t → i18n key` 映射表（RN getInfoMessage.ts:40-166 **逐条移植**：带作者/不带作者两族、`r`/`ru`/`rollback-message` 等参数化文案、default→Unsupported_system_message、`load_chunk` 渲染 1px 空行、announcement 类型走公告样式）
  - mention 高亮（简化版）：`md` AST MENTION 节点 + `mentions` 数组匹配（`@all/@here` 群色、自己 mentionMeColor、他人 mentionOtherColor）；无 md 的纯文本不高亮（对照 RN AtMention 数据源语义）
  - 输入区：普通 TextField（多行）、发送按钮、**IME 组合态不触发草稿保存抖动**、草稿 debounce 1s + blur 即写 + 卸载 flush（写 chats 表 `draft_message`+`draft_message_plain`，发送后四列清）、发送走 SendOrchestrator（纯文本 msg，md 省略）
  - 头部：标题 `resolveRoomHeaderTitle`（fname||dname||name，路由参数兜底）、返回
- [ ] TDD：系统消息映射表逐条 ≥20 用例、时间格式、草稿时机；Compose 测试发送链（输入→点发→QUEUED 行出现→fake orchestrator SENT）
- [ ] Commit: `feat(chat): RoomScreen（消息渲染/系统消息映射/草稿/纯文本输入）`

---

### Task 10: 已读未读（房间内标读）

**Files:**
- Create: `feature/chat/RoomReadMarker.kt`
- Test: `RoomReadMarkerTest.kt`

**Interfaces:**
- Produces:
  - 进入房间即 `readMessages(rid, now, updateLastOpen=true)`（额外写 `lastOpen=now`）
  - 停留期间新消息落库 → **debounce 1000ms** → `readMessages(rid, 最新ts, false)`
  - 离开房间清 timer + 停止监听
  - 双表写复用 Task 4
- [ ] TDD：进房即读/防抖/离开清理
- [ ] Commit: `feat(chat): 房间内已读标记（进房即读+停留防抖）`

---

### Task 11: 主屏替换与路由串联

**Files:**
- Modify: `MainActivity.kt`/导航图（Main 占位 → ChatListScreen 真实主屏 + Room 路由）、`MainScreen.kt`（占位退役——OrgSwitchSheet/登出入口迁到 ChatListScreen 顶栏菜单）
- Test: 导航 Compose 测试（列表→房间→返回、横幅可见性）

**Interfaces:**
- Produces:
  - ChatListScreen = 连接横幅 + 分段 LazyColumn + 下拉刷新（触发 RoomsSyncRepository.sync 的 pull 全量等价）+ 顶栏（组织菜单 OrgSwitchSheet 入口/登出/search 入口占位——全局搜索 M5）
  - Room 路由（rid + 标题兜底参数）；进出房间生命周期挂 RoomStreamManager/ReadMarker/Draft flush
  - M1 的 onLoginSuccess → Main 保持不变（Main 现在是真实列表）
- [ ] 导航测试 + 模拟器走查（无凭证止步登录页；有会话恢复直进列表）
- [ ] Commit: `feat(app): 主屏替换为会话列表并串联房间路由`

---

### Task 12: M2 收尾

- [ ] 全量门禁 3 次（flake 率记录；RealtimeSessionManagerTest 预案照旧）
- [ ] Maestro：现有两流程回归 + 新增「列表断言」受限于无凭证——报告说明验证边界；testID 断言回归
- [ ] 《用户自测协议》M2 增补：双端文本收发、列表状态（未读/置顶/草稿/横幅）、分页、已读未读与 RN 对照步骤
- [ ] versionName 0.3.0；README 更新
- [ ] Commit + push: `chore(m2): 门禁冒烟与自测协议增补`

---

## 用户自测协议（M2 增补要点）

双端（RN 版 vs 原生 `.debug`）同一账号 side-by-side：列表三段分组与排序一致（含置顶/草稿/未读分支）、未读徽标数字与样式、最后消息预览（含特殊消息类型）、左滑右滑行为、连接横幅（飞行模式）、进房即读+停留标读、文本收发（含自己消息浅蓝/状态徽标/echo 不双条）、历史翻页、草稿保存与列表预览、removed 会话消失。

## M2 完成定义

1. 12 任务全部 SDD 闭环
2. 全量单测 + lint 绿；Maestro 回归通过；模拟器 UI 走查到请求边界
3. `.debug` APK + 自测协议 M2 增补交付
4. 用户完成双端收发一致性验收后 M2 关闭
