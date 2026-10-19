# M11 · RN 冻结后 delta 盘点（c92e3df..HEAD）

- **冻结点**：`c92e3df`（2026-09-09 17:44 +0800，fix(push): JS 侧推送监听器缺失）
- **RN HEAD**：`5093004`（2026-10-15 15:17 +0800）
- **总量**：69 commits；diff 规模 119 files，+6304 / −257
- **方法**：逐条读 message + `git show --stat` 涉及文件，对照 AA M1-M10 实现与里程碑裁定归入四类；「已覆盖」为「记档不做」的佐证子类（AA 已有同语义实现，列 AA 文件为证）

## 裁定表（git log 序，新→旧）

| sha | 摘要 | 类目 | 裁定理由 | AA 影响 |
|---|---|---|---|---|
| 5093004 | auth 登出/冷启动登录态埋点（log.save 诊断） | 记档不做 | 埋点类；AA 无埋点体系（M0-M10 无 observability 域） | 无 |
| 4140bd2 | Android 13+ POST_NOTIFICATIONS 运行时权限 | 记档不做（已覆盖） | AA M6 已做且为记录在案的故意分歧：`core/push/NotificationPermissionGate.kt:31`（"Android 13+ divergence from RN"） | 无 |
| a8c8579 | 版本号升至 1.22.5 | 记档不做 | 版本号 churn；AA versionCode 独立线（0.x / 下限守卫 28187900） | 无 |
| 6f9fb6e | docs: 会议与文档语义链接设计稿 | RN 域外 | specs 文档，无代码 | 无 |
| eb18e95 | auth 登录日志上报 log.save（对齐旧版 ILogInterface） | 记档不做 | 埋点类；AA 无 log.save/RUM 通道 | 无 |
| e6db5a4 | observability rehydrate 补设 RUM 用户 | 记档不做 | ARMS RUM 埋点；AA 无 RUM | 无 |
| 7601535 | voice: RNCallKeep @ReactMethod 重载闪退补丁 | RN 域外 | RN 库补丁（patches/react-native-callkeep）；AA 语音为原生 Telecom/ConnectionService（M10），无 RNCallKeep | 无 |
| d13cbeb | docs: 年会签到签字采集/大屏轨迹细则 | RN 域外 | specs 文档 | 无 |
| 62494cb | announcement: 聊天内公告卡片短文本塌缩补 minWidth | 记档不做（联动注记） | 样式修复（minWidth 100 / 标题字号 18）；**AA 缺整个聊天内公告卡组件**——`feature/chat/ui/MessageRow.kt:73` 注记"announcement 走普通行=RN"与 RN 实际不符（RN `RoomMessageRowBody.tsx:41` 对 room_*_announcement 渲染 AnnouncementMessage 专卡），疑似实现误读。基线缺口归 T2 清单复核；若补卡须含本提交样式 | 公告消息渲染形态与 RN 不一致（普通气泡 vs 专卡）——T2 复核 |
| 32c8984 | announcement: 公告列表最新置顶 + 消息转公告展示原始发送人 | **追进发布** | AA `core/chat/RoomAnnouncements.kt` parseMainAnnouncements 无 updateTime 降序排序、无 originSender 字段；`feature/roominfo/ui/RoomAnnouncementScreen.kt` 列表序与发送人展示受影响；预览文本取最新一条同需对齐 | **追进项 #1**（M4 域，用户可见） |
| 7a36873 | version | 记档不做 | churn | 无 |
| 80eae96 | android: 剔除 callkeep 误并的电话权限 | 记档不做 | RN manifest 清理；AA 电话权限（MANAGE_OWN_CALLS 等）为 M10 Telecom 有意声明，从无 callkeep 污染 | 无 |
| acb22af | docs: 年会签到签名契约/大屏动画设计 | RN 域外 | specs 文档 | 无 |
| d866ec5 | voice: iOS 通话接入 CallKit | RN 域外 | iOS 专属 | 无 |
| b37558c | voice: 拨号被会议预热态误判拦截 | 记档不做（已覆盖） | AA 生而用修正后语义：`domain/voice/VoiceSessionController.kt:147` 仅 inMeeting/minimized 拦截（预热态不算进行中） | 无 |
| 13c442f | docs: 年会签到跨系统整体方案 | RN 域外 | specs 文档 | 无 |
| 1a7daa6 | docs: 年会签到 gateway 复用结论 | RN 域外 | specs 文档 | 无 |
| b38a97b | docs: 年会签到手写签名设计 | RN 域外 | specs 文档 | 无 |
| 2aacb25 | docs: 年会签到方案1确认稿 | RN 域外 | specs 文档 | 无 |
| ced706f | Merge fix/preheat-backhandler | 记档不做 | merge 节点（内容见 2b1cb21） | 无 |
| 2b1cb21 | meeting: 预热态 BackHandler 不再吞返回键 | 记档不做（已覆盖） | AA `domain/meeting/MeetingSessionStateMachine.kt` consumesBack 仅 inMeeting 为真（M8 坑 4 在案：preheating/minimized 不消费交还导航） | 无 |
| 7ed6e22 | chore(ios): 版本号 1.22.3 | RN 域外 | iOS churn | 无 |
| 76e14c4 | Merge fix/minimize-backhandler | 记档不做 | merge 节点（内容见 f6eaef2） | 无 |
| e19a64e | scan: 模拟器缺扫码 Pod 降级提示 | RN 域外 | iOS 模拟器构建问题（GoogleMLKit arm64）。**注记：AA 无扫码功能**（RN 冻结基线有 ScanScreen/扫一扫）——基线缺口归 T2 清单复核 | 无（delta 本身 iOS 专属；扫码缺口为 T2 事项） |
| 4a959d1 | Merge feat/mobile-share-real-landscape | 记档不做 | merge 节点 | 无 |
| c3062f7 | Merge fix/landscape-immersive-scope | 记档不做 | merge 节点 | 无 |
| f6eaef2 | meeting: 最小化态 BackHandler 不再吞返回键 | 记档不做（已覆盖） | AA consumesBack minimized=false（M8 坑 4 同源） | 无 |
| 46b6a23 | chore(ios): 条码扫描 Pods 1.22.2 | RN 域外 | iOS 专属 | 无 |
| 676a389 | meeting: 列表态横屏顶部栏 tap toggle | RN 域外 | 会议 WebView 最小化壳（MeetingMinimizeShell）列表态控件；AA 最小化为原生气泡（M8-T8），无 WebView 壳 | 无 |
| 0615b21 | meeting: 多主体切换废弃旧预热 WebView | RN 域外 | WebView 预热架构；AA 会议原生替换（M8 调研 §1.4 VERDICT），预热为原生 openPreheat 单次语义 | 无 |
| ad23a49 | meeting: WebView 预热+postMessage SPA 秒进 | RN 域外 | 同上——WebView 桥接架构 AA 已原生替换，原生入会无预热桥 | 无 |
| 5445110 | Merge feat/mobile-share-real-landscape | 记档不做 | merge 节点 | 无 |
| 23ef1fa | appiaim-mobile: 禁用 WebView 内置缩放 | RN 域外 | 会议 WebView 缩放治理（配合 web 端自管理双指缩放）；AA 无会议 WebView | 无 |
| aa93319 | forward: 合并转发附件透传 appiaBaseUrl | **追进发布** | 服务端在 msgData.messages 每条消息带 appiaBaseUrl（附件所在主体域名）；AA `feature/chat/forward/ForwardMergeMessage.kt` mapForwardMessagesToEntities 无该字段（ForwardMessageItem/MessageEntity 均缺列），消息级域名未合并进附件——跨主体合并转发图片裂图/文件 404。`core/media/AttachmentUrlFormatter.kt` 已支持 appiaBaseUrl 参数，缺的是透传链 | **追进项 #2**（M3 域，用户可见） |
| 88d1a61 | room: 多选设为公告附件相对链接补全主体域名 | 记档不做（联动注记） | **AA 无「设为公告」多选动作**（`feature/chat/ui/MessageActionsSheet.kt:64`"归 M7 不移植"，M7 未收编）——基线缺口归 T2 清单复核；若补该动作须连带本提交域名补全 + 32c8984 originSender 语义 | 无（动作缺失为 T2 事项） |
| 5fc2df5 | properties | 记档不做 | churn | 无 |
| 6600e48 | Merge feat/mobile-share-real-landscape | 记档不做 | merge 节点 | 无 |
| d82f1c1 | multiSelect: 多选新增新频道（创建讨论） | **追进发布** | rooms.createDiscussion（prid=当前房/pmid=全部选中/t_name=首条消息去链接前缀）+ showAppiaTag bit0=0 门控 + 成功退多选清栈跳转；AA `feature/chat/ui/MessageActionsSheet.kt` MultiSelectActionBar 仅逐条/合并转发+批量撤回，`core/network/api/ChannelsApi.kt` 无 createDiscussion 端点 | **追进项 #3**（M3 域，新交互） |
| 009ae95 | forward: 转发选人页新增创建频道并转发 | 记档不做（已覆盖） | AA `feature/chat/forward/ForwardSelectScreen.kt:185` 已实现（"创建频道并转发（RN :645-653）"注记） | 无 |
| dfa3f50 | roomList: 会议入口文案改为 AntMeeting | 记档不做（已覆盖） | AA `res/values/strings.xml:258` drawer_meeting="AntMeeting"（zh 同） | 无 |
| 22dd611 | meeting-mobile: 列表搜索框改 Pressable | RN 域外 | 会议 WebView 壳列表态的 RN 控件手势 bug（退会误跳全局搜索）；AA 原生会议列表无此控件 | 无 |
| f7668cb | meeting: 原生→web 桥接 data 用字符串 | RN 域外 | WebView 桥协议；AA 无会议桥 | 无 |
| 579ad96 | meeting: 左滑/返回键触发 web 离开确认面板 | 记档不做（已覆盖） | AA 原生离开确认弹层（M8 坑 4：meeting_room_leave_confirm，consumesBack 单源） | 无 |
| e64c42d | Merge feat/mobile-share-real-landscape | 记档不做 | merge 节点 | 无 |
| 66bedb5 | meeting-mobile: 左滑/返回键绑顶栏返回同逻辑 | 记档不做（已覆盖） | AA 原生返回=离开确认同源（M8） | 无 |
| 2a565fe | docs: 年会签到方案命名调整 | RN 域外 | specs 文档 | 无 |
| cbbb778 | docs: 年会签到方案B码解析修正 | RN 域外 | specs 文档 | 无 |
| fd30946 | docs: 年会签到人力评估 | RN 域外 | specs 文档 | 无 |
| 591dbfa | meeting-mobile: 共享态顶部栏悬浮压视频上方 | 记档不做（已覆盖） | AA M8-T9：共享态顶栏悬浮显隐（headerVisible，tap toggle=web hover 等价，`feature/meetingroom/ui/MeetingRoomScreen.kt:124`） | 无 |
| e28744f | meeting-mobile: 共享态顶栏跟随工具栏+横屏方向改左 | RN 域外 | 会议 WebView 壳层（MeetingMinimizeShell/MeetingWebView）；AA 原生横屏与顶栏为 M8-T9 GREENFIELD 自有实现 | 无 |
| 458994b | meeting: 横屏真全屏隐藏系统通知栏/导航栏 | **追进发布** | RN MainActivity 横屏 immersive；AA `feature/meetingroom/ui/MeetingRoomScreen.kt` 横屏无系统栏隐藏（全仓无 WindowInsetsController/WindowCompat 调用）——横屏会中系统栏仍悬浮。visual 级 | **追进项 #4**（M8 域，视觉次要；GREENFIELD 域内原生等价需求，controller 裁定） |
| f4d2768 | meeting: 共享态转屏按钮失灵 | RN 域外 | WebView 壳 state 时序 bug；AA 原生 toggle 直接调 API（M8-T9 裁定正针对此坑） | 无 |
| c9f7ed1 | search: 全局搜索进私聊 bump tSearch | 记档不做（已覆盖） | AA `MainActivity.kt:1206` fromGlobalSearch → chatBumper.enterRoom（M5-T8） | 无 |
| 6b5f95e | meeting: 接收 web 横屏桥接消息锁方向 | RN 域外 | WebView 桥；AA 原生横屏三触发源（M8-T9） | 无 |
| e9fb916 | ios: 开启 iPad 支持 | RN 域外 | iOS 专属 | 无 |
| c0e07aa | version | 记档不做 | churn | 无 |
| b269a68 | app-update: 暂不升级会话内静默，冷启动重新提醒 | 记档不做（已覆盖） | AA `feature/settings/AppUpdatePromptController.kt` 坑 11 注记即本修订（"冷启动重弹是 2026-09-15 有意修订，勿改"），登出复位经 AuthRepository | 无 |
| 2c32e43 | markdown: 有序列表标记按层级区分 | 记档不做（已覆盖） | AA `feature/chat/ui/MarkdownNodes.kt:267` orderedMarker 同款逐位（1..100 / a..z / I..XX / i..xx 循环，web gazzondown 对齐注记） | 无 |
| 1dcbfca | forward: 转发选人搜索接入 spotlightv2 聚合 | 记档不做（已覆盖） | AA `core/network/api/SpotlightApi.kt:79` fetchForwardSelectSearch 同参逐位 | 无 |
| 654fb8d | forward: useForwardSearch 防抖 hook | 记档不做（已覆盖） | AA ForwardSearcher（`ForwardSelectScreen.kt:66`，300ms debounce 同语义） | 无 |
| 2314c69 | forward: fetchForwardSelectSearch 接口 | 记档不做（已覆盖） | AA SpotlightApi.kt（转发弹窗聚合搜索，RN :67-151 同参） | 无 |
| d63139a | docs: 转发选人服务端搜索计划 | RN 域外 | 计划文档 | 无 |
| 684e941 | memberProfile: POTA 区块渲染崩溃 | 记档不做（已覆盖） | AA `core/network/api/MemberProfileApi.kt:117` parseOtkrQuery 三层 sanitize（逐层滤 null/data 缺失）+ canQuery 门（success 且有 data 才放行） | 无 |
| d7d07cc | docs: 会议入口实现计划 | RN 域外 | 计划文档 | 无 |
| ae9e255 | test: 抽屉会议入口顺序断言 | 记档不做 | 测试补充 | 无 |
| ddef1b5 | drawer: 头像抽屉新增会议入口 | 记档不做（已覆盖） | AA M5-T9 裁定无抽屉，两 RN 入口合并单一菜单项（`feature/chatlist/ui/ChatListScreen.kt:99` onOpenMeeting → MeetingHome） | 无 |
| 48c2961 | room: 频道列表"+"菜单新增会议入口 | 记档不做（已覆盖） | 同上 M5-T9 菜单化迁移裁定 | 无 |
| 128a8a9 | meeting: 会议图标/首页地址常量/入口文案 | 记档不做（已覆盖） | AA 均备：drawer_meeting/MEETING_HOME_URL/MeetingHome 路由 | 无 |
| d0b7128 | verison | 记档不做 | churn | 无 |

