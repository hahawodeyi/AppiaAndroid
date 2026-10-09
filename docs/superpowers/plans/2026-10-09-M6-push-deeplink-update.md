# M6 推送+自更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的阿里云推送全链（SDK 初始化/token 注册/点击进房+消息高亮/冷启动队列）、应用内自更新（版本检查/强制与可选弹窗/应用内 APK 下载安装），并落地总纲 §4.6 六项 M5 遗留前置。

**Architecture:** 推送在 RN 是完整生产实现（非 stub，调研报告 §1）——原生 Application.onCreate 初始化 + 登录后 token 上传 + 四层 payload 兜底解析 + host 校验 + 90s TTL 导航队列三 drain 点。深链不走 URL scheme（RN 的 `appia://` filter 是死配置，勿照抄）；推送点击 → 队列 → `RoomRoute(jumpToMessageId)` 复用 M5 跳转管线（零新增跳转逻辑）。自更新走非 RC 独立端点（无 token），应用内 APK 下载+FileProvider+系统安装器（RN 早期 plan 的"不做应用内安装"已被代码推翻，以代码为准）。

**Tech Stack:** 既有栈；推送 = `com.aliyun.ams:alicloud-android-push:[3.9.4.1,4.0)` + `alicloud-android-third-push`（阿里云 maven 仓，RN 现网验证 targetSdk 36 兼容）；自更新下载 = OkHttp（既有）+ FileProvider。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（§4.6 M5 遗留六项在 T1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN）；调研报告 `2026-10-09-M6-research.md`（坑清单 16 条在内）

## Global Constraints

- **坑 1（危害之最）**：SDK init 返回码 `'10000'` 或 `'PUSH_20110'`（原生先行已注册）**都算成功**——按失败提前 return 则点击监听器永不注册、推送点击无法进房
- **坑 3**：`channel_01` 必须预建 IMPORTANCE_HIGH（不预建系统自动低重要性建 → 进程存活时无横幅声震；已存在通道重要性不可覆盖）
- payload 解析必须移植 `extractPushEjsonPayload` 全部四层兜底（Android `extra` 双重转义/厂商通道丢 type/裸 rid/oncall 最小体），漏层则特定通道点击进不了房
- host 不匹配静默跳过（多组织防护）；导航队列 90s TTL + `rid:messageId` 去重 + 三 drain 点缺一不可
- 「暂不升级」是**会话内内存静默**（冷启动重弹是 RN 2026-09-15 有意修订）——勿"修复"成持久化；手动检查 `bypassDismiss` 覆盖静默；强制更新无返回键出口、下载中隐藏按钮
- versionCode 正式包必须 **> 28187853**（生产版已装，否则安装器拒装自更新失效）
- 语音 oncall 通知升级/分流归 M10——M6 parser 只留 oncall 识别桩（host 校验后进房照常）
- 纯函数优先 TDD（payload 解析/版本比较/队列语义全 TDD）；文案 i18n（`appUpdate_*` 12 键 + `pushBattery_*` 7 键照搬）；提交中文
- **外部依赖如实入册**：EMAS 控制台核对、厂商通道（华为/荣耀/vivo 等）真机推送、GMS 设备离线送达——均为用户域验收依赖，代码任务只负责 SDK/meta-data/弹窗 Activity 就绪
- 用户验收分工同前

---

### Task 1: M6 前置收尾（总纲 §4.6 六项）

**Files:** MainActivity（导航 reset 对齐 + 陈旧注释）、feature/search 两 VM（生命周期）、core/settings/ServerSettingRegistry（asBoolean KDoc）、装配 helper、backlog 实体
- [ ] ① **导航 reset 对齐**：`navigateToRoomFromAppRoot` 等价——Room 导航统一 helper（对照 RN reset [MineDrawer, Room] 语义：Chat 栈已挂载时复用栈底 key 防列表重建；未挂载 nested navigate；兜底 root reset），T5 搜索房间点击/T6 跨房跳转/T8 bump 链与深链共用；返回行为对齐 RN（从房返回回会话列表非搜索屏）
- [ ] ② **搜索 VM 生命周期**：GlobalSearch/RoomSearch 两 VM 升级 entry 级 androidx ViewModel（RoomMessagesViewModel 同款）或补 dispose 挂点——出屏取消 debounce/在飞请求（对照 RN unmount-cancel）
- [ ] ③ **注释清理**：T1 asBoolean 故意分歧 KDoc 一行；MainActivity:253/1063 两条失实 TODO 占位注释删除
- [ ] ④ **M6 杂项 backlog 建实体**：`docs/backlog-m6.md` 收编终审 M-5 无主项（T5 success:false 空态/rid="" 空房/_id 空 key O(n²)；T8 bump 整行写；T9 ServerInfoApi 吞 CE；T2 RoleRefresher 改名；T6 scroll-to-latest/loadEarlier spinner）——每项一行 + 归属建议
- [ ] ⑤ **rememberServerBoundDb() 装配 helper**：MainActivity serverUrl/db/auth remember 三连收敛（§4.6-6 止损）
- [ ] ⑥ roomlist_swipe 登录后 Maestro：按登录会话可得性执行或边界如实（报告注明）
- [ ] TDD（helper/VM 生命周期）→ Commit: `chore(m6): 前置收尾（总纲 §4.6 六项）`

