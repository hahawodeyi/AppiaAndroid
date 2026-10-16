# M11 功能 Checklist——基础域（M1-M4）

> RN 锚 = 冻结基线 `c92e3df`（`git -C ../appiaMobile show c92e3df:<path>` 可复核）。
> 状态三值：✅已实现（AA 文件锚 + 里程碑终审）/ ❌缺口（入结构化缺口清单）/ 🔶授权差异（引用协议已知差异节号或总纲裁定）。
> 协议差异节 = `docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`（四/六/八/九/十/十一节）。
> 本文件 T2 建档（基础域 M1-M4）；T3/T4 追加扩展域与 RTC 域。

## 一、M1 认证与多组织

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 企业码验证 + 服务器列表（EnterpriseCodeScreen/index.tsx） | ✅ | M1 终审；feature/login/ui/EnterpriseCodeScreen.kt + EnterpriseVerifyApi.kt |
| 企业码失败/空输入 Alert + i18n 文案 | ✅ | M1 终审；EnterpriseCodeScreen.kt（enterprise_verifyFailedTitle 等） |
| envHost 开发后门（label 长按切 env 输入，__DEV__） | ✅ | M1-T3；EnterpriseCodeScreen.kt（BuildConfig.DEBUG 门控，KDoc 注记） |
| 登录页服务器下拉（selected ?: first，兜底 appia.cn） | ✅ | M1-T5；feature/login/ui/LoginScreen.kt |
| 服务器 label 连点 10 次/500ms 切手输 URL | ✅ | M1-T5；LoginScreen.kt（KDoc RN:168-181） |
| SMS 登录主模式（手机号 + 区号 + 验证码） | ✅ | M1 终审；LoginScreen.kt + AuthApi.kt（loginSendCode/login） |
| 发码 60s 冷却 + smsCodeSendConsumedRef 防重 | ✅ | M1-T5；LoginScreen.kt（冷却态 Compose 测试） |
| 滑块验证码 SMS 底部弹层 WebView（verification/sms-login） | ✅ | M1-T5；SmsCaptchaBottomSheet.kt + CaptchaWebView.kt |
| 密码模式内嵌滑块 WebView（verification/sms） | ✅ | M1-T5；CaptchaWebView.kt（ENABLE_PASSWORD_LOGIN=false 门控，debug 可开） |
| ic postMessage 透传/心跳忽略 | ✅ | M1-T5；CaptchaWebView.kt（KDoc RN:293-301） |
| CAS SSO（settings.oauth 探测 + ssoToken + 回调判定） | ✅ | M1-T6；AuthWebScreen.kt + CasApi.kt（evaluateCasRedirect 纯函数 TDD） |
| 忘记密码 Web（AuthWebScreen 复用） | ✅ | M1-T6；AuthWebScreen.kt（login_forgot_password 标题） |
| 区号页（getAreaCode + 缓存 + +86 回落） | ✅ | M1-T4；feature/login/ui/AreaCodeScreen.kt |
| 登录页语言切换挂件 | ✅ | M5-T11 收口 M1 差异 1；feature/login/ui/AuthHeaderLanguageSwitch.kt |
| session 持久化 + 启动恢复（auth-storage 三字段） | ✅ | M1-T7/11；core/datastore/AuthSessionStore.kt + SessionBootstrapOrchestrator.kt |
| DDP resume + 会话失效踢出（logged out/expired 文案匹配） | ✅ | M1-T8；RealtimeSessionManager.kt（失效三态 TDD） |
| 组织切换（换票/缓存命中/互斥/回滚） | ✅ | M1-T10；OrgSwitchCoordinator.kt + feature/org/ui/OrgSwitchSheet.kt |
| 组织候选列表（getSwitchCandidate + MMKV 缓存） | ✅ | M1/M2；feature/org/OrgListRepository.kt（两段式 M2-T1 收口） |
| 登出编排（清缓存/teardown/删库/回 prelogin） | ✅ | M1-T10；domain/session/AuthRepository.kt（logout 序 TDD） |
| CAS enabled 严格布尔（非严格 true 按未启用处理） | 🔶 | 协议四节差异 4（fail-closed，现网无该场景） |
| 密码滑块 4xx/5xx 无横幅（RN 行为差异） | 🔶 | 协议四节差异 5（对齐 RN 行为差异待复查，两版同表现） |
| 区号回落显示时机（乐观先显 vs 返回后显示） | 🔶 | 协议四节差异 2 |
| 组织候选离线延迟（缓存即显 vs 会话就绪一次加载） | ✅ | 协议四节差异 3 已由 M2-T1 两段式收口；feature/org/OrgListRepository.kt |
| VerificationScreen 死路由不复刻 | 🔶 | 协议四节差异 6（RN 侧死路由，两侧用户均不可见） |

