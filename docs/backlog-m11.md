# M11 Backlog（收尾验收缺口记档 + delta 记档 + 三页评估索引）

> 记录性质：本页收编 M11 收尾验收（全量 checklist 334 行——T8 收口含 G13 新增行 + RN delta 69 提交盘点）裁定为
> 记档不修的全部事项。裁定框架 = spec §1-4「主链路修/次要记档」：main-chain 缺口走 T5
> 修复槽位（本里程碑内修复），minor/visual 记档本页。
> checklist 全景与逐行证据：`docs/m11-feature-checklist.md`；delta 盘点：`docs/m11-rn-delta.md`。

## 一、修复槽位处置（T5 Step 2，main-chain 3 项——均在 ≤3 上限内）

| # | 事项 | 处置 |
|---|------|------|
| G9 | 合并转发消息级 appiaBaseUrl 透传（跨主体附件裂图/404） | **修复**（T5 槽位 1，commit ebc1171，RN delta 追进 #2 同源；checklist 行已翻 ✅） |
| G10 | 设置页手动检查更新接线应用内弹窗（showManual 零调用/宿主挂载作用域断链） | **修复**（T5 槽位 2，commit fcf7ab9；checklist 行已翻 ✅） |
| G13 | 首启隐私同意门禁（T7 审查新发现——RN 冻结点内原生合规门禁 AA 零实现） | **修复**（T5 槽位 3，commit f1e6e4e：PrivacyConsentStore MMKV + Compose 同意屏 + MainActivity 根门禁；checklist M1 域新增 ✅ 行） |

## 二、checklist 缺口记档（minor 9 + visual 1）

| # | 事项 | 来源 | 建议归属 | 状态 |
|---|------|------|----------|------|
| G1 | 只读房横幅：RoomFooter 只读优先替换输入区（roomreadonly_banner 键已迁无消费方；AA 现长按菜单已拦 + isRoomReadOnly 仅拦菜单不换底栏） | checklist M2 域（:64） | feature/chat RoomScreen（只读态底栏替换） | 记档 |
| G2 | 组织 Tab 条（RoomListOrgTabs：多主体 tab 快捷切换 + 管理弹层 + tab 未读点；AA 组织切换走 ⋮ 菜单 OrgSwitchSheet 完整可用——M5-T9 菜单化先例） | checklist M2 域（:67） | 远期形态增强（交互形态缺口非功能缺失，controller 裁定记档） | 记档 |
| G3 | 聊天内公告卡（room_*_announcement 专卡：标题+分隔线+md 正文+图片行+文件；现走普通气泡行） | checklist M3 域（:102）/ delta 62494cb 联动 | feature/chat MessageRow（公告型专卡分支） | 记档 |
| G4 | 多选「设为公告」（MultiSelectActionBar showAnnouncement 门 + saveRoomSettings roomAnnouncementData） | checklist M4 域（:103）/ delta 88d1a61 联动 | feature/chat 多选操作条（若做含 88d1a61 域名补全） | 记档 |
| G5 | 多选「新频道」创建讨论（createDiscussion prid/pmid/t_name；T1 追进 #3 冻结后新增） | checklist M3 域（:104）/ delta d82f1c1 | feature/chat 多选操作条 + ChannelsApi | 记档 |
| G6 | 回复态草稿不持久化（draft_reply_msg_id 恒空——重进房间回复态丢失） | checklist M3 域（:110） | feature/chat DraftRepository | 记档 |
| G7 | 扫一扫（ScanScreen 相机扫码 + 相册识码 + SSO QR；AA 无任何扫描器。⚠️ RN 入口为 "+" 菜单——AA 无 "+" 菜单，若做需重锚 ⋮ 菜单） | checklist M4 域（:152）/ delta e19a64e 联动 | 远期（含相机/MLKit 依赖引入评估） | 记档 |
| G8 | 公告列表 updateTime 降序 + originSender 展示（「XX 发布的消息，由 YY 设为公告」；T1 追进 #1 同源） | checklist M3/M4 域（:101） | core/chat RoomAnnouncements + RoomAnnouncementScreen | 记档 |
| G11 | 会议横屏真全屏隐藏系统栏（WindowInsetsController hide；T1 追进 #4 冻结后新增，visual 级——GREENFIELD 域内原生等价 UX） | checklist M8 域（:305）/ delta 458994b | feature/meetingroom MeetingRoomScreen | 记档 |
| G12 | 邮件行左右滑快捷动作（左滑标未读 setUnreadCount(1)+置顶 setPinned/右滑标已读 markChannelVisited；setUnreadCount 生产已有 SessionBootstrapOrchestrator.kt:108 接线仅 setPinned 死；方向以 handler 语义为准） | checklist M9 域（:327） | feature/roomlist 邮件行滑动手势 | 记档 |
| G9 残余 | 视频内联缩略图（MessageAttachments.kt:190）与音频点击 URL（:233）未传 att.appiaBaseUrl 第五参——跨主体合并转发场景残余两处（RN MessageVideo 不渲染缩略图/音频播放页为 AA 自有 binding，均无 RN 对齐目标；播放主路径 buildViewerVideo 已修）。顺手补一行即可，普通消息零影响 | G9 槽位审查 M-1/M-2 | feature/chat MessageAttachments 触碰时 | 记档 |
| G10 残余 | checkUpdating 复位无 finally（SettingsScreen onPressCheckUpdate）：非取消异常逃逸路径（回退 openUrl 的 ACTION_VIEW 理论 SecurityException）会锁死检查更新按钮直至重进屏；CancellationException 路径与旧 finally 写法等价。顺手包回 try/finally 即可 | G10 槽位审查 M1 | feature/settings SettingsScreen 触碰时 | 记档 |

