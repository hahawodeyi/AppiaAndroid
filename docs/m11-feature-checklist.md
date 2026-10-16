# M11 功能 Checklist——基础域（M1-M4）+ 扩展域（M5-M7）+ RTC 域（M8-M10）

> RN 锚 = 冻结基线 `c92e3df`（`git -C ../appiaMobile show c92e3df:<path>` 可复核）。
> 状态三值：✅已实现（AA 文件锚 + 里程碑终审）/ ❌缺口（入结构化缺口清单）/ 🔶授权差异（引用协议已知差异节号或总纲裁定）。
> 协议差异节 = `docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`（四/六/八/九/十/十一/十三/十五/十七/十九/二十一/二十三节）。
> 本文件 T2 建档（基础域 M1-M4）；T3 追加扩展域（M5-M7）；T4 追加 RTC 域（M8-M10）。
> M8/M10 为 GREENFIELD 混合域：RN 会议本体是 WebView H5、RN 语音未过 M0 真机门禁（`docs/VOICE_CALL_M0_GATE.md` 冻结点未勾选）——两域功能面以 AA spec/计划 UI 清单 + RN 仓既有代码为锚，🔶 密集引用协议十九/二十三节。

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

## 五、M5 搜索 + presence + 设置

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| settings.public 分批同步（50/批 + stream-notify-all 增量，86 键注册表） | ✅ | M5-T1；core/settings/ServerSettingRegistry.kt + SettingsPublicApi.kt（CHUNK_SIZE=50） |
| 全局角色运行时刷新（30s 节流 + inflight 去重 + bootstrap/ON_RESUME/focus 三触发） | ✅ | M5-T2；domain/session/RoleRefresher.kt |
| presence 三层（store + 2s 批量订阅 + username 解析 isRocketChatUserId） | ✅ | M5-T3；domain/presence/PresenceStore.kt + PresenceBatcher.kt + UsernameIdResolver.kt |
| presence 绿点（仅 online/away；TeamScreen/名片/成员网格/单聊 peer 四消费点） | ✅ | M5-T3；feature/chat/ui/PresenceBadge.kt（TeamScreen.kt:639/MemberProfileScreen/RoomInfoScreen.kt:632/ChatRow.kt:179 消费）；bot 剔除 PresenceJudgments.isBotUserId |
| 名片「在线」文字态仅 online（与绿点判定分离） | ✅ | M5-T3；PresenceJudgments.kt isOnlineTextStatus（MemberProfileScreen.kt:165 消费） |
| UI_Use_Real_Name 接线（缺省 true；发送者名/提及/预览/引用四消费点） | ✅ | M5-T4；MessageRow.kt buildMessageHeaderDisplay（:100 缺行 true 注记） |
| 全局搜索 4 tab（all 预览 3 条+折叠 / members / messages / files cursor 分页） | ✅ | M5-T5；GlobalSearchViewModel.kt + GlobalSearchScreen.kt（三分区折叠 :292）+ FilesSearchApi.kt |
| 全局搜索防抖 300ms + 竞态守卫 + 离线本地分区回退 | ✅ | M5-T5；GlobalSearchViewModel.kt（DEBOUNCE + localFallbackRows :491，banner=error 回退） |
| spotlight contact 点击 rid username 回退走 openDirectMessage 链 | ✅ | M5-T5；GlobalSearchViewModel.kt resolveDmRid（knownRid 仅本地确证后采用） |
| 消息详情屏（chat.search 分页 50 / _id 去重 append / addedCount==0 止步） | ✅ | M5-T5；GlobalSearchMessageDetailScreen.kt（appendChatSearchPage :96） |
| 高亮截断（keepMatchVisible 前后缀省略） | ✅ | M5-T5；feature/search/ui/HighlightText.kt |
| 跳转高亮（loadSurroundingMessages 50 + chunk 占位 1px + #FFF7D6 + 15s 超时 + 300ms loading 防抖 + 取消） | ✅ | M5-T6；MessageJumpController.kt + MessageJumpApi.kt + MessageRow.kt JumpHighlightColor |
| 跳转只响应路由参数变化（防死循环）/ 发消息或下拉刷新退实时 | ✅ | M5-T6；MessageJumpController.kt（plan 三路 not-found/navigate-room/scroll/fetch） |
| 房间内搜索 6/4 tab（群 messages/members/files/media/mentions/links；单聊 4） | ✅ | M5-T7；RoomSearchViewModel.kt getVisibleRoomSearchTabs + RoomSearchScreen.kt |
| 房间内搜索各 tab 语义（messages notIncludeFile / files/media regex / mentions 双层 / members 本地 / links 启发式） | ✅ | M5-T7；RoomSearchViewModel.kt（links 启发式照抄——协议十三节差异 7） |
| 加密房本地 LIKE 搜索（字面匹配） | 🔶 | 协议十三节差异 6（AA 字面转义修正 RN _ 通配退化——故意分歧）；RoomSearchViewModel.kt:336 ESCAPE 注记 |
| tSearch bump（进房/离房双 bump + 800ms 重试 + DDP 覆盖防护） | ✅ | M5-T8；domain/chat/ChatBumper.kt |
| 设置页五分区（通用/通知/关于/法律/账户） | ✅ | M5-T9；feature/settings/ui/SettingsScreen.kt（五分区 qa tag 逐段） |
| 语言三段切换（登录头下拉 + 设置三段，运行时全 app 生效） | ✅ | M5-T11；core/i18n/LocaleController.kt + AuthHeaderLanguageSwitch.kt；下拉视觉差异——协议十三节差异 11 |
| 字体段选择仅持久化不改变渲染 | 🔶 | 协议十三节差异 2（RN 真实应用字体资产；AA MMKV 先行） |
| 头像样式 photo｜letter（users.setPreferences 乐观回滚） | ✅ | M5-T9；SettingsScreen.kt:201-215（乐观翻转+失败回滚）；letter 头像 /avatar 变体未消费——协议十三节差异 3 |
| 通知细项三键（disableNotifications/muteGroupMentions/hideUnreadStatus '1'/'0'） | ✅ | M5-T9；UserPreferencesApi.kt + RoomInfo 通知细项接线 |
| 消息设置屏（showImageSummary/showDocumentSummary 两开关乐观） | ✅ | M5-T9；feature/settings/ui/MessageSettingScreen.kt |
| 状态编辑（maxLength 120 + users.setStatus + mergeStatusText） | ✅ | M5-T9；StatusEditScreen.kt（STATUS_MAX_LENGTH=120） |
| ProfileScreen 只读基线（头像/姓名只读 + 二维码 + 邮箱 + 设置入口） | ✅ | M5-T9；feature/settings/ui/ProfileScreen.kt（RN 无编辑端点不发明——计划 Global Constraint） |
| MyCard 迁正（唯一入口 ProfileScreen；Enterprise_Name 表读显示） | ✅ | M5-T9；MyCardScreen.kt:322（Enterprise_Name 两侧细线） |
| 头像上传不做（RN ProfileScreen 未实现） | 🔶 | 协议十三节差异 1（只读对齐） |
| 检查更新（设置-关于：GET /provider/api/v1/version 免登录 + 结果 Alert） | ✅ | M5-T9 + M6-T6；SettingsScreen.kt:179-194 + AppReleaseApi.kt；应用内弹窗仅自动检查链路由 M6 收口——手动检查弹窗为缺口 #G10（M6 节登记） |
| 清除缓存（teardown → 删库 → 重 bootstrap） | ✅ | M5-T9；feature/settings/ClearLocalCache.kt（generation 防竞态） |
| 退出其他设备（users.removeOtherTokens 确认弹窗） | ✅ | M5-T9；SettingsScreen.kt 账户分区 |
| guest 删号判定（username 含 appia.guest 显示删号入口） | ✅ | M5-T9；SettingsScreen.kt（判定走登出链） |
| 法律分区（服务条款/隐私政策 LEGAL_URLS；浏览器 pref inApp→应用内 systemDefault→外跳） | ✅ | M5-T9 + M5-T10；SettingsScreen.kt:147-149 + openLink 决策（InAppWebScreen.kt:666） |
| 电池优化状态行（isIgnoringBatteryOptimizations + ON_RESUME 刷新 + 申请豁免） | ✅ | M5-T9；SettingsScreen.kt:134-166（null 不渲染分区 RN 同） |
| 快捷回复行 no-op（CustomQuickReply 屏计划外） | 🔶 | 协议十三节差异 5（行渲染不可导航）；RN CustomQuickReplyScreen 实为 128 行可编辑屏（setUserPreferences appiaQuickReplies）——AA 仅渲染行无屏无编辑，差异节按 no-op 裁定收录 |
| InAppWeb 壳（needAuth 白名单三域 + users.externalToken 换 code + 返回栈 canGoBack 域外判定） | ✅ | M5-T10；feature/web/InAppWebScreen.kt（survey/lexiang/ssc-docs 三域 :66-69）+ ExternalTokenApi.kt |
| InAppWeb 时序（Meteor localStorage 注入 onPageStarted→evaluateJavascript） | 🔶 | 协议十三节差异 9（RN-on-Android 同款时序逐位同）；泛微/石墨/WPS/会议拦截 M7 收口见下节 |
| 跳转加载遮罩仅覆盖消息区（非 RN 全屏 Modal） | 🔶 | 协议十三节差异 12 |
| 跨房跳转平跳（navigate 非 reset 清栈）收口 | ✅ | 协议十三节差异 10 已由 M6-T1 navigateToRoomFromAppRoot 收口（协议十五节差异 12）；MainActivity 导航 helper |