## 二、M2 会话列表 + 单聊

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 列表三段分组（我的助手/待办/频道，空段不显） | ✅ | M2 终审；ChatListSectioner.kt（RN groupChatsForRoomListSections 移植） |
| 段内排序（置顶>草稿>未读>时间；双未读落时间） | ✅ | M2 终审；ChatListSorter.kt（compareChatsRoomList ≥10 用例） |
| 待办段内四分（高优+草稿>高优>普通+草稿>普通） | ✅ | M2-T2；ChatListSectioner.kt |
| 最后消息预览全规则（草稿优先/特殊消息/发送人前缀） | ✅ | M2-T3 + M4-T1 收口；LastMessagePreview.kt（mention 显示名 M4 收口） |
| 相对时间五档（刚刚/N 分/N 时/N 天/M月d日） | ✅ | M2-T3；RoomListTime.kt |
| 未读徽标三档宽度 + 99+ + 标题加粗 + 静音图标 | ✅ | M2-T3；ChatRow.kt |
| 已读态右滑（标未读+置顶）/未读态左滑（标已读） | ✅ | M2-T4；ChatRowActions.kt（subscriptionAppearsRead 移植） |
| subscriptions.unread/read + rooms.favorite 端点 | ✅ | M2-T4；core/network/api/SubscriptionsApi.kt（双表写 TDD） |
| 连接状态横幅（断开/重连/重试按钮/2s 防抖/冷启动宽限） | ✅ | M2-T5；ConnectionBannerPolicy.kt + feature/chatlist/ui/ConnectionBanner.kt |
| 房间流订阅（stream-room-messages/typing/deleteMessage） | ✅ | M2-T6；core/realtime/RoomStreamManager.kt（typing RN 亦只订阅不分发——roomStreams.ts:73） |
| 消息落库字段收敛（ts 三态/status 不写/JSON 字符串化） | ✅ | M2-T6；core/messaging/MessageUpsert.kt（≥15 用例） |
| 历史分页（t 前缀映射/latest 游标/noGrowthStreak） | ✅ | M2-T7；RoomHistoryRepository.kt + RoomMessagesViewModel.kt |
| 发送状态机（QUEUED/SENDING/SENT/ERROR + serverId 迁移） | ✅ | M2-T8；SendOrchestrator.kt（echo 不双条 TDD） |
| 失败重试退避 1s/2s/4s + ERROR 点击重发 | ✅ | M2-T8；SendOrchestrator.kt |
| 消息行布局（左对齐/自己浅蓝 #CCE6FF/头像/时间戳 UTC 后缀） | ✅ | M2-T9；feature/chat/ui/MessageRow.kt |
| 系统消息映射（getInfoMessage 逐条 + rollback 分组 + 重新编辑） | ✅ | M2-T9 + M3-T11；SystemMessageTexts.kt + SystemMessageText.kt（≥20 用例） |
| mention 高亮（md AST 来源；@all/@here/自己三色） | ✅ | M2-T9 + M3-T5 收口；InlineNodes.kt（显示名解析 M3 收口） |
| 进房即读 + 停留 1s 防抖标读 + 离开清理 | ✅ | M2-T10；RoomReadMarker.kt |
| 草稿（debounce 1s/blur 即写/卸载 flush/发送后清） | ✅ | M2-T9；DraftRepository.kt |
| 日期分隔线 + 空态 + 滚到底按钮 | ✅ | M2-T9；RoomScreen.kt（滚到底简化为「不在顶部即显示」） |
| removed 事件（列表行删 + 退订 + 在房退出） | ✅ | M2-T4 + M4-T10；NotifyUserPersistence.kt + RoomAccessLoss.kt |
| 只读房横幅（RoomFooter 只读优先替换输入区） | ❌ | RN RoomFooter/index.tsx:26-39 + RoomReadOnlyFooter（roomReadOnly_banner）；AA RoomScreen.kt:955 注记「只读横幅 M2 现状不变」——只读房仍渲染输入区（长按菜单已拦，isRoomReadOnly 仅拦菜单不替换底栏）；strings.xml 已有 roomreadonly_banner 键无消费方。见缺口清单 #G1 |
| 列表搜索入口（RoomListSearchBar onFocusNavigate→GlobalSearch；RN 冻结版内联搜索框已隐藏） | ✅ | M5-T5；ChatListScreen.kt:92-93/208-216（onOpenSearch→GlobalSearch；协议七节差异 6 已收口；RN index.tsx 变体 SearchBar 同语义） |
| 深链 appia:// scheme（RN manifest hosts room/auth/fse + https 域） | 🔶 | M6 调研 §2.4 结论 15：RN filter 为死配置（RN src 无 Linking 消费者）；AA 推送点击走 intent extras 自建径（协议七节差异 5 收口口径） |
| 组织 Tab 条（RoomListOrgTabs：多主体 tab 快捷切换 + 管理弹层 + 未读点） | ❌ | RN RoomListScreen/index.tsx:476-485 + RoomListOrgTabs.tsx + OrgTabsManageSheet；AA 全仓零 OrgTabs 命中，组织切换仅 ⋮ 菜单「我的企业」弹层（OrgSwitchSheet）——无列表顶部 tab 形态、无 tab 未读点（selectOrgTabStatuses）。任何里程碑计划/协议差异节均未裁定此项。见缺口清单 #G2 |
| 下拉刷新失败仅 Alert 不触发横幅 | 🔶 | 协议七节差异 1（横幅由连接层驱动，M5 收口口径） |

