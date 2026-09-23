# M5 搜索+presence+设置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的全局搜索/房间内搜索（含消息跳转高亮）、presence 在线状态全链、设置/资料/状态编辑，并落地 M4 遗留的 settings.public 同步（解锁全部门控）/全局角色刷新/useRealName 接线/i18n 语言切换。

**Architecture:** settings.public 是 M5 的解锁基石（T1 先行）——Enterprise_Name/UI_Use_Real_Name/partners 门控/Agent_Bot_List 全靠它。presence 三层（store+批量订阅+username 解析）替换 M2/M4 的降级渲染。搜索域新建 feature/search 包，消息跳转高亮（loadSurroundingMessages DDP + chunk 占位 + 高亮态）同时是 M6 深链的前置。

**Tech Stack:** 既有栈；i18n 运行时切换用 per-app locale（AppCompatDelegate.setApplicationLocales / API33 LocaleManager）——不抄 zustand。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（§4.5 M4 遗留六项在 T1/T2/T9/T12 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN）；调研报告 2026-09-23（20 条坑清单在内）

## Global Constraints

- **基线事实**：ProfileScreen 在 RN 是只读（头像上传/姓名编辑 RN 未实现）——M5 对齐只做展示+二维码+状态编辑，不发明端点（头像上传如需走 legacy setAvatarFromService，推 M6+ 评估）
- links tab 是启发式（RN 注释自认临时方案）——照抄勿过度设计；mentions tab 服务端 query+客户端过滤的双层语义保持
- presence 索引协议（args[0] 索引映射 USER_STATUSES）、subscribeRaw added 数组去重、isRocketChatUserId 判定规则逐条移植
- spotlight contact 行 rid 是 username 回退——进房必须走 openDirectMessage 链（knownRid 仅本地确证后采用）
- 跳转高亮只响应路由参数变化（防死循环）；chunk 占位消息 1px；双 bump 防 DDP 覆盖
- UI_Use_Real_Name 缺省 true（表读缺行回 true——现 hardcoded true 恰是正确缺省）
- 纯函数优先 TDD；i18n key 沿用 RN；提交中文；M3 教训纪律（参数新增列全下游三层）
- M5/M6 边界：推送/深链/自更新弹窗归 M6（T6 的 jumpToMessageId 通路是深链前置，接口留好）；M5/M7 边界：Labor/泛微/石墨/agents 管理归 M7（InAppWeb 只做壳）
- 用户验收分工同前

---

### Task 1: settings.public 同步（解锁基石）

**Files:**
- Create: `core/settings/ServerSettingRegistry.kt`（86 id → 列类型映射，五关键 id 对照 RN serverSettingRegistry.ts）、`core/network/api/SettingsPublicApi.kt`（GET settings.public 50/批）
- Modify: `RealtimeSessionManager`（bootstrap "public settings" 步实现 + stream-notify-all public-settings-changed 增量——**核对 M1 订阅清单是否已含 stream-notify-all**，缺则补订阅）、`SettingDao`（upsertAll + observeById Flow）
- Test: `ServerSettingRegistryTest.kt`、`SettingsPublicApiTest.kt`

**Interfaces:**
- Produces: `syncPublicSettings()`（分批 50 → prepare 归一（布尔/串/数/数组列）→ upsert，单条失败跳过不阻断）；`publicSettingsChanged` 流消费（args[1]={_id,value} 单条 upsert）；`fun SettingDao.observe(id): Flow<SettingEntity?>`；`usePublicSettingBoolean` 等价（读+缺省）
- [ ] TDD：注册表类型映射/分批 wire/归一边界（布尔串"true"/数组 JSON）≥8 用例
- [ ] Commit: `feat(settings): settings.public 同步与流式增量（86 键注册表）`

---

### Task 2: 全局角色运行时刷新（M4 遗留 §4.5-1）