## 六、M6 推送 + 自更新

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 阿里云推送 SDK 初始化（Application.onCreate + meta-data + init 结果码 10000/PUSH_20110 均成功） | ✅ | M6-T2；core/push/AliyunPushBootstrap.kt + AppiaApplication.kt（坑 1 语义） |
| channel_01 预建 IMPORTANCE_HIGH + im_default | ✅ | M6-T2；PushNotificationChannels.kt（坑 3 不预建则系统低重要性） |
| manifest 全套（POST_NOTIFICATIONS/RECEIVE_BOOT_COMPLETED/角标三权限/receiver 三 action/PopupPushActivity/meta-data） | ✅ | M6-T2；AndroidManifest.xml:20,82 + com/wix/.../PopupPushActivity.kt |
| FCM 辅助通道（google-services 插件仅 release 应用） | 🔶 | 协议十五节差异 2（.debug 后缀与 json package 冲突；GMS 离线送达验收须 release 包）；app/build.gradle.kts:98-105 任务图条件应用 |
| 推送 token 注册（登录 deviceId→push.token / 登出删除；deviceId=CloudPushService.getDeviceId） | ✅ | M6-T3；PushTokenRegistrar.kt（端点/body/存储键与 RN 对齐） |
| POST_NOTIFICATIONS 运行时申请（每进程会话至多一次） | 🔶 | 协议十五节差异 1（RN 从不申请——AA 故意分歧优于基线）；NotificationPermissionGate.kt |
| 电池优化引导（冷启动+登录双触发；跳系统白名单页） | 🔶 | 协议十五节差异 5（收窄：每会话至多一弹、登出态冷启动不触发）；BatteryOptimizationGuide.kt |
| payload 四层兜底解析（ejson→extra 袋内双重转义→整袋→顶层 rid+type→裸 rid 兜底 'c'；ROOM_TYPE_MAP；标题规则） | ✅ | M6-T4；PushPayloadParser.kt（全兜底层 + 双重转义 ≥14 用例） |
| oncall 识别分流（通话中语音意图不进房；isCallEnd 照常进房） | ✅ | M6-T4 桩 + M10-T12 收口；PushClickRouter.kt onVoiceOncall 出口 + VoicePushIngress.kt（协议十五节差异 3 已收口） |
| host 校验（多组织防护：B 组织推送 A 组织点击静默跳过） | ✅ | M6-T4；PushClickRouter.kt isPushHostMatchingCurrentServer（hostname 比、忽略协议/尾斜杠/子域后缀） |
| 90s TTL 导航队列（rid:messageId 去重 + drain 门控 auth∧navReady） | ✅ | M6-T4；PendingPushNavigation.kt（双向过期丢弃） |
| 三 drain 点（导航就绪/Main 挂载/回前台 + 400ms 延迟二次 drain） | ✅ | M6-T5；PushNavigationDrainer.kt（400ms 原样移植非 Android 惯例） |
| 进房复用跳转管线（RoomRoute(jumpToMessageId)；ejson 无 messageId 只进房不高亮） | ✅ | M6-T5；MainActivity drain→navigateToRoomFromAppRoot→M5 跳转管线零新增逻辑 |
| 通知清理（回前台 cancelAll——进房已读后通知残留清空） | ✅ | M6-T5；PushNavigationDrainer.kt:40（坑 16）；清理时序——协议十五节差异 10（冷启动 onResume 即清，无行为差） |
| 冷启动点击直达（进程内回调直接入队无 JS 桥缓存层） | 🔶 | 协议十四节自测 1 + M6 计划 T5 注记（AA 原生单进程天然简化，结构差报告注明） |
| appia:// scheme 深链（RN manifest 声明） | 🔶 | 协议十五节差异 4（RN 死配置不移植——src 无 Linking 消费者；AA 走推送 intent extras 自建径）；亦见 M2 节同款行 |
| 自更新检查（裸 OkHttp GET provider/api/v1/version 无 IM token + url 逗号取下标 + semver 比较 + 3h stale 窗口 + 仅已登录） | ✅ | M6-T6；AppReleaseApi.kt（APP_RELEASE_URL_COMMA_INDEX=0）+ CompareAppVersions.kt + AppReleaseCheckController.kt |
| 自更新检查无瞬时重试（失败保持旧结果） | 🔶 | 协议十五节差异 6（RN TanStack 3 retry 不移植——终态一致） |
| 前台重查受 3h 窗口门（RN invalidateQueries 每前台真查） | 🔶 | 协议十五节差异 7（最大提示滞后 3 小时） |
| semver 比较省略长度帽/边界锚 | 🔶 | 协议十五节差异 11（19+ 位数字段抛异常 vs RN 降级字典序——服务端可控输入判不可达） |
| 自更新弹窗（强制无暂不+返回键不可关；可选暂不=会话内内存静默冷启动重弹；下载中隐藏按钮；自动检查链路 AppUpdatePromptHost 完整） | ✅ | M6-T7；feature/settings/ui/AppUpdateModal.kt + AppUpdatePromptController.kt（坑 11/12 语义——自动检查链路无缺；手动检查接线缺口见 #G10 行） |
| 手动「检查更新」弹应用内弹窗（bypassDismiss——点过暂不也立即弹） | ❌ | RN SettingsScreen :157-160 → useAppUpdateUiStore.show(row, {bypassDismiss:true})；AA SettingsScreen.kt:172-199 onPressCheckUpdate 有新版时仍 openUrl 跳浏览器（M5-T9 时代行为）——AppUpdatePromptController.showManual() 已备但全仓零调用点（仅测试），M6-T7 未接线（协议十四节自测 4「手动检查→立即弹」AA 验不过）。见缺口清单 #G10 |
| 弹窗头图 update.png | 🔶 | 协议十五节差异 8（无美术资产——主题化头盒承载同文案） |
| 应用内 APK 下载安装（app 专属 Download 免权限→临时名→改名→FileProvider→系统安装器；缓存命中同名跳下载；挂载清理旧版） | ✅ | M6-T7；AppReleaseInstaller.kt（downloadAndInstall/cleanupInstalled） |
| FileProvider authority 差异 | 🔶 | 协议十五节差异 9（${applicationId}.provider vs RN 硬编码 cn.appia.im.provider） |
| versionCode > 28187853 下限接线（release.properties VERSION_CODE 守门） | ✅ | M6-T8；app/build.gradle.kts:27-39（MIN_INSTALLABLE_VERSION_CODE 校验失败即报错） |
| 跨组织未读刷新（onNotification 到达非本组织 host→刷新跨组织未读） | ✅ | M7-T1 收口协议十七节差异 9；CrossOrgUnreadRefresh.kt + AppiaAliyunPushReceiver.kt:24,57（onNotification/receivedInApp 双挂点；onMessage 透传无 extraMap 仍不挂——差异 9 注记） |

