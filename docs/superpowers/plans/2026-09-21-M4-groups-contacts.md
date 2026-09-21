# M4 群组+联系人 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的群组管理（RoomInfo/成员/角色/移除/公告/改名/退出）与联系人域（Team 双树通讯录/成员名片/我的二维码/发起 DM/建群选人），补齐 M3 遗留的转发组织树 tab 与预览 mention 显示名。

**Architecture:** 在 M1-M3 基础上：权限层（permissionsStore 等价 + hasRoomPermission 纯函数 + `permissions.listAll` 同步消费 M1 已订阅的 permissions-changed 流）、群组 API 层（Meteor saveRoomSettings 类 + members/v2 复用 M3）、UI 层（RoomInfo 系列屏 + TeamScreen + MemberProfile/MyCard + CreateChannelMembers 选人器——选人器同时服务建群/加人/转发组织树补全）。

**Tech Stack:** 既有栈；二维码用 ZXing（`journeyapps:zxing-android-embedded` 或 core 生成位图——报告选型）。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（§4.4 M4 前置 4 项在 Task 1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN）；调研报告 2026-09-21

## Global Constraints

- **基线事实**：ContactsScreen 是未挂载占位（「通讯录」入口实路由到 TeamScreen）——M4 只做 TeamScreen，不做 ContactsScreen
- 纯函数优先（权限判定/成员解析/公告解析/选人判定全 TDD）；文案 i18n（key 沿用 RN）；提交中文；每任务测试绿+提交
- **M3 教训纪律（§4.4）**：参数新增任务必须列全下游消费者（签名/wire/测试断言三层）
- M4/M5 边界：RoomInfoSettings 的通知设置细项（saveNotification 全参数面）归 M5；RoomMeeting 归 M8；POTA/OKR（OtkrSection）**跟随 RN——有权限门控，M4 做占位判断**（getOtkrCanQuery 无权限则不显示，有权限的场景留 M5+ 评估——如实入册）；ProfileScreen 编辑（头像/昵称）归 M5
- 用户验收分工同前：MockWebServer 全链 + 模拟器走查；真实群操作（建群/踢人/设角色）由用户 side-by-side

---

### Task 1: M4 前置收尾（总纲 §4.4 四项）

**Files:** 按 §4.4：`feature/chat/forward/ForwardSelectScreen`（组织树 tab 接真数据源）、`feature/chatlist/LastMessagePreview`（mention 显示名）、`core/database/`（mentions 列评估）、表情 DAO 清退、计划文档补流程纪律条款引用

**Interfaces:**
- Produces: ① 转发组织树 tab（M3 占位最近会话 → 本任务接 `hrm/v2.users.list` 部门树——依赖 Task 6 的 contacts 数据源，**若 Task 6 未到则本任务先做其余三项，组织树接线挪 Task 6 完成后**，报告说明顺序）；② 预览 mention 显示名（ChatEntity 加 `mentions` 列【Room schema v2 迁移 + MessageUpsert 已写 mentions——chats 表的 lastMessage 里有 mentions 字段吗？**读 RN schema.ts 核实 chats 行是否需要该列**，若 lastMessage JSON 内含 mentions 则免迁移直接解析，报告定案】）；③ 表情 DAO 清退（sync 改全量替换语义或 remove 集处理——对照 RN store 整表替换）；④ §4.4 纪律条款在计划头引用
- [ ] 每项 TDD → Commit: `chore(m4): 前置收尾四项（总纲 §4.4）`

---

### Task 2: 权限层（permissionsStore + hasRoomPermission + listAll 同步）

**Files:**
- Create: `core/permissions/PermissionsStore.kt`（StateFlow<Map<String,List<String>>>）、`core/permissions/HasRoomPermission.kt`（纯函数）、`core/network/api/PermissionsApi.kt`（permissions.listAll）
- Modify: `RealtimeSessionManager`（M1 已订阅 permissions-changed 流——接消费：patch store）
- Test: `HasRoomPermissionTest.kt`（RN hasRoomPermission.test.ts 移植）、`PermissionsStoreTest.kt`