**Files:**
- Create: `domain/session/RoleRefresher.kt`
- Modify: `RealtimeSessionManager`（bootstrap user roles 步）、`MainActivity`（ON_RESUME）、RoomInfo/RoomMembers 装配（focus）
- Test: `RoleRefresherTest.kt`

**Interfaces:**
- Produces: `refresh(force: Boolean = false)`——users.info self → parseUserRoles（已有）→ AuthSessionStore 合并写；30s 节流 + inflight 去重；三触发点（bootstrap force/ON_RESUME/focus）
- [ ] TDD：节流/inflight/force/合并 ≥6 用例
- [ ] Commit: `feat(auth): 全局角色运行时刷新（对照 syncCurrentUserRoles）`

---

### Task 3: presence 基建（三层）

**Files:**
- Create: `domain/presence/PresenceStore.kt`、`PresenceBatcher.kt`（2s 防抖+users.presence 逗号串+subscribeRaw added 增量去重）、`PresenceStreamParser.kt`（索引映射）、`UsernameIdResolver.kt`（400ms 防抖+isRocketChatUserId 判定移植）
- Modify: `RealtimeSessionManager`（占位步实现+stream-user-presence 分发）、消费点接线（MessageRow/TeamScreen/MemberProfile/RoomAvatar 单聊 peer——**resolveDirectPeerUserId 链落地后删 M4 死代码**）
- Test: `PresenceStreamParserTest.kt`、`UsernameIdResolverTest.kt`（isRocketChatUserId 全规则）、`PresenceBatcherTest.kt`

**Interfaces:**
- Produces: `status(userId): StateFlow<String?>`（merge store+fallback 双层）；绿点仅 online/away、名片 isOnline 文字态仅 online（两判定分离）；bot 剔除
- [ ] TDD：索引映射越界回退/added 去重/id 判定规则全条/双层降级 ≥12 用例
- [ ] Commit: `feat(presence): 在线状态三层基建（批量订阅/索引解析/username 解析）`

---

### Task 4: useRealName 接线（M3 遗留）

**Files:**
- Modify: `MessageRow.kt`（buildMessageHeaderDisplay 读 SettingEntity 缺省 true）、InlineNodes/MarkdownNodes mention 路径同参
- Test: 扩展用例（true/false 两态渲染名）

**Interfaces:** Produces: 单参贯通四消费点（发送者名/提及/预览已接 M4/引用）
- [ ] Commit: `feat(chat): UI_Use_Real_Name 接线（缺省 true）`

---

### Task 5: 全局搜索

**Files:**
- Create: `feature/search/GlobalSearchViewModel.kt`、`ui/GlobalSearchScreen.kt`（4 tab+分区折叠 3 条预览+查看更多）、`ui/GlobalSearchMessageDetailScreen.kt`（chat.search 分页 50/_id 去重 append/hasMore=addedCount==0 止）、`core/network/api/FilesSearchApi.kt`（files.search cursor 分页多形状）、`feature/search/ui/HighlightText.kt`（keepMatchVisible 前后缀省略）
- Modify: `SpotlightApi`（fetchGlobalSearch 全参/fetchMessagesFull isMessageFull+offset）、`ChatListScreen` 占位接线（qa-room-list-search → navigate）
- Test: `GlobalSearchViewModelTest.kt`（三段查询/防抖 300/竞态守卫/离线回退本地分区）、`HighlightTextTest.kt`

**Interfaces:**
- Produces: 4 tab 数据流（all 预览 3+折叠；messages 完整列表一次性；files cursor 分页）；点击行为（contact→openDirectMessage【rid username 回退坑】/房间→T8 bump+进房/消息→Detail 屏→T6 跳转）；spotlight 结果映射（users/rooms/usersInRooms 匹配成员 subtitle/messages.rooms N 条 subtitle）
- [ ] TDD：三段参数数组逐位/映射分组/去重止步/高亮分段 ≥12 用例
- [ ] Commit: `feat(search): 全局搜索（4 tab/spotlight 三段/文件分页/高亮）`