## 七、M7 待办 + 工作台 + AI

| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 待办四接口（GET appia/todos / set-message-todo status:1 / update-message-todo-status -1 / update-message-todo） | ✅ | M7-T2；core/network/api/TodosApi.kt（发送值≠显示值服务端约定照抄——坑 2） |
| 待办分区段头 + 段内四分排序（高优+草稿>高优>普通+草稿>普通） | ✅ | M7-T2；ChatListSectioner.kt（TODO("roomList_sectionTodo") 段头落地） |
| 待办双计数源（抽屉=REST total / 红点=DDP todoCount 有意不同步） | 🔶 | 协议十七节差异 4（RN 同构勿统一——坑 1）；ChatListScreen.kt:142 REST 线 + ChatRow.kt:167 DDP 线 |
| 抽屉待办卡（⋮ 菜单：total 计数 + 首条标题；点击→全量列表） | 🔶 | 协议十七节差异 3（迁菜单后附件缩略图/元信息框无宿主未移植——仅 total+首条标题）；ChatListScreen.kt:231-253。量级补注：RN 卡含附件缩略图（MessageImageRow）+ 创建/提醒时间元信息框 + 高优徽标完整视觉，AA 仅两行文本——T5 重估候选 |
| 会话行头像红点（todoCount>0；>99 显 99+） | ✅ | M7-T3；ChatRow.kt:165-170 + TodoCard.kt RoomItemTodoBadge（红底白字） |
| 房间头待办入口（todoCount>0 显 ☑ → 房间待办屏） | ✅ | M7-T3；RoomScreen.kt:287,701-719（qa-room-header-todo；todo→search 顺序 RN 同） |
| 长按设待办/完成待办（互斥二元：appia_todo 进行中→完成否则设为） | ✅ | M7-T3；MessageActionController.kt:113,138（SET_TODO/FINISH_TODO；M3 长按菜单全集行交叉引用） |
| 待办列表屏全量 + 房间待办屏按 rid（footer/空态「全部待办」钮） | ✅ | M7-T3；TodoListScreen.kt + RoomTodoScreen.kt |
| 待办卡（高优 tag/stripMarkdownLite 标题/附件图与文件行/房间名/超时红/完成+去处理） | ✅ | M7-T3；TodoCard.kt（RN TodoListCard 逐块对照） |
| 完成待办双写（POST 成功 + 清本地消息 appiaTodo——房间内样式即消失） | ✅ | M7-T3；TodoActions.kt complete（坑 12 双写）+ TodoListRepository.kt invalidate |
| 改提醒（过去时间拒提；保存后条目更新） | ✅ | M7-T3；TodoActions.kt setReminder（date <= now 拒提 RN 同口径） |
| 待办附件跳转（图片→大图查看器鉴权；文件→文档预览） | ✅ | M7-T3；TodoAttachmentJumpTest 四用例（viewer 鉴权参对照 openTodoAttachment） |
| 去处理（跳来源房间；同房保留路由参 jumpToMessageId） | ✅ | M7-T3；RoomTodoScreen.kt onGotoSession（RN :109-118 roomType/routeTitle/fromAgent 原样透传） |
| 工作台宫格（worktable_config 裸 fetch 无 auth 头 + normalizeWorktableGroups 过滤/补前缀/空组丢弃） | ✅ | M7-T4；WorktableApi.kt（独立实例——坑 8）+ LaborRepository.kt（guest E-Learning 过滤） |
| 工作台搜索过滤 + 空态双文案（labor_emptyaftersearch/emptyconfig） | ✅ | M7-T4；LaborScreen.kt:163-167（filterWorktableBySearch + showEmpty RN :76-81） |
| 工作台三支路由（type3 建私信 DM / 消息待办 type10→TodoList / 其余 InAppWeb；url 空→nonsupport） | ✅ | M7-T4；LaborRouting.kt（NONSUPPORT_PATH） |
| 考勤打卡定位权限先询（拒绝 Alert 不打开） | ✅ | M7-T4；LaborScreen.kt:121-131（ensureFineLocation） |
| Labor urlType=1 hash-query 拼参未入 | 🔶 | 协议十七节差异 7（RN 仅 hash-query 调用方用——需要时补路由参数） |
| 泛微三件套（Weavertoken 头+cookie+sessionStorage 防循环 reload hasReloaded_<url> + XHR/fetch 劫持 + 跨页 transition 注入） | ✅ | M7-T5；InAppWebScreen.kt:231,281-297 + InAppWebIntercepts.kt FanweiWeaverTokenGate（进程内去重） |
| 泛微 token 快路竞态（并发首调可能多一次冗余 HTTP） | 🔶 | 协议十七节差异 10（creator 持锁 await——仅冗余无错误） |
| 石墨拼参 appendShimoQueryIfNeeded（org/source/userId/token） | ✅ | M7-T5；InAppWebIntercepts.kt:29-51 |
| WPS 入口改写（WPS_ORG_DOC_HOSTS + docs.appia.vip fallback 'all'） | ✅ | M7-T5；InAppWebIntercepts.kt:53-96 |
| 会议外链拦截（腾讯会议 http(s)→外部浏览器；wemeet:// 拒载） | ✅ | M7-T5；InAppWebIntercepts.kt isTencentMeetingHttpUrl/isWemeetSchemeUrl；AntMeeting 归 M8 原生替换（协议十七节差异 2 已收口） |
| postMessage 桥（SetTitle 改标题 / onResetPasswordSuccess→登出+goBack / navigationStateChange） | ✅ | M7-T5；InAppWebScreen.kt:208-225,629-644（addJavascriptInterface("ReactNativeWebView")） |
| 返回决策链全分支（chat-gpt pop / BACK_CLOSE_URL_SEGMENTS / 根页比较 / ANT_AGENT_FORCE_POP_URLS 两 URL） | ✅ | M7-T5；InAppWebIntercepts.kt resolveInAppWebBackAction（:117-162 全分支） |
| needVPN 探活（HEAD 10s 超时→「需 VPN」错误页）+ RECRUITMENT 隐藏顶栏注入 | ✅ | M7-T5；InAppWebIntercepts.kt vpnHeadProbe + InAppWebScreen.kt:260 buildRecruitmentHideChromeScript |
| 遥测埋点 skip（onError/HttpError/RenderProcessGone 上报） | 🔶 | 协议十七节差异 8（AA 无 analytics 总线——KDoc 注明） |
| Android 泛微注入时序（onPageStarted/Finished+doUpdateVisitedHistory 重现） | 🔶 | 协议十七节差异 11（M6 已证与 RN-on-Android 逐位同）；InAppWebScreen.kt:509-518 |
| AI SSE 客户端（POST 流式 body 手写 + 小写 x-auth-token/x-user-id + FINISH/DONE 立即停读 + chunk text??content??raw 次序） | ✅ | M7-T6；core/network/sse/AiStreamClient.kt（坑 4 停读纪律） |
| bot 端点映射（staffService.bot→saveToStaffServiceAgent / agent.bot+personal.bot→sendToAI / Agent_Bot_List→saveToClawAgent / 否则 null） | ✅ | M7-T6；core/ai/BotEndpoints.kt（映射表完整保留——staffService 使用归 M10） |
| 触发判定（parseBotMentions 保序去重 + prompt 剔除 @bot + shouldTriggerAi） | ✅ | M7-T6；AiTurnResolver.kt（剔除正则 RN 同款） |
| 附件 prompt 模板（image-url / file-proxy——Site_Url） | ✅ | M7-T6；AiPromptBuilder.kt imagePromptTemplate/filePromptTemplate |
| 槽位机制（agentLoadingMsg 注入 u=bot/msg=prompt/msgData{relatedUserMessageId,botReplyIndex}；同 id 不重复注入；真实消息到达 clear + 15s 兜底强清） | ✅ | M7-T7；AiRoomStateMachine.kt + AgentLoadingMessage.kt（坑 5/6 双兜底与防闪烁） |
| 串行驱动（先 setProcessing 再 await clear——顺序反了覆盖槽位；60s 兜底超时） | ✅ | M7-T7；AiRoomStateMachine.kt runAiTurn（坑 6 反重排不变量） |
| 停止按钮（abort+finalize；停止后有文本仍持久化） | ✅ | M7-T7；AiRoomStateMachine.kt setStopping + AgentLoadingMessage finalize（saveAiMessage 恰一次） |
| staffService 客服域（转人工/评价/FastModelMessage 引文 bot.docs citation） | 🔶 | 协议十七节差异 1（划 M10）；AiResponseMessage.kt fastModelMsg 类型桩（AI_FAST_MODEL 纯文本占位） |
| AI 流式中纯文本完成后才切 markdown | 🔶 | 协议十七节差异 5（RN 同构）；AiResponseMessage.kt ai_response 走 M3 链+复制按钮 |
| 跨触发交错（首流未 finalize 即丢弃部分文本） | 🔶 | 协议十七节差异 6（RN unmount 同型） |
| myAgents 虚拟行（个人助手段固定行 + MMKV 缓存 rid + im.create 自聊 DM + fromAgent 自动标题 Agent） | ✅ | M7-T8；AgentChannelRow.kt（agentChannelRowOrNull + ensureAgentRoom） |
| agents 管理（AgentEditor 创建/编辑/禁用/恢复 + base64 快捷添加 + 重复 id 错误识别） | ✅ | M7-T8；feature/agents/ui/AgentEditorScreen.kt + ClawAgentEdit.kt + ClawAgentsApi.kt 四 POST（协议十一节差异 1/2 部分收口注记见 M4 节） |
| AgentEditor edit+success 冒烟等待裁撤 | 🔶 | 协议十七节差异 12（Robolectric 下 onSaved 到达慢——wire 断言保留） |