## 三、M3 消息完整功能

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| TipTap 富文本编辑器（10tap WebView + 桥） | ✅ | M3-T2/12；feature/chat/editor/（EditorWebView/TenTapBridge/TenTapEditorBridge，spike GO） |
| 工具栏（Aa 展开：加粗/斜体/删除线/高亮/字色 11 色/列表/清除格式 + 激活态回读） | ✅ | M3-T12；ChatInputBarController.kt（toggle-bold/setColor 等 action 面） |
| TipTap↔md AST 双向转换（含 BIG_EMOJI 判定） | ✅ | M3-T3；TipTapJsonConverter.kt（RN 测试移植 + round-trip） |
| md 渲染链（md 直读/stale 回退/GFM 表格 augment） | ✅ | M3-T4；MessageMdResolver.kt |
| 块级节点（Paragraph/Heading/Quote/Code/List/BigEmoji/HR/Table） | ✅ | M3-T4；MarkdownNodes.kt（表格 3 行截断 + 全屏 overlay） |
| 行内节点（LINK/INLINE_CODE/EMOJI/BOLD/ITALIC/STRIKE/MENTION 三色） | ✅ | M3-T5；InlineNodes.kt（Bold 内递归——修正 RN 缺口，M3 计划 Task 5 偏离记录） |
| KaTeX 公式渲染 | 🔶 | 协议九节差异 1（降级原式文本 + 点击全屏 WebView 渲染；RN 行内直渲） |
| (edited) 标记（md 行内尾随/无 md 独立灰字） | ✅ | M3-T13；MessageRow.kt（appendEditedTagToMd） |
| 附件选择（PhotoPicker + Documents，上限 100，拷贝暂存） | ✅ | M3-T6；AttachmentSelector.kt + PendingAttachments.kt |
| 附件上传（rooms.upload 单文件/multiAttachments 多文件/失败 append 重试/进度环） | ✅ | M3-T6；UploadApi.kt + SendOrchestrator（成功路径禁写 attachments） |
| 附件查看（图片捏合缩放/视频 Media3/音频/PDF/office 轮询/下载通知） | ✅ | M3-T7；AttachmentViewer.kt + VideoPlayer.kt + DocPreview.kt + MediaCache.kt |
| PDF 整本一次性渲染（大 PDF 卡顿/OOM 天花板） | 🔶 | 协议九节差异 6（PdfRenderer 自绘天花板，反馈时升按页懒渲染） |
| office 预览服务端转码轮询（31 次 × 2s；RN localFile 直开不移植） | 🔶 | 协议九节差异 7 |
| 合并转发卡片视觉简化（圆角/涟漪未像素级复刻） | 🔶 | 协议九节差异 12 |
| AI 摘要/横竖屏切换/全房间画廊（附件查看器附属） | 🔶 | 协议九节差异 13（接受推迟） |
| 表情回应（chat.react toggle + 乐观更新 + 反应条 + picker） | ✅ | M3-T8；ReactionApi.kt + ReactionBar.kt（Android 领先功能） |
| 表情回应 picker 为固定 12 常用集 | 🔶 | 协议九节差异 2（Android 领先功能边界：legacy 全量+搜索后补；RN 0 字节占位不可见） |
| 转发单条/合并（forwardMessageIds wire + forwardMergeMessage 卡片 + 详情下钻） | ✅ | M3-T9；ForwardApi.kt + ForwardSelectScreen.kt + ForwardMergeCard.kt + ForwardDetailScreen.kt |
| 转发选择页（3 tab + spotlightv2 搜索 300ms 防抖 + MAX 10 + 创建频道并转发） | ✅ | M3-T9 + M4-T1；ForwardSelectScreen.kt（组织树 M4 收口；创建频道并转发 RN:645-653） |
| 已读回执（自己消息已读/未读图标 + 明细页双 tab） | ✅ | M3-T10；ReadReceiptScreen.kt + ReadReceiptsApi.kt（DM 不渲染可点图标） |
| 未读横幅（room.firsUnread ≥10 条显示 + 点击跳转 + 滚过消失） | ✅ | M3-T10；UnreadBanner.kt |
| 长按菜单全集（回复/编辑/复制/转发/多选/撤回/重发 + 待办两项 M7） | ✅ | M3-T11 + M7-T3；MessageActionController.kt（getOptions 全分支 TDD） |
| 撤回（message.recall + 快照 + rollback 分组 + 重新编辑回填） | ✅ | M3-T11；RecallApi.kt + SystemMessageText.kt（RollbackMessageGroup） |
| 回复引用（composeQuotedMessageText permalink+@mention + ReplyPreview 横幅） | ✅ | M3-T11；MessageActionController.kt:273-302 + RoomScreen.kt 回复预览 |
| 消息编辑（buildEditContent 回填 + updateMessage 直发 + stash 草稿） | ✅ | M3-T12；MessageEditController.kt + ChatInputBarController.kt |
| @提及补全（beforeinput trigger + MentionSuggestion 多选 + agent 房门控） | ✅ | M3-T12；MentionSuggestionScreen.kt（ALL_MEMBER 首项 + Agent_Bot_List 门控）；bot 候选/成员头像 M4/M5 收口（协议九节差异 8 已收） |
| 合并转发消息级 appiaBaseUrl 透传（跨主体附件域名——裂图/404） | ❌ | RN aa93319（MessageBody/mediaPreview 逐消息 appiaBaseUrl 合并进附件 URL）；AA ForwardMergeMessage.kt 的 ForwardMessageItem/mapForwardMessagesToEntities 无该字段——服务端已下发、AA 丢弃，跨主体合并转发图片裂图/文件 404。T1 追进项 #2（T1 评审定级主链路渲染缺陷）。见缺口清单 #G9 |
| 公告列表 updateTime 降序 + originSender 展示（消息转公告显示原始发送人） | ❌ | RN 32c8984（roomAnnouncements 排序 + 「XX 发布的消息，由 YY 设为公告」）；AA RoomAnnouncements.kt parseMainAnnouncements 无排序无 originSender 字段，getAnnouncementPreviewText 取 items.last()（RN 取最新=排序后首条）；RoomAnnouncementScreen.kt 仅 announcement_published_by[_time]，strings.xml 缺 announcement_from_sender_by 键。T1 追进项 #1（M4 域功能，登记于 M3 节防漏；亦在 M4 公告行交叉引用）。见缺口清单 #G8 |
| 聊天内公告卡（AnnouncementMessage：room_*_announcement 专卡——标题+分隔线+md 正文+图片行+文件） | ❌ | RN RoomMessageRowBody.tsx:41-47（isAnnouncementType→AnnouncementMessage 专卡）+ AnnouncementMessage/index.tsx；AA MessageRow.kt:67-75 将三型排除系统消息后走普通气泡行——「announcement 走普通行=RN」注记与 RN 实际不符（T1 移交项 1 复核属实：实现误读）。见缺口清单 #G3 |
| 多选「设为公告」（MultiSelectActionBar showAnnouncement 门 + saveRoomSettings roomAnnouncementData） | ❌ | RN MultiSelectActionBar/index.tsx:55-63 + RoomScreen/index.tsx:939-978（handleSetAnnouncement）；AA MessageActionsSheet.kt:64 注记「归 M7 不移植」但 M7 计划全文无此任务（T1 移交项 2 复核属实：未收编）。见缺口清单 #G4 |
| 多选「新频道」（创建讨论 createDiscussion，prid/pmid/t_name） | ❌ | T1 delta 追进项 #3（RN d82f1c1 冻结后新增）；AA ChannelsApi.kt 无 createDiscussion 端点、MultiSelectActionBar 无该钮。已由 T1「追进发布」清单承载，此处登记防双计。见缺口清单 #G5 |
| 附件卡片 ACTIONS/COLLAPSIBLE_QUOTE 不渲染 | 🔶 | 协议九节差异 4（bot/集成卡片推迟 M7；QUOTE 引用卡片 RN 亦不渲染） |
| 相册 Pager 范围 = 本消息图片 | 🔶 | 协议九节差异 5（全房间画廊推迟 M7 专项） |
| 图片无客户端压缩 | 🔶 | 协议九节差异 3（跟随 RN 现状） |
| 长按时长 400ms vs RN 500ms / 图标不转录 | 🔶 | 协议九节差异 9 |
| 编辑横幅简化（无原文预览行）/提交分路 | 🔶 | 协议九节差异 10 |
| 回复态不持久化（draft_reply_msg_id 恒空） | ❌ | RN DraftRepository 写 replyMsgId ?? ''（回复中退出重进保留回复态）；AA DraftRepository.kt:12 注记「M3-T12 现状」不写该列——重进房间回复态丢失。协议/backlog 均无裁定。见缺口清单 #G6 |