**Interfaces:**
- Produces:
  - `hasRoomPermission(permission, userRoomRoles, globalRoles, store, fallback)`: Boolean——合并房间角色+全局角色对照 permission→roles 映射；`getDefaultPermissionMapping()` 兜底（set-owner:[owner]/set-moderator:[owner,moderator]/remove-user:[owner,moderator,admin]/...对照 RN 逐条）
  - `canEditRoomSettings(chat)`: edit-room 权限 + 兜底 [owner,moderator,admin]；prid 讨论房恒可编辑
  - `syncPermissions()`：`permissions.listAll` 全量（固定 id 清单：edit-room/add-user-to-joined-room/remove-user/set-owner/set-moderator/set-ghost-owner/delete-c/delete-p——对照 RN syncPermissions.ts）→ store；`stream-notify-logged` permissions-changed 事件 → store patch（M1 分发接口消费第一个权限级 handler）
- [ ] TDD：映射合并/兜底/角色并集/移除房主排除 ≥10 用例
- [ ] Commit: `feat(permissions): 权限层（listAll 同步 + 流式更新 + hasRoomPermission）`

---

### Task 3: 群组 API 层（Meteor settings 类 + 角色操作）

**Files:**
- Create: `core/network/api/RoomSettingsApi.kt`（saveRoomSettings/saveNotification/favorite/leave/like/roles/toggleOwner/Moderator/GhostOwner/removeUser/addUsers/removeDepartment）
- Test: `RoomSettingsApiTest.kt`

**Interfaces:**
- Produces（端点/参数对照调研报告 §3 逐字）:
  - `postSaveRoomSettings(rid, settings)`（Meteor method.call 形态——roomName/roomAnnouncementData 等 key）；`postLeaveRoom`（`{channels|groups|im}.leave` 按 t 前缀）；`getRoomRoles`（`{prefix}.roles`）
  - `postToggleRoomOwner/Moderator`（addOwner/removeOwner/addModerator/removeModerator 按 t 前缀）；GhostOwner API 落地但**不接 UI**（对照 RN：API 层存在未接线——如实入册）
  - `postRemoveUserFromRoom`（**team 主房先 `teams.removeMember` 再 `{prefix}.kick`**——对照 RN 双调用）；`postAddUsersToRoom`（Meteor addUsersToRoom + isShareRecord）；`postRemoveDepartmentFromRoom`（local.removeGroupUsersToRoom）
- [ ] TDD：wire 逐字段（含 t 前缀映射/team 双调用/Meteor 信封）≥14 用例
- [ ] Commit: `feat(room): 群组设置与角色 API（对照 roomSettings.ts 逐端点）`

---

### Task 4: RoomInfoScreen（信息页/静音置顶/退出）

**Files:**
- Create: `feature/roominfo/ui/RoomInfoScreen.kt`、`feature/roominfo/RoomInfoActions.kt`（mute/pin 乐观态）、`feature/roominfo/RoomUsageOptions.kt`（appiaUsage 分类）
- Modify: 导航图（RoomInfo 路由 + RoomScreen 头部点击接已有锚点）
- Test: `RoomInfoActionsTest.kt`、Compose 冒烟

**Interfaces:**
- Produces（对照 RoomInfoScreen/index.tsx 全结构）:
  - 非直聊：成员网格（≤20 槽含加减槽，`appia/room/members/v2` 复用 M3 MentionSource 解析）→ 加人（T8 选人器 addToRoom）/移除（T5 remove 模式）/更多成员（T5 list 模式）；信息卡（房名行→T7 改名【canEditRoom 门】/公告预览行→T6/会议行占位 M8）；设置卡（**分类行仅 c|p**【canEditRoomUsage 门，RoomUsageSettingDrawer 选项 Room_Sort_COP/Organize/Meeting/Others】/静音 toggle/置顶 toggle）；退出按钮（markPendingSelfLeave → postLeaveRoom，失败清标记）
  - 直聊：对方头像卡（→ T6' MemberProfile）/+ 按钮（选人器 peer 预选）/会议行占位
  - mute/pin 乐观：本地先写（hideUnreadStatus+disableNotifications / chats.f）失败回滚（对照 roomInfoSettingsActions.ts）
- [ ] TDD：乐观回滚/槽位计算（20−加−减）/usage 门 ≥8 用例 + 冒烟
- [ ] Commit: `feat(roominfo): 房间信息页（成员网格/静音置顶乐观/退出）`

---

### Task 5: RoomMembersScreen（成员列表/角色操作/移除）

**Files:**
- Create: `feature/roominfo/ui/RoomMembersScreen.kt`、`core/chat/RoomMemberActions.kt`（buildMemberActionSheetItems 等价）、`core/chat/ResolveMemberRoleTag.kt`
- Test: `RoomMemberActionsTest.kt`（RN 权限判定移植）、`ResolveMemberRoleTagTest.kt`