## 八、M8 会议

> 域性质：PORT（预订/面板/纪要/表单/入口）+ GREENFIELD（Agora 原生 RTC 替换 RN 的 WebView H5——M8 调研 §1.4 VERDICT）。RN 锚 = 入口/线缆/UI 逻辑文件；RTC 本体无 RN 锚。

| 功能点（锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 会议 wire 层 6 接口（meetingRoomBook/RecordUpdate/editPeriodicSingle/cancelPeriodicSingle/meetingRoomList/room members——services/api/meeting.ts） | ✅ | M8-T2；core/network/api/MeetingApi.kt（6 端点 + RoomMembersResult） |
| payload 构建/校验（buildMeetingPayload:69-108——周期 periodicInfo/编辑追加 announcementId/坑 11 手动模式 meetCode+meetUrl 都必填/周期结束不早于单次） | ✅ | M8-T2；core/meeting/MeetingPayloadBuilder.kt（timezone getUTCOffset/腾讯置空串） |
| 会议面板双 tab（RoomMeetingScreen/index.tsx——预订 upcoming 升序在前 past 降序在后（endSec>now 分组 meetingBookingDisplay:19,60,151）+ 纪要 tab ymd 倒序） | ✅ | M8-T3；feature/meeting/ui/RoomMeetingScreen.kt（MeetingTab.BOOKINGS/MINUTES）+ MeetingPanelData.kt |
| 仅 upcoming 且有 joinUrl 显「加入」（坑 9：meetUrl 优先 else meetCode??:tencentMeetingId） | ✅ | M8-T3；feature/meeting/MeetingPanelData.kt resolveMeetingJoin（WEMEET_SCHEME_RE 外跳/AntMeeting 码 URL 分流） |
| 纪要详情纯本地渲染（MeetingSummaryDetailScreen——IMeetingSummary topic/timeBegin/place/host/participant/content blocks/files + buildMeetingMinutesBody，无独立接口） | ✅ | M8-T3；feature/meeting/ui/MeetingSummaryDetailScreen.kt（meeting_summaryplace/host/participants 信息行） |
| 公告流会议/纪要卡（MeetingAnnouncementCard + MeetingMinutesCard——仅面板消费，聊天流无内嵌渲染点） | ✅ | M8-T3；feature/meeting/ui/MeetingCards.kt（RN 两卡组件移植；「聊天流无消费点」研究核对注记 :44） |
| 会议表单全字段（MeetingFormScreen/index.tsx——主题/起止/会议室/与会人默认全房间联邦成员 :162-181/createTxMeeting 默认 true/周期预设 0-6/desc/材料/通知开关/私有网络） | ✅ | M8-T4；feature/meeting/ui/MeetingFormScreen.kt（extraMeetingRoom 保留） |
| 周期三分支编辑（edit-normal/edit-periodic-single/edit-periodic-all——MeetingSubmitPlan 分派） | ✅ | M8-T4；feature/meeting/MeetingSubmitPlan.kt（PERIODIC_SINGLE/PERIODIC_ALL 常量 + periodicFromRecord） |
| 删除（普通/周期整串 type:delete；周期单次 cancelPeriodicSingleMeeting {rid,announcementId}） | ✅ | M8-T3/T4；feature/meeting/MeetingDelete.kt + postSaveRoomSettings roomAnnouncementData |
| 附件「会议」入口门控（canShowMeetingAttachment——Appia_Meeting_Enable≠false + 非 omnichannel + 非单聊，坑 8） | ✅ | M8-T4；feature/meeting/MeetingSubmitPlan.kt canShowMeetingAttachment + AttachmentSelector.kt chatinput_meeting 项 |
| 会议首页（RN「+」菜单/抽屉「会议」行 → MEETING_HOME_URL H5 的原生替代——meetingRoomList 未来窗口平铺 upcoming） | ✅ | M8-T5；feature/meeting/ui/MeetingHomeScreen.kt + ChatListScreen.kt:272-277 drawer_meeting 菜单项（M5-T9 菜单化裁定） |
| RoomInfo 会议行 → 面板（M4 占位收口——renderMeetingRow :356-361） | ✅ | M8-T3；feature/roominfo/ui/RoomInfoScreen.kt:142,258 MeetingRow（M4 节占位行交叉引用收口） |
| InAppWeb AntMeeting 移交（初始 URL/页内跳转/openLink inApp 分支三路 → 原生会议，替换 M6-T5 外链处理） | ✅ | M8-T5；InAppWebScreen.kt resolveAntMeetingHandoff:157 + openLinkRoute:171 + SettingsScreen.kt:149（协议十七节差异 2 收口注记见 M7 节） |
| 腾讯会议记录外跳（tencentMeetingId 兜底码 → wemeet:// 或 https 外开；meeting.tencent.com → 系统浏览器） | ✅ | M8-T3 + M7-T5；MeetingPanelData.kt External 分支 + InAppWebIntercepts.kt isTencentMeetingHttpUrl:110 |
| 原生 RTC 引擎（GREENFIELD：Agora 4.5.2 + credentials 接口 `/api/v2/video/meeting/credentials` spike 结论 B + cert-less 兜底） | ✅ | M8-T1/T6；core/meeting/rtc/AgoraMeetingEngine.kt + MeetingCredentialsApi.kt（协议十九节差异 2/3——字段待抓包收紧/生产 token 待服务端） |
| 会议状态机（meetingSessionStore phase 语义——idle/preheating/inMeeting/minimized；坑 4 consumesBack 仅 inMeeting） | ✅ | M8-T6；domain/meeting/MeetingSessionStateMachine.kt（openPreheat 单次/markInMeeting 两路） |
| 会议房间 UI（GREENFIELD：视频宫格/控制条 mic·camera·share·members·minimize·leave/成员列表/离开确认弹层） | ✅ | M8-T7；feature/meetingroom/ui/MeetingRoomScreen.kt（qa-meeting-room-* testTag 全 + meeting_room_leave_confirm） |
| 视频帧上屏 | 🔶 | 协议十九节差异 4（tile 呈现参与者态——帧渲染需扩 setupRemoteVideo/LocalVideo，M8 范围外后续补；被踢近似 差异 5/成员无名字映射 差异 6 同源） |
| 与老 H5 会议互通 | 🔶 | 协议十九节差异 1（原生 RTC 替换 WebView H5，裁定不要求互通除非同 AppId+token 体系） |
| 浮窗+气泡最小化（RN MeetingForegroundService Kotlin 近乎照抄——SPECIAL_USE/canDrawOverlays 降级链 坑 7/横条 verbatim 参数/应用内可拖拽气泡位置持久化） | ✅ | M8-T8；core/meeting/floatwin/（MeetingForegroundService/MeetingFloatWindow/MeetingFloatPolicy verdict）+ feature/meetingroom/ui/MeetingMiniBubble.kt + MeetingMiniBubbleLayout.kt（KvStore 持久化） |
| 横屏三触发源（useOrientationToggle——共享驱动/顶栏转屏钮 toggle 直调 API/最小化强制竖屏）+ 会中常亮 keep-awake + 共享态顶栏悬浮 | ✅ | M8-T9；feature/meetingroom/MeetingOrientation.kt + MeetingRoomScreen.kt:122（FLAG_KEEP_SCREEN_ON/headerVisible tap toggle） |
| 互斥不对称（坑 6：语音任意非 idle 阻塞会议；会议仅 inMeeting/minimized 阻塞语音 preheating 不算） | ✅ | M8-T9 + M10-T12 双侧收口；MeetingSessionStateMachine.blocksVoiceStart + MainActivity.kt:849 meetingBlockedByVoice（VoicePhase.Idle.name lowercase 陷阱注释在案） |
| 横屏真全屏隐藏系统栏（RN 458994b MainActivity immersive——statusBars+navigationBars hide） | ❌ | T1 追进项 #4（RN 冻结后 delta，docs/m11-rn-delta.md 追进发布清单 4）：AA 横屏无 WindowInsetsController/WindowCompat 调用（全仓 grep 零命中）——横屏会中系统栏仍悬浮。visual 级，GREENFIELD 域内原生等价 UX。见缺口清单 #G11 |
| prefersLandscape 跨会话残留 | 🔶 | 协议十九节差异 11（会议结束记忆不重置，下次恢复横屏一次——边缘影响裁定不修）；MeetingOrientation.kt:29 |
| 会议室时间轴占用简化（MeetingRoomTimelineRow 可视化不移植——名称单选可空/extraMeetingRoom 编辑保留无创建 UI） | 🔶 | 协议十九节差异 7（brief 明文授权简化）；MeetingFormScreen.kt:577 注记 |
| 15 分钟步进取整（RN minuteInterval=15 轮盘——Compose TimePicker 确认时向下取整） | 🔶 | 协议十九节差异 8；MeetingFormScreen.kt:488（TodoReminderPicker 复用） |
| 与会人选择为表单内 dialog（RN VoiceMemberPicker intent:'meeting' 独立屏 + meetingFormDraftStore 往返） | 🔶 | 协议十九节差异 9（M4 选择器组织树数据源不匹配——浮层等义 + 草稿往返未移植）；MeetingFormScreen.kt:617,797 |
| 消息内链接未走 openLink（InlineEnv.onLinkPress 兜底系统浏览器——AntMeeting 链接不自动移交） | 🔶 | 协议十九节差异 13（pre-existing，注记「T13 组装收口」为 M8 计划任务号笔误——M8 无 T13，实际状态：Settings 法律链接已接 openLink:149、聊天 InlineEnv 仍兜底 ACTION_VIEW）；InlineNodes.kt:311 兜底 |
| 遥测 skip（会议错误埋点） | 🔶 | 协议十九节差异 12（AA 无 analytics 总线——KDoc 注明）；同型先例 M7 节遥测行 |
| APK 包体 abiFilters（Agora SDK 24.3MB→93.1MB；arm64-v8a 单 ABI 裁定，x86_64 模拟器装不上） | 🔶 | 协议十九节差异 14（M8 终审裁定；splits/per-ABI versionCode 归 M11 发布工程）；app/build.gradle.kts ndk.abiFilters |