## 四、M4 群组 + 联系人

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| RoomInfo 非直聊（成员网格 ≤20 槽加减/信息卡/设置卡/退出） | ✅ | M4-T4；feature/roominfo/ui/RoomInfoScreen.kt |
| 成员网格槽位（members/v2 + 加人/移除/更多成员入口） | ✅ | M4-T4；RoomInfoScreen.kt（onAddMembers/onRemoveMembers/onMoreMembers 装配） |
| 分类行（仅 c|p，canEditRoomUsage 门 + 抽屉四选） | ✅ | M4-T4；RoomInfoScreen.kt + RoomInfoActions.kt（appiaUsage） |
| 静音/置顶 toggle 乐观更新 + 失败回滚 | ✅ | M4-T4；RoomInfoActions.kt（mute/pin 乐观） |
| 退出房间（markPendingSelfLeave → postLeaveRoom 失败清标记） | ✅ | M4-T4；PendingSelfLeave.kt |
| RoomInfo 直聊分支（对方头像卡 + 加号建群 peer 预选 + 会议行） | ✅ | M4-T4 + M8；RoomInfoScreen.kt（onDirectAddChannel intent=create） |
| 成员管理页双模式（list 部门分组/remove 复选多选） | ✅ | M4-T5；feature/roominfo/ui/RoomMembersScreen.kt |
| 成行长按 ActionSheet（发消息/设撤 owner/设撤 moderator/移除，权限门） | ✅ | M4-T5；core/chat/RoomMemberActions.kt（hasRoomPermission 门全集 TDD） |
| owner 行不可移除 + joinType 含 user 才可移除 | ✅ | M4-T5；core/chat/ResolveMemberRoleTag 旁 canRemoveMemberRow |
| 部门行长按移除整个部门（removeDepartment） | ✅ | M4-T5；RoomMembersScreen.kt |
| 批量移除（并行 per-user + 每 dep 一个 removeDepartment） | ✅ | M4-T5；RoomMembersScreen.kt |
| team 主房移除双调用（teams.removeMember → kick） | ✅ | M4-T3；RoomSettingsApi.kt（wire TDD） |
| 角色徽标（owner→owner / moderator|ghost-owner|admin→admin） | ✅ | M4-T5；ResolveMemberRoleTag.kt |
| GhostOwner API 落地不接 UI | 🔶 | 协议十一节差异 13（RN 同款：API 层存在无 UI 调用方，忠实一致） |
| 公告解析（plain/JSON/双重编码/嵌入文件拆分/meeting|summary 分类） | ✅ | M4-T7；core/chat/RoomAnnouncements.kt（全形态 TDD） |
| 公告屏（列表/编辑双态 + 发布/删除 + announcement.bot 附件端点 + md 渲染 + 编辑门） | ✅ | M4-T7；feature/roominfo/ui/RoomAnnouncementScreen.kt（列表序/originSender 缺口见 M3 节 #G8 行——T1 追进项 #1 交叉引用） |
| 直聊不可传公告文件 | ✅ | M4-T7；RoomAnnouncementScreen.kt（上传入口门控） |
| 改名屏（fname/dname max80 + error-invalid-room-name 友好提示） | ✅ | M4-T7；feature/roominfo/ui/RoomChannelNameEditScreen.kt |
| 权限层（permissions.listAll + permissions-changed 流 + hasRoomPermission 兜底映射） | ✅ | M4-T2；core/permissions/ + PermissionsApi.kt |
| 选人器（create/addToRoom 双 intent + members 五子源 + org 部门树多选三态） | ✅ | M4-T8；feature/contacts/ui/CreateChannelMembersScreen.kt（BFS 全选/三态 TDD） |
| 选人器搜索（spotlightv2 users-only + agents 本地过滤） | ✅ | M4-T8；CreateChannelMembersScreen.kt + SpotlightApi.kt |
| 通讯录双树（PMT/L1D tab + 我的卡 + 公司头 + 部门下钻 + footer 计数） | ✅ | M4-T6；feature/contacts/ui/TeamScreen.kt + TeamModels.kt（RN 测试移植） |
| 通讯录搜索（PMT+L1D 合并去重/部门按名/成员按名或用户名） | ✅ | M4-T6；TeamModels.kt（buildTeamFlatList 搜索） |
| 成员名片（users.info + 头像鉴权 + 发消息 DM 链 + email/supervisor/简历门） | ✅ | M4-T9；feature/contacts/ui/MemberProfileScreen.kt + OpenDirectMessage.kt（三段链 TDD）；简历链接 InAppWeb 迁正 M5-T10（协议十一节差异 7 已收口） |
| 名片语音通话按钮 | ✅ | M10-T12 接线（协议十一节差异 5 已收口，M4 期占位禁用）；MemberProfileScreen.kt:74 |
| 名片 POTA/OKR 节（无权限不显示；有权限只读层级） | ✅ | M4-T9；MemberProfileScreen.kt OtkrSection（parseOtkrQuery 三层 sanitize M5 收口）；协议十一节差异 4（只读层级 RN 同） |
| 名片/信息页会议行 | ✅ | M8-T5 接线（协议十一节差异 12 已收口，M4 期占位）；RoomInfoScreen 会议行 |
| 我的二维码（qrcode.query 7 级回退 + ZXing 生成 + 保存相册权限链） | ✅ | M4-T9；core/network/api/QrcodeApi.kt（回退次序 TDD）+ MyCardScreen.kt |
| 我的名片入口 | ✅ | 协议十一节差异 11（顶栏菜单形态，M5-T9 菜单化裁定范畴）；ChatListScreen ⋮ 菜单 + ProfileScreen.kt |
| 发起 DM（本地查找 → im.create → 进房 reset） | ✅ | M4-T9；OpenDirectMessage.kt + MainActivity 装配 |
| roomAccessLoss（被踢/自退/别处退出三态 + 栈清理全清单 + DM 过滤） | ✅ | M4-T10；domain/session/RoomAccessLoss.kt（stackInvolvesRid 清单） |
| ContactsScreen 独立联系人屏不做 | 🔶 | 协议十一节差异 6（TeamScreen 双树覆盖全部场景，RN 通讯录入口实路由即 TeamScreen） |
| agents tab 仅可选择（无管理面板） | 🔶 | 协议十一节差异 1（管理入口 M7 AgentEditor 收编创建/编辑/禁用——现状 M7-T8 已落 AgentEditorScreen.kt，差异 1/2 的「创建智能体」已部分收口） |
| partners 门控休眠（Appia_Show_External_Partners） | 🔶 | 协议十一节差异 3（M5 settings 拉齐后激活） |
| 部门图标色块/三态勾选视觉近似 | 🔶 | 协议十一节差异 8/9（视觉级） |
| presence M5 收口 | 🔶 | 协议十一节差异 10（M5-T3 已落 presence 基建；TeamScreen 绿点 status 回退渲染） |
| 扫一扫（RoomListScreen "+" 菜单 → ScanScreen 相机扫码 + 相册识码 + SSO QR 改写 + ScanResult 文本复制） | ❌ | RN RoomListScreen/index.tsx:297-303（addMenuItems scan 项）+ ScanScreen/index.tsx + ScanResultScreen + lib/scan/{parseQrPayload,routeScanResult,resolveSsoQrUrl}；AA 全仓无 Scanner/MLKit/camera 扫描（T1 移交项 3 复核属实）；MyCard/Profile 仅有二维码生成（QrcodeApi.kt）。⚠️ 依赖 RN "+" 菜单——AA 无 "+" 菜单（M5-T9 菜单化裁定），入口需重新锚定。见缺口清单 #G7 |
| RN "+" 菜单本身（创建频道 + 扫一扫） | 🔶 | AA 无 "+" 菜单为 M5-T9 既定裁定（ChatListScreen.kt:100 注记；delta 表 ddef1b5/48c2961 行引）；其内「创建频道」功能经 转发页「创建频道并转发」+ RoomInfo 直聊加号（peer 预选 create intent）+ 通讯录建群路径可达，独立「空手建群」入口缺——与 #G7 合并评估（扫码入口需重锚定） |
| 列表顶栏头像 → 侧栏抽屉（RoomListProfileHeader onPressAvatar → openDrawer → MineMenuScreen） | 🔶 | AA 无抽屉为 M5-T9 裁定（ChatListScreen.kt:85-100 注记链）；抽屉内容（待办/工作台/通讯录/会议/个人资料/我的企业/登出）全部经 ⋮ 菜单可达（M7-T3/T4、M8-T5 菜单化迁移注记） |

## 附：状态分布（基础域）

| 域 | 行数 | ✅ | ❌ | 🔶 |
|---|---|---|---|---|
| M1 认证多组织 | 24 | 20 | 0 | 4 |
| M2 会话列表单聊 | 26 | 22 | 2 | 2 |
| M3 消息完整 | 37 | 20 | 6 | 11 |
| M4 群组联系人 | 39 | 30 | 1 | 8 |
| 合计 | 126 | 92 | 9 | 25 |

> ❌ 行与缺口编号 #G1-#G9 的对应及严重度评定见任务报告结构化缺口清单（T5 消费输入）：
> #G1 只读横幅（M2）/ #G2 组织 Tab 条（M2）/ #G3 聊天内公告卡（M3）/ #G4 多选设为公告（M3）/ #G5 多选新频道（M3，与 T1 delta 追进项 #3 同源）/ #G6 回复态草稿（M3）/ #G7 扫码（M4）/ #G8 公告置顶+originSender（M3 节登记，与 T1 追进项 #1 同源）/ #G9 合并转发 appiaBaseUrl（M3，与 T1 追进项 #2 同源）。T5 汇总时 #G5/#G8/#G9 与 T1「追进发布」清单防双计。