**Interfaces:**
- Produces:
  - 双模式：list（默认）/remove（checkbox 多选+头部移除钮）；数据 members/v2（org 块分组：本地块首/多块 orgHeader/部门分组行）+ getRoomRoles 合并
  - 成行长按 ActionSheet：发消息（`im.create` 链复用 T9' openDirectMessage）/设/撤 owner/设/撤 moderator/移除——全部 `hasRoomPermission` 门（T2）+ **owner 行不可移除 + joinType 含 'user' 才可移除**（canRemoveMemberRow 本地判定对照 RN）；部门行长按 → removeDepartment
  - 批量移除：并行 per-user remove + 每 dep 一个 removeDepartment → goBack
  - 角色徽标：owner→'owner'/moderator|ghost-owner|admin→'admin'（resolveMemberRoleTag/mergeMemberRoles 对照）
- [ ] TDD：动作项权限全集/移除排除/角色合并 ≥12 用例
- [ ] Commit: `feat(room): 成员管理页（角色操作/移除/部门分组）`

---

### Task 6: 通讯录数据源 + TeamScreen（双树）

**Files:**
- Create: `feature/contacts/ContactsRepository.kt`（hrm/v2.users.list + parseContactsMap）、`feature/contacts/TeamModels.kt`（teamModels.ts 移植：TEAM_ROOT_IDS {pmt:'EMT-0',l1d:'EMT-1328'}/buildTeamFlatList/就业类型计数）、`feature/contacts/ui/TeamScreen.kt`
- Modify: ChatListScreen 顶栏菜单（「通讯录」入口——对照 RN MineMenu :329）
- Test: `ContactsRepositoryTest.kt`、`TeamModelsTest.kt`（RN teamModels.test.ts 移植）

**Interfaces:**
- Produces:
  - `fetchContactsMap` → `hrm/v2.users.list` 解析（parseContactsMapResponse 对照）；状态源 StateFlow（对照 useContactsStore）
  - TeamScreen：根视图（PMT/L1D tab/我的卡/公司头 logo+Enterprise_Name/部门行【图标按 tagLabel PMT/PDT/L1D/L3D】/footer 总数+就业类型计数）；子部门视图（buildTeamFlatList 扁平：子部门+直属成员）；搜索（PMT+L1D 合并去重/部门按名/成员 memberMatchesQuery）；成员点击→T6' 名片；部门点击→push 子视图
  - presence 预取（requestUserPresence/scheduleResolve 复用 M1/M2 既有）
  - **完成后回接 T1 的转发组织树 tab**（数据源就绪）
- [ ] TDD：模型构建/搜索合并去重/计数 ≥10 用例
- [ ] Commit: `feat(contacts): 通讯录双树（hrm 数据源 + TeamScreen）`

---

### Task 7: 公告 + 房名编辑

**Files:**
- Create: `feature/roominfo/ui/RoomAnnouncementScreen.kt`、`core/chat/RoomAnnouncements.kt`（解析）、`feature/roominfo/ui/RoomChannelNameEditScreen.kt`
- Test: `RoomAnnouncementsTest.kt`（RN roomAnnouncements.test.ts 移植）、改名屏冒烟

**Interfaces:**
- Produces:
  - 公告解析：`parseMainAnnouncements(announcement, announcements)`（plain/JSON/双重编码/`` 嵌入文件拆分/meeting|summary 分类——对照逐条）；进房 `rooms.info` 刷新回写 chats 行（announcement/announcements 列）
  - 公告屏：列表/编辑双态；发布/编辑/删除 `saveRoomSettings {roomAnnouncementData}`（删除 `{_id, type:'delete'}`）；文件上传（announcement.bot 专用端点 `/api/v1/admin/file/upload/announcement.bot` + file-proxy 改写）；渲染 markdown（复用 M3 链）+ 图片/文件（复用 M3 附件渲染）；编辑门 useCanEditRoomSettings（T2）；**直聊不可传文件**
  - 改名屏：fname/dname 编辑 max80 → `saveRoomSettings {roomName}`；`error-invalid-room-name` 友好 Alert
- [ ] TDD：公告解析全形态 ≥10 用例
- [ ] Commit: `feat(room): 公告与房名编辑（解析对照 roomAnnouncements）`

---