## 尾部汇总

### 计数

| 类目 | 数量 | 明细 |
|---|---|---|
| 记档不做 | 38 | 已覆盖佐证 19 / 纯记档（churn 5 + merge 7 + 埋点 3 + manifest 清理 1 + 测试 1）/ 联动注记 2 |
| RN 域外 | 27 | docs/specs 12（其中年会签到 9——跨系统未来功能，RN app 亦未实现，AA 无动作）/ iOS 5 / 会议 WebView 壳与桥接架构（AA 已原生替换）8 / RN 库补丁 1 / RN 控件 bug 1 |
| **追进发布** | **4** | 见下清单 |
| 无法离线判定 | 0 | 无需服务端开关/真机/双账号才能裁定的 delta |

### 追进发布清单（T5 槽位输入，controller 裁定）

1. **32c8984 公告列表最新置顶 + originSender**（M4 域）
   - AA 文件：`app/src/main/kotlin/cn/appia/im/core/chat/RoomAnnouncements.kt`（parseMainAnnouncements 加 updateTime 降序——缺失时间沉底稳定排序；RoomAnnouncement 增 originSender 字段+解析；预览取最新一条）、`app/src/main/kotlin/cn/appia/im/feature/roominfo/ui/RoomAnnouncementScreen.kt`（列表序消费+「XX 发布的消息，由 YY 设为公告」标题展示）
   - 影响：公告列表顺序与 RN/web 不一致（AA 保持插入序）；消息转公告缺原始发送人展示