---

### Task 6: 消息跳转高亮（M6 深链前置）

**Files:**
- Create: `domain/chat/MessageJumpResolver.kt`（本地 find→chat.getMessage）、`feature/chat/MessageJumpController.kt`（plan 三路 not-found/navigate-room/scroll/fetch-surrounding + 超时 15s + loading 防抖 300ms + 取消）
- Modify: `core/network/api`（loadSurroundingMessages DDP method {_id,rid},50）、`RoomMessagesViewModel`（jumpMessages 替换源+PREVIOUS/NEXT_CHUNK 占位+loadEarlierInJumpMode+exitJump 回实时）、`RoomScreen`（jumpToMessageId 路由参数+LoadingOverlay 可取消+isJumpMode 分流）、`MessageRow`（高亮底色 #FFF7D6）
- Test: `MessageJumpControllerTest.kt`（plan 三路/超时/取消/chunk 占位/防死循环——只响应参数变化）

**Interfaces:**
- Produces: `jumpTo(messageId)` API（跨房重导航 via 路由参数）；占位消息 1px 渲染接 M2 的 load_chunk 通路；发消息/下拉刷新 exitJump
- [ ] TDD ≥10 用例
- [ ] Commit: `feat(chat): 消息跳转高亮（loadSurroundingMessages/chunk 占位/15s 超时）`

---

### Task 7: 房间内搜索

**Files:**
- Create: `feature/search/RoomSearchViewModel.kt`（6/4 tab by 房型；messages chat.search notIncludeFile:true 分页；files/media {prefix}.files regex query；mentions mentions._id query+客户端过滤（空页 addedCount==0 止）；members 本地缓存过滤；links 启发式；**加密房本地 LIKE**）、`ui/RoomSearchScreen.kt`（消息 tab 复用 MessageRow）
- Modify: RoomScreen header 搜索入口（M2 留的锚点）
- Test: `RoomSearchViewModelTest.kt`（各 tab wire/分页/竞态/加密房分支）

**Interfaces:**
- Produces: tab 结构 getVisibleRoomSearchTabs 等价（群 6/单聊 4）；点击（消息→goBack+T6 跳转/文件→DocPreview/媒体→UrlMediaPreview【M3 组件复用】/成员→openDirectMessage）
- [ ] TDD ≥12 用例
- [ ] Commit: `feat(search): 房间内搜索（6 tab/加密房本地/启发式 links）`

---

### Task 8: tSearch bump + 搜索收口

**Files:**
- Create: `domain/chat/ChatBumper.kt`（tSearch+roomUpdatedAt 写+进房/离房双 bump+800ms 重试）
- Modify: T5/T7 点击接线
- Test: `ChatBumperTest.kt`（双 bump/重试/DDP 覆盖防护）

**Interfaces:** Produces: bump 通路（M2 排序的 tSearch 分支激活）
- [ ] Commit: `feat(search): tSearch bump 与搜索点击收口`

---

### Task 9: 设置页族 + 通知细项 + 状态编辑 + MyCard 迁正

**Files:**
- Create: `feature/settings/ui/SettingsScreen.kt`（五分区：通用【语言三段 T11/字体/浏览器/头像样式 photo|letter→users.setPreferences 乐观回滚】/通知【电池优化状态行】/关于【版本/服务器版本 GET /api/info 免登录/检查更新→M6 前先展示+跳浏览器】/法律【LEGAL_URLS】/账户【guest 删号判定/清除缓存=teardown+删库+重 bootstrap/退出/退出其他设备 users.removeOtherTokens】）、`MessageSettingScreen.kt`（showImageSummary/showDocumentSummary 两开关 setPreferences 乐观）、`StatusEditScreen.kt`（maxLength 120+users.setStatus+mergeStatusText）、`core/network/api/UserPreferencesApi.kt`
- Modify: `MyCardScreen`（Enterprise_Name 行接 T1；入口从顶栏迁 ProfileScreen——**新建只读 ProfileScreen** 对齐 RN：头像/姓名只读/二维码/邮箱/设置入口）、RoomInfo 通知细项（saveNotification 三键参数面：muteGroupMentions 单键+解耦——对照 RN 参数就三键）、MineMenu/顶栏菜单挂点调整
- Test: `UserPreferencesApiTest.kt`、设置页冒烟（分区/电池行/清除缓存链）