### Task 8: 选人器 CreateChannelMembers（建群/加人/转发共用）

**Files:**
- Create: `feature/contacts/ui/CreateChannelMembersScreen.kt`、`core/chat/CreateChannelSelection.kt`（判定纯函数）、`core/network/api/ChannelsApi.kt`（channels.create/im.create 复用 M3 openDirectMessage）
- Test: `CreateChannelSelectionTest.kt`（RN createChannelSelection 移植）、`ChannelsApiTest.kt`

**Interfaces:**
- Produces（对照 1800 行屏的核心语义，**允许 UI 简化但数据/判定/wire 全保真**）:
  - 双 intent：create（新建频道自身预选）/addToRoom（既有成员预检但排除出底栏）；双模式 members（recent/PMT/L1D/partners【服务端设置门 Appia_Show_External_Partners】/agents【ClawAgents+编辑禁用恢复——**agents 管理动作 M4 简化为仅选择**，管理留 M5+，如实入册】）/org（depId 部门树多选）
  - 搜索：spotlightv2 users-only（复用 M3 SpotlightApi 形态）；部门全选 BFS 后代（buildDeptDescendantUsernames）；checkbox 三态（checked/unchecked/indeterminate）
  - 判定：`canConfirmCreateChannel`/`canConfirmAddToRoom` 纯函数（对照）；确认 create→`channels.create {users+depIds+all}`、add→Meteor addUsersToRoom
  - **页面保真 tab 常驻（保滚动）**：Compose 用 saveable state 等价——报告说明方案
- [ ] TDD：判定/BFS/三态/wire ≥12 用例
- [ ] Commit: `feat(contacts): 选人器（建群/加人/部门树多选）`

---

### Task 9: MemberProfile + MyCard + 发起 DM

**Files:**
- Create: `feature/contacts/ui/MemberProfileScreen.kt`、`feature/contacts/ui/MyCardScreen.kt`、`core/chat/OpenDirectMessage.kt`、`core/network/api/MemberProfileApi.kt`（users.info）、`core/network/api/QrcodeApi.kt`（qrcode.query 7 级回退）
- Test: `OpenDirectMessageTest.kt`、`QrcodeFallbackTest.kt`

**Interfaces:**
- Produces:
  - MemberProfile：users.info 数据（头像 getTeamUserAvatarUri 鉴权）；操作：发消息（openDirectMessage：本地 DM 查找→im.create→navigateToRoom reset）/语音通话按钮（**占位禁用 M10 接**）；个人信息行（email/supervisor leaderNames[0]/简历链接 canViewResume 门→InAppWeb）；POTA/OKR 节（getOtkrCanQuery 无权限不显示——占位入册）
  - MyCard：二维码（qrcode.query **7 级回退策略**对照 fetchUserQrcode.ts 逐级）；ZXing 生成/展示；保存相册（Android <29 WRITE_EXTERNAL_STORAGE + 拒绝映射）；入口 ProfileScreen（M5 域——本任务先挂 ChatList 顶栏菜单可替，报告说明）
  - openDirectMessage 链：knownRid 校验→本地 chats 扫描→im.create（对照三段）
- [ ] TDD：DM 链三段/qrcode 回退次序 ≥8 用例
- [ ] Commit: `feat(contacts): 成员名片/我的二维码/发起 DM 链`

---

### Task 10: roomAccessLoss 收口 + M4 收尾

- [ ] roomAccessLoss（lib/chat/roomAccessLoss.ts 对照）：被踢/自退/别处退出检测 → 栈清理（RoomInfo/RoomMembers/Announcement/NameEdit 涉及 rid 全 pop——对照 stackInvolvesRid 清单）
- [ ] 全量门禁×3（flake 率）+ Maestro 回归 + 模拟器走查（无凭证边界如实）
- [ ] 自测协议 M4 增补：群信息/成员管理（角色/移除——**需双账号**）/公告编辑/改名/退出/通讯录浏览搜索/名片/发 DM/建群加人——已知差异清单（agents 管理简化/OKR 占位/语音禁用/ContactsScreen 不做等）
- [ ] versionName 0.5.0；README
- [ ] Commit + push: `chore(m4): 收尾与自测协议增补`

---

## M4 完成定义

1. 10 任务全部 SDD 闭环
2. 全量单测+lint 绿；Maestro 回归；模拟器走查
3. `.debug` APK + 协议 M4 增补交付
4. 用户完成群操作双端验收后 M4 关闭