2. **aa93319 合并转发消息级 appiaBaseUrl 透传**（M3 域）
   - AA 文件：`app/src/main/kotlin/cn/appia/im/feature/chat/forward/ForwardMergeMessage.kt`（ForwardMessageItem 增 appiaBaseUrl + mapForwardMessagesToEntities 映射保留）、`app/src/main/kotlin/cn/appia/im/feature/chat/ui/MessageAttachments.kt`（消息级域名合并进各附件，对齐 RN MessageBody/collectMediaPhotosFromMessages）；`core/media/AttachmentUrlFormatter.kt` 参数已备无需改
   - 影响：跨主体合并转发附件预览裂图/404（服务端已下发该字段，AA 丢弃）
3. **d82f1c1 多选「新频道」创建讨论**（M3 域）
   - AA 文件：`app/src/main/kotlin/cn/appia/im/feature/chat/ui/MessageActionsSheet.kt`（MultiSelectActionBar 增动作，showAppiaTag bit0=0 门控）、`app/src/main/kotlin/cn/appia/im/core/network/api/ChannelsApi.kt`（rooms.createDiscussion：prid/pmid/t_name=首条消息去链接前缀，空则未命名主题）、RoomScreen 接线（成功退多选清栈跳转/失败 toast）
   - 影响：AA 多选操作条缺「新频道」入口（RN 对齐旧版 Start_a_Discussion）