---

### Task 2: 推送 SDK 基建（依赖/初始化/通道/manifest）

**Files:**
- Create: `core/push/AliyunPushBootstrap.kt`（Application.onCreate 直调幂等：manifest meta-data 读 appKey/appSecret → PushServiceFactory.init → register → turnOnPushChannel；**init 结果码 10000/PUSH_20110 均成功**——坑 1）、`core/push/PushNotificationChannels.kt`（`channel_01` IMPORTANCE_HIGH 预建 + `im_default`；voice_incoming 留 M10 桩）
- Modify: `AppiaApplication`（onCreate 调 bootstrap）、`app/build.gradle.kts`（阿里云 maven 仓 + 两 SDK 依赖）、`AndroidManifest.xml`（权限 POST_NOTIFICATIONS/WAKE_LOCK/RECEIVE_BOOT_COMPLETED/VIBRATE/ACCESS_WIFI_STATE/READ_PHONE_STATE/REQUEST_INSTALL_PACKAGES + 角标三权限；receiver 三 action；PopupPushActivity（extends AndroidPopupActivity，类名按 EMAS 控制台——**外部核对项注释**）；meta-data 全套 §5.5；FCM default channel）
- Test: `AliyunPushBootstrapTest`（幂等/结果码语义——纯逻辑部分）
- [ ] FCM/google-services：**裁定项——默认仅 release 应用插件**（debug `.debug` 后缀与 json package 冲突），meta-data 两 build 均带；GMS 离线送达验证归用户域，报告注明
- [ ] Commit: `feat(push): 阿里云 SDK 基建（初始化/通道/manifest）`

---

### Task 3: token 注册 + 通知权限 + 电池引导

**Files:**
- Modify: `core/push/PushTokenRegistrar.kt`（deviceIdProvider 换 `CloudPushService.getDeviceId`——端点/body/存储键已与 RN 对齐只换源）、登录/登出编排核对（AuthRepository :76/:114 既有）
- Create: `core/push/NotificationPermissionGate.kt`（POST_NOTIFICATIONS 运行时申请——**故意分歧：RN 从不申请，AA 补**，报告注明）、`core/push/BatteryOptimizationGuide.kt`（isIgnoringBatteryOptimizations 检测 + 引导弹窗触发：冷启动 + 登录成功双触发，对照 RN bootstrapDeferredServices/authStore）
- Modify: MainActivity/登录装配（权限申请与引导挂点）
- Test: TokenRegistrar wire 回归（换源后端点/body 断言不变）+ BatteryGuide 判定 ≥6 用例
- [ ] i18n：`pushBattery_*` 7 键迁移
- [ ] Commit: `feat(push): token 注册接线与通知权限/电池引导`

---

### Task 4: payload 解析 + 点击路由（TDD 重头）

**Files:**
- Create: `core/push/PushPayloadParser.kt`（**四层兜底逐层移植**：raw.ejson → extra/extras 袋内 ejson（双重转义）→ extra 整袋即业务体 → 顶层 rid+type → 仅 rid 兜底 'c' → roomId+msgType=oncall 最小体；ROOM_TYPE_MAP c/d/p/l；标题规则 d=sender.username/l=sender.name??name/其余 name）、`core/push/PushClickRouter.kt`（解析 → host 校验 `isPushHostMatchingCurrentServer`（hostname 比、忽略协议/尾斜杠/子域后缀）→ oncall 桩（识别即原样放行，语音分流 M10）→ 挂起导航意图）
- Create: `core/push/PendingPushNavigation.kt`（内存队列：**90s TTL**、`rid:messageId` 去重、drain 门控 `isAuthenticated && navReady`）
- Test: `PushPayloadParserTest`（**全兜底层 + 双重转义 + 各退化形态 ≥14 用例**——坑 5）、`PendingPushNavigationTest`（TTL 过期/去重/乱序 drain ≥8 用例）、host 校验 ≥6 用例
- [ ] Commit: `feat(push): payload 解析与点击路由（四层兜底/host 校验/90s 队列）`

---

### Task 5: 深链进房 + 高亮收口（三 drain 点）