## 九、M9 邮件

> 域性质：PORT（RN 邮件域完整非 stub——M9 调研）。RN 锚 = src/lib/mail + screens/Mail*。

| 功能点（锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| mail client 基座（mailClient.ts——`{server}/mail/api` 基址 + X-Auth-Token/X-User-Id + CookieJar + 401→`/auth/refresh`→重试一次） | ✅ | M9-T1；core/mail/MailClient.kt（独立 OkHttp 不走 RocketSdk） |
| RSA-OAEP 凭证加密（mailCredentialTransport.ts——SPKI PEM + **显式 OAEPParameterSpec(SHA-256,MGF1,MGF1-SHA256)** 坑 1） | ✅ | M9-T1；core/mail/MailCredentialCipher.kt:25（固定向量互操作测试钉死） |
| 公钥缓存按 keyVersion 记账（坑 2 改进——RN 永不刷新是缺陷） | ✅ | M9-T1；MailCredentialCipher.kt CachedKey(keyVersion)（协议二十一节差异 7——比 RN 更优的授权分歧） |
| 403=未开通静默空 / bootstrap 链（useMailInit——GET /accounts 401→exchange→重试；坑 6） | ✅ | M9-T2；feature/mail/MailInit.kt bootstrapMailSession |
| 绑定页（MailAccountSetupScreen——email 预取 user.emails[0]/手输 + 测试连接（先）+ 绑定（后）+ 引导图 step1/2.png 失败隐藏） | ✅ | M9-T2；feature/mail/ui/MailAccountSetupScreen.kt:139-140 guideStep URL |
| 节流专用 toast（坑 5——429/`too many login attempts`/`too frequent` 判定） | ✅ | M9-T2；MailAccountSetupScreen.kt:246-247,276（isMailThrottleError → mail_throttle） |
| 会话列表虚拟邮件行（buildMailChannelChatRow:36-69——`__appia_mail_channel__` assistant 段注入 + Appia_Mail_Channel_Enabled 门控） | ✅ | M9-T2；feature/mail/MailChannelRow.kt + ChatListScreen.kt:103-107,382（mailRow 流派生不触发重建 :124） |
| 收件箱聊天式升序锚底（坑 9——旧→新/首屏滚底/onStartReached 翻旧页 pageSize=30） | ✅ | M9-T3；feature/mail/ui/MailChannelScreen.kt:60,106-122（anchorRequest 锚定链 + 顶部接近翻页） |
| 文件夹 sheet（MailFolderSheet——specialUse 五映射 + path 正则兜底 坑 11 + IMAP Drafts 隐藏 + 虚拟 `__drafts__` + Virus Items 过滤） | ✅ | M9-T3；feature/mail/MailFolderUtils.kt + ui/MailFolderSheet.kt |
| 草稿夹删光自动弹回 INBOX / 下拉刷新（非草稿先 sync 再重拉；草稿只重拉） | ✅ | M9-T3；MailChannelViewModel.kt（triggerMailSync :63） |
| 搜索/未读过滤开关/行未读加粗已读变灰 | ✅ | M9-T3；MailChannelScreen.kt + MailChannelApi.kt searchMailMessages:50（搜索口不透传未读过滤——RN 同构注记） |
| 详情 HTML 渲染 WebView **JS 禁用**（坑 10 等价安全位——RN render-html 无 sanitize） | ✅ | M9-T4；feature/mail/ui/MailDetailScreen.kt（协议二十一节差异 2——更严授权） |
| 详情归一化（normalizeMailMessageDetail——坑 3 五组别名 + 坑 4 `mail-` 前缀剥离时机对齐） | ✅ | M9-T4；core/mail/MailModels.kt:270-314（剥前缀只发生在 URL 构造点——commit 88c7301 对齐 RN 剥前缀时机） |
| 附件缓存/超时/分级（mailAttachmentCache `{msgId}_{part}` + 30s 超时 size/512KB 上限 120s + >25MB 仅下载/5-25MB 确认/≤5MB 直接 + 保存 DownloadManager） | ✅ | M9-T4；feature/mail/MailAttachmentController.kt:47,112（Download/appia + 通知；FileProvider cache_path 挂 :183） |
| 附件预览分级系统查看器（非图片/视频/pdf → ACTION_VIEW 选择器） | 🔶 | 协议二十一节差异 9（无可用 App 时提示失败——RN 同为系统打开）；MailAttachmentController.kt |
| 删除（普通→移 Trash 确认；Trash 内→永久删除二次确认文案不同） | ✅ | M9-T4；MailDetailScreen.kt:69,383-385（isMailTrashFolder path+name 判定） |
| 线程三级降级（mailThread——thread 端点→列表查询 threadId/conversationId→仅锚点；蓝色边框高亮） | ✅ | M9-T4；feature/mail/MailThread.kt（MailThreadLoadResult）+ ui/MailThreadScreen.kt（协议二十一节差异 6——RN 同构降级） |
| 撰写 5 模式（new/reply/replyAll/forward/draft——纯文本正文 + To/Cc/Bcc/主题） | ✅ | M9-T5；feature/mail/ui/MailComposeScreen.kt |
| replyAll 全进 to 无 cc（坑 13——去重去自己）+ Re:/Fwd: 前缀去重 | ✅ | M9-T5；feature/mail/MailComposeRecipients.kt（验收断言口径注记） |
| 联系人联想（≥2 字符 + 300ms debounce + 仅 To 域聚焦——contacts/search） | ✅ | M9-T5；MailComposeScreen.kt:79,197-212（CONTACT_DEBOUNCE_MS=300） |
| 附件 JSON 内联 base64（坑 12——10 个/25MB 上限 + 流式读盘防峰值；不复用 M3 upload 链） | ✅ | M9-T5；feature/mail/MailComposeAttachments.kt（协议二十一节差异 4——分块 3KB 流式 RN 更优注记） |
| 草稿链（dirty beforeRemove 拦截确认→Compose BackHandler 等义 + /drafts CRUD + drafts/:id/send 直发；new 直发不落服务端草稿） | ✅ | M9-T5；MailComposeScreen.kt:87-88,355-357 + MailChannelApi.kt:71-178（RN 同构 new 无残留） |
| 转发 forward-draft 失败降级本地预填 | ✅ | M9-T5；MailChannelApi.kt createForwardMailDraft:145 + MailComposeScreen 降级分支 |
| DDP mail.updated 角标（mailUpdatedHandler——stream-notify-user 全量帧被动解析不 subscribe + 坑 7 文件夹归一化剥 `\`+大写） | ✅ | M9-T6；core/mail/MailUpdatedHandler.kt:45-47（uid 含 `/` 容错 split 语义） |
| 角标持久态（mailChannelStore version 2——pinned/lastVisitAt/未读/预览 per-org 分桶 + migrate + markChannelVisited 清零） | ✅ | M9-T6；feature/mail/MailBadgeStore.kt:85,100,120-123（MMKV 单键存 RN v2 快照；AA 无 v1 存量注记） |
| 未读启发式 100 条天花板（坑 8——只扫 INBOX 首页 100 条照抄；无 mark-read 端点靠 GET detail 隐式置读） | 🔶 | 协议二十一节差异 3/5（RN 同构天花板 + 已读依赖服务端）；MailChannelApi.kt:225-250 fetchMailUnreadCount |
| 行预览 `{fromName}：{subject}` + 时间列 | ✅ | M9-T6；MailChannelApi.kt buildLatestMailPreview:189 + fetchLatestMailPreview:207 |
| 推送 newCount 缺失按 0（只刷新预览不计角标） | 🔶 | 协议二十一节差异 8（RN 同构）；MailUpdatedHandler 消费侧 |
| 无独立账户管理页（RN 也无——解绑/删除/签名/多账户） | 🔶 | 协议二十一节差异 1（「账户设置」=绑定页，勿排任务）；M9 计划 Global Constraint |
| 邮件真收发边界（服务端 mail 网关 + 真邮箱 = 用户域依赖） | 🔶 | 协议二十节前置 1/2 + 差异 10（Maestro 仅「行显示+点击进入」冒烟边界） |

## 十、M10 语音通话

> 域性质：GREENFIELD 完整实现（RN 语音 M0 真机门禁冻结点未通过——`docs/VOICE_CALL_M0_GATE.md`/`VOICE_CALL_GAP_STATUS.md`：逻辑在 store/UI 待真机、录音摘要部分实现、busy 无信令源）。功能面锚 = RN 仓既有语音代码（src/lib/voice 50 纯函数 + features/voice + stores/voiceSessionStore.ts）+ AA spec §3 UI 清单；🔶 密集引协议二十三节 12 项。

| 功能点（锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| callMsg 解析/状态判定决策包 A（lib/voice——parseCallMsg 双层 JSON/callRecordStatus exist 混杂形态/groupVoiceCall 群组判定/isRoomCallMsgActive/reconcileStale） | ✅ | M10-T1；core/voice/lib/（ParseCallMsg/CallRecordStatus/GroupVoiceCall/IsRoomCallMsgActive/IsRoomVoiceInProgress/ReconcileStaleLocalRoomCallMsg——RN 测试即规格全转写） |
| 决策包 B（shouldPresentIncoming/voiceCallDismissed/isVoiceAnsweredOnOtherDevice/resolveAgoraJoinUid 找不到绝不回退 8888/shouldAcceptRemoteUserJoined 不做精确过滤/shouldEndCallFromCallMsg/queueOtherIncoming/mapCallMsgToVoiceUsers） | ✅ | M10-T2；core/voice/lib/（ShouldPresentIncoming/VoiceCallDismissed/IsVoiceAnsweredOnOtherDevice/ResolveAgoraJoinUid/ShouldAcceptRemoteUserJoined/ShouldEndCallFromCallMsg/ShouldQueueOtherIncoming/MapCallMsgToVoiceUsers——历史坑 KDoc 保留） |
| 决策包 C（房间横幅 shouldShowRoomVoiceJoinBanner/选人 buildVoiceReceivers/跨 org voiceOrgLabels+findLoginCandidateForVoiceOrg/pickDirectVoicePeer/groupRejoinEligibility 三函数） | ✅ | M10-T3；core/voice/lib/（ResolveRoomVoiceJoinBanner/BuildVoiceReceivers/VoiceOrgLabels/FindLoginCandidateForVoiceOrg/PickDirectVoicePeer/GroupRejoinEligibility） |
| 状态机（voiceStateMachine.ts:12-55 全迁移矩阵 + VoicePhase 十相位） | ✅ | M10-T3；domain/voice/VoiceSessionStateMachine.kt + core/voice/VoicePhase.kt（表驱动测试全行对照） |
| oncall 网关客户端（oncallApi.ts:116-285 verbatim——POST `token` 头/GET `X-Auth-Token`/notify 两头皆不带/一律 `org` 头/40002→invalidate/路径尾问号原样） | ✅ | M10-T4；core/voice/OncallApi.kt（OncallContext/OncallTokenExpiredException/13 端点全量） |
| 网关常量（voiceEnv.ts——PROD/QA host `.dev.` 分流/AGORA_APP_ID 硬编码/org=Enterprise_ID trim 回退 bitmain）+ token 缓存 2 天 TTL | ✅ | M10-T4；core/voice/VoiceEnv.kt（VOICE_HOSTER_AGORA_UID=8888）+ VoiceTokenCache.kt |
| Agora 语音引擎（agoraRtc.ts 284 行——LiveBroadcasting+Broadcaster/音量指示 500ms/加密 Aes128Gcm/回铃 startAudioMixing 音量 10/500ms 音频路由延迟窗口/released 守卫） | ✅ | M10-T5；core/voice/rtc/VoiceRtcEngine.kt:117-127（协议二十三节差异 6——500ms 待真机裁定） |
| 通话计时器（voiceCallTimers——主叫 30s 提示/65s 挂断 + 被叫 65s，recordId 锚定） | ✅ | M10-T6；core/voice/VoiceCallTimers.kt（HOST_HINT_MS=30_000/HOST_END_MS=CALLEE_END_MS=65_000 锚不符 no-op） |
| 主叫链编排（voiceSessionStore :1034-1242——占位 callMsg→token→init→join(8888)→回铃；40002 重试一次/代数防竞态/generation mismatch 丢弃/endCall 单飞/ringing 相位 cancel·接通后 leave） | ✅ | M10-T7；domain/voice/VoiceSessionController.kt startOutgoing:144（终审修正 32c2afa toast 键/IO 读体/乐观清除） |
| 被叫链（:1244-1439——presentIncoming 无 channelId 跳过/accept 解析 uid 带 recordOrg/reject 标记 dismissed/跨 org ctx 切换） | ✅ | M10-T8；VoiceSessionController.kt（VoiceUidResolveException Controller 捕获） |
| callMsg 同步裁定面（:806-1032 逐段——sync 他房活跃忽略/空闲发现呈来电/残缺快照抑制 :905-936/stale ended for host/单聊 peer hangup/群组 invitee 计数增长重锚计时器/第二路入队） | ✅ | M10-T8；VoiceSessionController.kt syncCallMsgFromRoom（VoiceSyncCallMsgTest 十用例） |
| joinExisting/joinOngoing/startFromMessage（:1734-2078——横幅加入刷新缺 channel 记录/消息卡片点击 idle 守卫） | ✅ | M10-T8 + 终审 089d599（等待语义与读库护栏） |
| 三源同步观察器（useRoomListChats:61 + useRoomChatByRid:29 双源——chatDao observe 全表 callMsg 变化→sync；同值不重触发） | ✅ | M10-T10；domain/voice/CallSyncWatcher.kt（AA 全表 observe 统一——同 rid 幂等由 Controller 判定） |
| 推送语音分流（voicePushBridge/voicePushIngress——isCallEnd 丢弃/未登录入队/callMsg 嵌入短路 fetch/无则 getRecordStatusV2 拉） | ✅ | M10-T10 + M6 桩收口；domain/voice/VoicePushIngress.kt + VoicePushQueue.kt + PushClickRouter onVoiceOncall（协议十五节差异 3 收口——M6 节 oncall 行交叉引用） |
| 推送点击离线走 V2 fetch 全量（RN 最小合成即响铃） | 🔶 | 协议二十三节差异 12（AA 严格更强——入队重试 vs 即响铃） |
| Telecom ConnectionService 自管连接（GREENFIELD 增强——PhoneAccount SELF_MANAGED/reportIncomingCall 降级 false 不抛/接通 setActive/挂断 setDisconnected/权限引导 Settings） | ✅ | M10-T9；core/voice/platform/TelecomCallBridge.kt + VoiceConnectionService.kt（协议二十三节差异 7——RN CallKeep iOS-only，AA 双端系统级来电为增强非差异） |
| 主动呼出不上报 Telecom（spec §0 裁定——系统通话记录无呼出条目） | 🔶 | 协议二十三节差异 3（RN Android 侧同样不写系统记录） |
| microphone FGS + 通知渠道（VoiceForegroundService.kt 72 行移植——voice_active IMPORTANCE_LOW + voice_incoming IMPORTANCE_HIGH + API 30+ microphone type） | ✅ | M10-T9；core/voice/platform/VoiceForegroundService.kt + PushNotificationChannels（5 条 i18n 补迁 en+zh） |
| 铃声双轨（voiceRingtone.ts AppState 分流——前台 AndroidVoiceRinger RingtoneManager+震动 [0,500,1000,500]；后台交 Telecom 系统铃声） | ✅ | M10-T9 + 终审 c15f1bf（self-managed 无系统铃——前台应用内响铃补偿）；core/voice/platform/AndroidVoiceRinger.kt |
| 通话 UI 全家（VoiceCallHost 189 行相位分流——Idle 不渲染/IncomingRinging 横幅→全屏确认/活跃相位全屏 overlay/宫格波纹/第二路 Banner 叠加/三型 Alert/uiMinimized 气泡接管） | ✅ | M10-T11；feature/voice/ui/VoiceCallHost.kt + VoiceIncomingBanner/VoiceCallOverlay/VoiceParticipantCenter/VoiceOtherCallBanner/VoiceOtherCallAlert（qa-voice-* testTag 全） |
| 第二路三型弹窗（getOtherIncomingAlertType 1/2/3——同 org 单聊「接听将挂断」/join 邀请「加入群聊通话」/跨 org 切换确认） | ✅ | M10-T11；core/voice/lib/GetOtherIncomingAlertType.kt + VoiceOtherCallAlert.kt（RN 1:1） |
| 第二路横幅与 Alert 同显 z 序相反 | 🔶 | 协议二十三节差异 11（边缘场景裁定不修） |
| 选人页（VoiceMemberPickerScreen 516 行——intent=start/invite 分支 + 组织树/搜索/多选/chips + 群聊单人拦截 :46-49） | ✅ | M10-T11/T12；feature/voice/ui/VoiceMemberPickerScreen.kt（复用 M4 选人基建裁剪 intent=start 单源；终审 f2c49ff chips 陈旧修正） |
| MemberPicker 失败反馈全局弹窗层（RN 页面内 Alert） | 🔶 | 协议二十三节差异 9（Task 11 M-6 授权分歧——VoiceCallHost 常驻根部） |
| 最小化气泡（VoiceCallMiniBubble——拖拽累积/贴边吸附/位置 KvStore 持久化杀进程保留/单击恢复不掉线） | ✅ | M10-T12；feature/voice/ui/VoiceCallMiniBubble.kt:103-127（loadVoiceBubblePosition/saveVoiceBubblePosition；终审 8dce79a 边界修正） |
| 出应用系统级通话浮窗 | 🔶 | 协议二十三节差异 2（v1 以 FGS CATEGORY_CALL 常驻通知承载——RN CallKeep 浮窗 Android 对位缓 backlog-m10 项 2） |
| 通话录音与摘要 UI（VoiceRecordControl——RN 侧部分实现：手动开关有/查看录音页无） | 🔶 | 协议二十三节差异 1（i18n key 已迁移入库（strings.xml:1076,1087）/UI 面缓 backlog-m10 项 1；VoiceCallOverlay.kt:148 录音钮裁定③不渲染） |
| busy 相位无信令源（状态机 busy 保留/服务端不回信令——RN 同构） | 🔶 | 协议二十三节差异 4（靠 65s 超时兜底；backlog-m10 项 3——RemoteBusy 事件已在位）；VoiceSessionStateMachine |
| onUserOffline 故意 no-op（对齐旧版 commit 096dd3a——远端掉线不挂断靠 callMsg 同步/轮询收尾） | 🔶 | 协议二十三节差异 5（RN 同构——shouldAcceptRemoteUserJoined KDoc 历史坑保留） |
| 主叫入口双路（MemberProfile「语音通话」钮 handleVoiceCall + 附件菜单 voice_chat——单聊直拨/群聊跳选人页） | ✅ | M10-T12；MemberProfileScreen.kt:253-265（qa-member-profile-voice，M4 期占位收口）+ AttachmentSelector.kt:98,152-155 |
| 房间加入横幅（RoomVoiceJoinBanner——通话中房间顶部「加入」横幅）+ 通话消息卡片（VoiceChatMessage calling/talking 卡片点击 startFromMessage） | ✅ | M10-T12；feature/voice/ui/RoomVoiceJoinBanner.kt + feature/chat/ui/VoiceChatMessageCard.kt（MessageRow.kt:428 消费 + voicePhaseIdle 守卫） |
| 跨 org 通话（switchSessionToVoiceCallOrg——切主 org 数据面/来电方 org 名展示/多组织另一 org 进行中横幅 VoiceCrossOrgNotifier） | ✅ | M10-T12；VoiceSessionController.kt:992-1007 switchToVoiceOrg + feature/voice/ui/VoiceCrossOrgNotifier.kt:85 poll/switchOrg（MainActivity.kt:3275,3396 onResume 轮询装配） |
| openVoiceChatFromRoom 预检口径（parseCallMsg 无 DB 合并 vs RN parseEffectiveRoomCallMsg） | 🔶 | 协议二十三节差异 8（Task 12 L-1 裁定——Controller 内部守卫兜底正确性） |
| ON_RESUME 音频重应用三相位（InCall/Connecting/OutgoingRinging——reapplyInCallAudio 直调） | ✅ | M10 终审 ce4cfd5 收口协议二十三节差异 10（与 RN 三相位口径一致后成立）；VoiceRoomHandle |
| 互斥收口（会议 inMeeting/minimized 阻塞语音 + 语音任意非 idle 阻塞入会——meeting_end_voice_first 双向） | ✅ | M10-T12；VoiceSessionController.kt:146 meetingPhaseProvider + MainActivity.kt:846-850（协议十八节自测 8 对向收口） |
| 登出/切组织汇入（logout 序列 endCall("logout") + watcher.stop + ringer.stop + FGS stop——与 mailBadge.reset 同位） | ✅ | M10-T12；feature/voice/VoiceRoomHandle.kt shutdown + AuthRepository.kt（8dce79a 终审 shutdown 语义修正） |
| 麦克风运行时权限门（requestVoiceMicrophonePermission 三态——拒绝 Alert voice_error_mic_denied） | ✅ | M10-T12；feature/voice/VoiceMicGate.kt（VoiceSessionDeps.requestMicPermission 生产装配体；granted/拒绝/异常三态收敛） |
| 回铃资源（calling_sound.wav——RN iOS Copy Bundle Resources 未验收项） | ✅ | M10-T11；app/src/main/assets/voice_calling.wav（HOST_RINGBACK_ASSET=/assets/voice_calling.wav——VoiceSessionController.kt:1591；c1e400a 路径断言对齐） + drawable voice_call_bg/call_end |
| 群聊宫格动态宽与等待动画（VoiceParticipantCenter 140 行——波纹/说话角标/静音角标/参与者 tile 增减） | ✅ | M10-T11 + 终审 f2c49ff（宫格动态宽与脉冲修正）；VoiceParticipantCenter.kt（RN 无旧版 showFrequency 音量波纹——VOICE_CALL_GAP_STATUS「部分实现」清单，AA 按 RN 现状不复刻） |
| RN 未完成项不复刻清单（react-native-notifier 顶栏/消息长按「重拨」/sendOncallMessage 补发——RN 仓「未做/未验收」清单项） | 🔶 | 总纲 M10 裁定「完整实现不等 RN（RN 版仍在 M0 gate）」+ 冻结基线裁定「语音通话无 RN 参照物→方案可重做」——功能面锚=RN 仓既有代码（VOICE_CALL_GAP_STATUS.md 冻结点自认未做项不虚构）；登记防 T5 误报缺口 |

## 附：状态分布（全量）

| 域 | 行数 | ✅ | ❌ | 🔶 |
|---|---|---|---|---|
| M1 认证多组织 | 24 | 20 | 0 | 4 |
| M2 会话列表单聊 | 26 | 22 | 2 | 2 |
| M3 消息完整 | 37 | 20 | 6 | 11 |
| M4 群组联系人 | 39 | 30 | 1 | 8 |
| M5 搜索 presence 设置 | 38 | 32 | 0 | 6 |
| M6 推送自更新 | 27 | 16 | 1 | 10 |
| M7 待办工作台 AI | 41 | 31 | 0 | 10 |
| M8 会议 | 30 | 20 | 1 | 9 |
| M9 邮件 | 30 | 25 | 0 | 5 |
| M10 语音通话 | 40 | 30 | 0 | 10 |
| 合计 | 332 | 246 | 11 | 75 |

> ❌ 行与缺口编号 #G1-#G11 的对应及严重度评定见任务报告结构化缺口清单（T5 消费输入）：
> #G1 只读横幅（M2）/ #G2 组织 Tab 条（M2）/ #G3 聊天内公告卡（M3）/ #G4 多选设为公告（M3）/ #G5 多选新频道（M3，与 T1 delta 追进项 #3 同源）/ #G6 回复态草稿（M3）/ #G7 扫码（M4）/ #G8 公告置顶+originSender（M3 节登记，与 T1 追进项 #1 同源）/ #G9 合并转发 appiaBaseUrl（M3，与 T1 追进项 #2 同源）/ #G10 手动检查更新弹窗未接线（M6，main-chain）/ #G11 会议横屏隐藏系统栏（M8，与 T1 delta 追进项 #4 同源，visual 级）。T5 汇总时 #G5/#G8/#G9/#G11 与 T1「追进发布」清单防双计。
> 扩展域（M5-M7）差异均有协议十三/十五/十七节或里程碑裁定背书（🔶 26 项）；M5-M7 期间已收口的早期差异（十三.10 跳转平跳、十五.3 oncall、十七.2 AntMeeting、十七.9 跨组织刷新）按 ✅+收口注记收录。#G10 为 Fix round 1 拆行（原误挂 ✅ 行内的漏出验收项）。
> RTC 域（M8-M10）差异均有协议十九/二十一/二十三节或里程碑/spike 裁定背书（🔶 24 项）；M8-M10 期间收口的早期差异（十五.3 oncall 分流、十七.2 AntMeeting 拦截、十一.5/11.12 语音·会议占位行）在对应 ✅ 行内以交叉引用注记收录。#G11 = T1 追进项 #4 预归位（458994b，visual 级，controller 可降级记档）。