4. **458994b 会议横屏真全屏隐藏系统栏**（M8 域，visual 级）
   - AA 文件：`app/src/main/kotlin/cn/appia/im/feature/meetingroom/ui/MeetingRoomScreen.kt`（横屏 WindowInsetsController hide(statusBars+navigationBars)，竖屏/退出/最小化恢复）
   - 影响：横屏会中系统状态栏/导航栏仍显示（RN 已真全屏）；GREENFIELD 域内的原生等价 UX，严重度 visual——controller 可降级记档

### 无法离线判定清单

无。

### 联动注记 → T2 基线清单复核项（记档不做但牵出 AA 基线缺口）

| 注记源 | AA 缺口 | 复核建议 |
|---|---|---|
| 62494cb | 聊天内公告卡（AnnouncementMessage）整个组件缺失：room_*_announcement 消息 AA 走普通气泡行，RN 走专卡（标题+分隔线+md 正文+图片行+文件）。`MessageRow.kt:73`"走普通行=RN"注记与 RN 实际不符，疑似实现误读 | T2 M3 消息域清单对照 RoomMessageRowBody.tsx:41-47；若补卡含 62494cb 样式（minWidth 100/标题 18sp） |
| 88d1a61 | 多选「设为公告」动作缺失（MessageActionsSheet.kt:64"归 M7 不移植"未收编；RN 冻结基线含此动作） | T2 清单裁定补/记档；若补须含 88d1a61 域名补全（相对链接拼当前 server+file-upload→file-proxy 改写+# 剥离守卫扩展）+ 32c8984 originSender |
| e19a64e | 扫码功能缺失（RN 冻结基线有 ScanScreen/扫一扫入口；AA 仅 MyCard 二维码生成，无扫描器） | T2 清单裁定补/记档（delta 本身 iOS 专属已域外） |