**Files:**
- Create: `core/push/PushNavigationDrainer.kt`（三 drain 点等价：导航就绪 onReady / Main 挂载 / 回前台 **+400ms 延迟**二次 drain；drain 时 `removeAllNotifications` 等价 NotificationManagerCompat.cancelAll——坑 16）
- Modify: MainActivity（SDK 点击回调 → Router → 队列；drain 消费 → **T1 的 navigateToRoomFromAppRoot helper** → `RoomRoute(rid, t, title, jumpToMessageId=messageId)`——M5 跳转管线零改动复用；ejson 无 messageId 容忍只进房不高亮——坑 8）、回前台挂 drain
- Test: Drainer 时序（三触发/400ms/清理通知）≥6 用例
- [ ] 冷启动链路：进程内回调直接入队（AA 原生单进程无 JS 桥缓存层——天然简化，报告注明与 RN 结构差）
- [ ] Commit: `feat(push): 深链进房收口（三 drain 点/通知清理）`

---

### Task 6: 自更新检查（非 RC 端点）

**Files:**
- Create: `core/update/AppReleaseApi.kt`（裸 OkHttp GET `https://appia.cn/provider/api/v1/version?platform=android&versionName=X`——**无 IM token**；响应建模 `{success,data:{version,url,isForceUpdate,fileHash,fileSize,notes,updatedAt}}`；url 逗号分隔取下标 `APP_RELEASE_URL_COMMA_INDEX`（默认 0））、`core/update/CompareAppVersions.kt`（semver coerce 提取 x.y.z 后 gt；任一非 semver 回退小写字典序；空串恒 false——逐行为移植）
- Create: `core/update/AppReleaseCheckController.kt`（检查时机：**仅已登录** enabled；回前台重查；3h stale 窗口等价——对照 RN TanStack Query staleTime 3h 语义）
- Test: CompareAppVersions（semver/字典序回退/空串/后缀 ≥10 用例）、Api wire ≥6、Controller 时机 ≥4
- [ ] i18n：`appUpdate_*` 12 键迁移
- [ ] Commit: `feat(update): 自更新检查（非 RC 端点/semver 比较/时机控制）`

---

### Task 7: 自更新弹窗 + 应用内 APK 安装

**Files:**
- Create: `core/update/AppReleaseInstaller.kt`（下载到 `ExternalDirectoryPath/Download`（app 专属免权限）→ 临时名 `appia-release-downloading.apk` → 完成改名 `appia-release-{version}.apk` → **缓存命中同名直接装跳下载** → FileProvider content URI（authorities `cn.appia.im.provider` + file_paths xml）→ ACTION_VIEW 系统安装器；挂载时清理旧版本 APK/临时文件；进度回调 0~1；非 2xx 抛错）
- Create: `feature/settings/ui/AppUpdateModal.kt`（强制更新：无「暂不升级」+ **返回键不可关**；可选：暂不 = 会话内内存静默（**非持久化**——坑 11）；下载中隐藏按钮只留进度条；notes 图文展示；手动检查 bypassDismiss）
- Modify: MainActivity Main 装配（单例挂载：登录态 enabled + 回前台重查 + 清理触发）、`AndroidManifest`（FileProvider + REQUEST_INSTALL_PACKAGES 确认）
- Test: Installer（缓存命中/临时名序/清理/进度边界 ≥8 用例）、Modal 状态机（强制/可选/静默/bypass ≥8 用例）+ Compose 冒烟
- [ ] Commit: `feat(update): 自更新弹窗与应用内 APK 安装（FileProvider/强制拦截）`

---

### Task 8: versionCode 策略 + 发布接线

- [ ] versionCode 接线：release.properties VERSION_CODE 或等价机制，**正式包 > 28187853**（坑 10）；ABI 拆分公式评估（RN abi*2^20+base——报告定案，或单一递增常量）
- [ ] release 构建烟测：assembleRelease（release.properties 既有机制）产物可安装覆盖
- [ ] Commit: `chore(update): versionCode 下限接线与发布烟测`

---

### Task 9: M6 收尾

- [ ] 门禁×3（flake 率）+ Maestro（launch/login 链 + 新增 update 弹窗冒烟如可离线模拟——边界如实）+ 模拟器走查
- [ ] **推送端到端验证边界如实**：EMAS 控制台/厂商通道真机/GMS 设备 = 用户域验收依赖（协议十四节列明操作步骤与前置条件）
- [ ] 自测协议 M6 增补（第十四/十五节）：推送点击进房+高亮（冷/热启动）/token 注册（服务端可查）/通知权限申请/电池引导/自更新弹窗（强制/可选/静默重弹）/应用内安装/versionCode——已知差异（POST_NOTIFICATIONS 运行时申请为故意分歧/FCM debug 限制/语音 oncall 桩 M10/appia:// scheme 死配置不移植等）
- [ ] versionName 0.7.0；README
- [ ] Commit + push: `chore(m6): 收尾与自测协议增补`

---

## M6 完成定义

1. 9 任务全部 SDD 闭环
2. 全量单测+lint 绿；门禁×3；模拟器走查
3. `.debug` APK + 协议 M6 增补交付
4. 推送端到端（EMAS 控制台+厂商真机）与自更新覆盖安装由用户验收后 M6 关闭