## 三、🔶 重估裁定（T4 后台账挂账项）

| # | 事项 | 裁定 |
|---|------|------|
| 快捷回复屏（协议十三节差异 5） | RN 全链 = CustomQuickReplyScreen（128 行编辑屏）+ ChatInputBar 表情键盘内 AppiaQuickReplyPicker；AA 两端均缺（无表情键盘宿主 + 设置行 no-op）。**裁定记档**：两块均非主链路（不影响既有消息收发）；协议差异节措辞更新归 T8 顺手（「行渲染不可导航」→「编辑屏与输入侧消费均未实现」） | 记档（minor） |
| 抽屉待办卡（协议十七节差异 3） | 附件缩略图/元信息框/高优徽标为卡内视觉增量，total+首条标题已覆盖信息职责。**维持 🔶 记档** | 记档（visual 增量） |

## 四、RN delta 记档索引（69 提交四类裁定，明细见 docs/m11-rn-delta.md）

- 记档不做 38（已覆盖佐证 19——AA 生而用修正后语义/已有同语义实现）/ RN 域外 27（docs/specs 12 + iOS 5 + 会议 WebView 架构 8 + 库补丁 1 + 控件 bug 1）/ 追进发布 4（#1=G8 #2=G9 #3=G5 #4=G11 归位本页 §一/§二）/ 无法离线判定 0。

## 五、三页 backlog M11 评估索引（T5 Step 0，头行已落各页）

| 页 | 结论 |
|---|---|
| backlog-m6.md | #1–#8 域内 minor 随触碰收敛；#9 sink 归多组织未读角标特性（远期）；#10 已完成；FCM taskNames 启发式等工具链项记档不做（等 FCM 真正启用再议） |
| backlog-m8.md | #2 视频帧上屏记档不做（发布不阻塞，RTC 用户域验收覆盖）；#3 abiFilters 归 M11-T6 发布工程；#4–#13 minor 批量记档；#1/#14 用户域；#15 低危 |
| backlog-m10.md | #1 录音/摘要 + #2 系统级浮窗记档不做（依赖服务端/v1 由 FGS 通知承载）；#3 busy 信令无法离线判定；#4/#5 用户域；#6–#13 低危随触碰 |