**Interfaces:**
- Produces: saveNotification 三键全参（{disableNotifications, muteGroupMentions, hideUnreadStatus} '1'/'0' 字符串值）；clearLocalCache（teardown→resetDatabase→re-bootstrap，generation 防竞态复用）
- [ ] TDD：setPreferences wire/三键通知/saveNotification/清除缓存序 ≥10 用例
- [ ] Commit: `feat(settings): 设置页族与通知细项（对照 SettingsScreen 五分区）`

---

### Task 10: InAppWeb 壳

**Files:**
- Create: `feature/web/InAppWebScreen.kt`（最小版：needAuth 白名单三域硬编码+users.externalToken 换 code+同源 cookie 注入+返回栈 canGoBack 域外判定；**泛微/石墨/WPS/会议拦截归 M7**）、`core/network/api/ExternalTokenApi.kt`
- Modify: MemberProfile 简历链接（ACTION_VIEW→navigate InAppWeb）、openLink 等价工具
- Test: `ExternalTokenApiTest.kt`、白名单判定单测

**Interfaces:**
- Produces: 路由参数 {url,title?,needAuth?,source?}；白名单命中自动 needAuth；失败降级裸 URL
- [ ] TDD：白名单三域/换 code wire/降级 ≥6 用例
- [ ] Commit: `feat(web): InAppWeb 壳（needAuth 白名单/凭证注入/返回栈）`

---

### Task 11: i18n 语言切换

**Files:**
- Create: `core/i18n/LocaleController.kt`（AppCompatDelegate.setApplicationLocales + MMKV 持久化 "system"/"en"/"zh"，null 不设跟随系统）
- Modify: LoginScreen（AuthHeaderLanguageSwitch 等价下拉——M1 遗留挂件）、SettingsScreen 语言三段（T9 分区已留）
- Test: `LocaleControllerTest.kt`（三态/持久化往返/系统跟随解析）

**Interfaces:** Produces: 运行时切换全 app 生效（Resources 自动重建）
- [ ] Commit: `feat(i18n): 语言切换（per-app locale 三态）`

---

### Task 12: M4 遗留 polish 收编

- [ ] 表情 update 键清空（缺省空数组走 replaceAll 一行——服务端全删清表语义）；resolveDirectPeerUserId 死代码删除（T3 落地后）；Dimens.kt 删除；Color 字面量搭车收敛（新屏用 token，存量 M4 六屏在改动时顺带）
- [ ] Commit: `chore(m5): M4 遗留 polish 收编（表情清空/死代码/Dimens）`

---

### Task 13: M5 收尾

- [ ] roomlist_swipe Maestro 补登录后流程（若用户已验收登录——否则边界如实）；门禁×3 flake 率；模拟器走查
- [ ] 自测协议 M5 增补（第十二节）：全局搜索四 tab/房间内搜索六 tab/跳转高亮/presence 绿点/设置五分区/语言切换/状态编辑/通知细项/InAppWeb 简历——已知差异（头像上传 RN 未做对齐只读/links 启发式/mentions 客户端过滤空页等）
- [ ] versionName 0.6.0；README
- [ ] Commit + push: `chore(m5): 收尾与自测协议增补`

---

## M5 完成定义

1. 13 任务全部 SDD 闭环
2. 全量单测+lint 绿；门禁×3；模拟器走查
3. `.debug` APK + 协议 M5 增补交付
4. 用户完成双端验收后 M5 关闭
