# M1 认证与多组织 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的完整认证链路：企业码验证 → 登录（SMS/密码/CAS SSO）→ token 持久化与启动恢复 → session 引导（DDP resume + REST 会话同步）→ 组织切换 → 登出；真实凭证的功能验收由用户执行。

**Architecture:** 在 M0 的 core/network（DDP/REST）、core/database（Room 八表）、core/datastore（MMKV）之上构建 `RocketSdk`（REST+DDP 会话单例）、`RealtimeSessionManager`（generation 取消机制）、`OrgSwitchCoordinator`（互斥+回滚）与 feature/login、feature/org 的 Compose UI。数据流与 RN 一致：登录 → REST sync → Room 落库 → Flow → UI（M2 消费）。

**Tech Stack:** 既有栈 + WebView（滑块验证码/CAS SSO）、Coil（企业 logo）。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（总纲；§4.1 的 M1 前置任务在本计划 Task 1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（下称 RN，所有 file:line 引用基于此）

## Global Constraints

- 服务端 JSON：`Json { ignoreUnknownKeys = true }`；响应统一取 `data ?? resp`（RN sdk/index.ts:163-174）
- 凡用户可见文案走 i18n（key 沿用 RN 原名）；禁止代码硬编码中文
- 每个与 RN 行为对应的类，KDoc 标注 RN 源 file:line
- 提交信息中文 `类型(范围): 描述`；每任务测试绿 + 提交
- **凭证相关真实验证由用户执行**（本计划「§ 用户自测协议」）；agent 侧验证 = 单测（MockWebServer/MockWebSocket）+ 模拟器 UI 走查到请求边界
- RN 的 `VerificationScreen` 是占位页（仅一个 logout 按钮，RN VerificationScreen/index.tsx:12-36）——按占位复刻，不臆造功能
- RN `ENABLE_PASSWORD_LOGIN = false`（RN LoginScreen:29）：UI 默认只显示短信登录；密码模式代码实现但用同一常量门控（BuildConfig.DEBUG 可开），保持行为一致

---

### Task 1: session 层加固前置 + 构建配置落位（总纲 §4.1）

**Files:**
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`、`maestro/launch_android.yaml`
- Modify: `core/network/rest/RetrofitFactory.kt`、`core/network/rest/AuthInterceptor.kt`、`core/network/ddp/DdpClient.kt`、`core/database/DatabaseManager.kt`
- Create: `core/network/ServerUrl.kt`、`core/network/RocketHttp.kt`
- Test: 对应 `app/src/test/...` 扩展

**Interfaces:**
- Produces:
  - `ServerUrl`：`fun normalizeServer(url: String): String`（trim + 去**全部**尾斜杠——RN 有 4 处口径不一，统一取严）；`fun normalizeServerToDbKey(url: String): String`（复刻 RN db.ts:41-47：`(^\w+:|^)\/\/` 去协议、`/`→`.`，例 `https://appia.cn` → `appia.cn`，文件名 `appia_<key>`）；`fun wsUrl(url: String): String`（复用 DDP hostToWs）
  - `RocketHttp`：进程级共享 `OkHttpClient` 单例（连接池/调度器共享），`DdpClient` 与 `RetrofitFactory.create` 均注入它
  - `RetrofitFactory.create(host, authProvider, timeoutMs: Long? = null)`：per-call 超时支持（组织切换 30s；对照 RN restClient.ts:44-50）
  - `AuthSession` 增加 `data ?? resp` 平铺：`LoginResponse` 兼容 `{data:{authToken,...}}` 与裸形态（RN auth.ts:126）
  - DatabaseManager 缓存改 `ConcurrentHashMap` + `active` `@Volatile`
  - debug 构建 `applicationIdSuffix = ".debug"`（release 无后缀）；Maestro YAML appId 参数化为 `cn.appia.im.debug`
  - `AppiaApplication.onCreate` 调 `MMKV.initialize(this)`（提前于任何 Hilt 注入消费）

- [ ] **Step 1:** 每项先补失败测试（normalizeServer/DbKey 用例含 `https://appia.cn/`、`appia.cn///`、带路径 URL；平铺两形态；timeout 传播）
- [ ] **Step 2:** 实现 → 全量测试绿（84 + 新增）
- [ ] **Step 3:** 模拟器验证 `.debug` 包可装可启（与生产版共存：`adb shell pm list packages | grep appia` 应见两条）
- [ ] **Step 4:** `git commit -m "refactor(network): session 层加固前置（总纲 §4.1）+ debug 包后缀"` + push

---

### Task 2: RocketSdk 与登录凭证全集

**Files:**
- Create: `core/network/RocketSdk.kt`、`core/network/Credentials.kt`、`core/network/LoginRequestFactory.kt`
- Test: `app/src/test/kotlin/cn/appia/im/core/network/RocketSdkTest.kt`、`LoginRequestFactoryTest.kt`

**Interfaces:**
- Produces:
  - `sealed class LoginCredentials`（5 变体，逐一对照 RN credentialsTypes.ts + loginCredentialsRest.ts:20-75）：
    | 变体 | 端点 | 请求体 |
    |---|---|---|
    | `Password(username, password, ic?, ldap: Boolean=false)` | ldap? `verify-ic` : `login` | ldap→`{username, ldapPass, ldap:true, ldapOptions:{}, ic?}`；else→`{username, password, ic?}` |
    | `Sms(phone, code, areaCode)` | `login` | `{smsCode:true, phone, code, areaCode}`——**不带 ic**（RN LoginScreen:398-407 坑：sendCode 已消费 ic） |
    | `SwitchOrg(userId, userToken, url)` | `login` | `{userId, userToken, url}` |
    | `Cas(credentialToken)` | `login` | `{cas:{credentialToken}}` |
  - `class RocketSdk(ddp: DdpClient, client: OkHttpClient)`：`initialize(server)`（重建 DdpClient，**不切库**——RN session.ts:267-271）、`connect()`、`login(credentials): LoginResult`（REST 成功 → `resume(token)` DDP）、`resume(token): DdpLoginResult`、`hydrateRestSession(session)`、`clearRestSession()`、`disconnect()`、`get/post(methodCall 回退：POST method.call/{encodeURIComponent(method)}，body {message: <JSON 字符串>}——RN sdk/index.ts:270-291)`、`hasDdpUserId()`
  - `LoginResult`：`{authToken, userId, me?}`（`data ?? resp` 平铺），`userFromLoginMe` 组装（RN auth/userFromLoginMe.ts:26-42）
- [ ] TDD：MockWebServer 断言 5 变体 wire body 逐字段 + REST 成功后发出 DDP `login{resume}`（MockWebSocket）+ 失败传播
- [ ] Commit: `feat(sdk): RocketSdk 与五类登录凭证（对照 loginCredentialsRest 逐字段）`

---

### Task 3: 企业码验证 + 服务器解析 UI

**Files:**
- Create: `feature/login/EnterpriseVerifyApi.kt`、`feature/login/ui/EnterpriseCodeScreen.kt`
- Test: `EnterpriseVerifyApiTest.kt`、`ServerUrlTest`（已在 T1）扩展

**Interfaces:**
- Produces:
  - `POST {envHost}/provider/api/v1/verify` body `{identity}` → `{success, msg?, servers?: [{url,name?,ename?,logo?,selected?}]}`（RN verifyEnterprise.ts:12-24）；`DEFAULT_VERIFY_ENV_HOST = "https://appia.cn"` 常量
  - `EnterpriseCodeScreen`（Compose）：输入框+下一步；失败 Alert（i18n `enterprise_verifyFailedTitle`/`enterprise_verifyFailedUnknown`/`enterprise_missingCode`）；成功 `navController.navigate(Login(servers))`（replace）
  - **开发后门复刻**：长按「企业识别码」label 切出 envHost 输入框（RN EnterpriseCodeScreen:96-106 的 `__DEV__` 等价物：仅 `BuildConfig.DEBUG` 显示）
- [ ] TDD：verify API（success/servers/失败 msg）→ UI 走查（Compose 测试或模拟器手测：空输入 Alert、假 host 报错路径）
- [ ] Commit: `feat(login): 企业码验证与服务器列表（对照 verifyEnterprise）`

---

### Task 4: AuthApi（SMS 发码/区号/登录编排）

**Files:**
- Create: `feature/login/AuthApi.kt`、`feature/login/ui/AreaCodeScreen.kt`
- Test: `AuthApiTest.kt`

**Interfaces:**
- Produces:
  - `loginSendCode(host, phone, areaCode, ic)` → `POST /api/v1/login.sendCode` body `{phone, areaCode, ic: ic ?? {}}`；`raw.success !== false` 即成功（RN auth.ts:67-81）
  - `loginGetAreaCodes(host, locale)` → `GET /api/v1/getAreaCode?locale=`，模块级缓存 key=`host|locale`；失败/空回落 `[{label: t('login_area_china'), areaCode:'+86', code:'CN'}]`（RN auth.ts:37-62 + AreaCodeScreen:25-47）
  - `login(host, credentials)` 编排：`prepareSocketConnection`（停监听→disconnect→initialize→wire→connect，**不切库**）→ `sdk.login(credentials)`（RN auth.ts:87-90）
- [ ] TDD：三端点 wire 断言 + sendCode ic 透传/login 不带 ic 的差异用例 + 区号缓存与回落
- [ ] Commit: `feat(login): 短信发码/区号/登录编排（对照 auth.ts 含 ic 时序差异）`

---

### Task 5: LoginScreen UI（短信主模式 + 滑块验证码 + 密码门控）

**Files:**
- Create: `feature/login/ui/LoginScreen.kt`、`feature/login/ui/CaptchaWebView.kt`、`feature/login/SmsCaptchaBottomSheet.kt`
- Modify: 导航图（Auth 栈：Enterprise→Login→(AreaCode/AuthWeb)）
- Test: Compose UI 测试（输入校验/按钮态）

**Interfaces:**
- Produces（对照 RN LoginScreen 全文）:
  - 服务器选择：`servers` 下拉（`selected ?: first`，兜底 `https://appia.cn`，RN:35-39）；label 连点 10 次/500ms 切手输 URL（RN:168-181）
  - 短信模式（默认）：手机号（`replace(/\D/g,'')`）+区号跳转 AreaCodeScreen+验证码+发码（60s 冷却 RN:327；`smsCodeSendConsumedRef` 防重 RN:303-339）
  - 滑块验证码：SMS 走底部弹层 WebView `{server}/verification/sms-login?locale=&t={nonce}`（RN:121-130）；密码走内嵌 WebView `{server}/verification/sms?locale=&t={reloadKey}`（RN:112-119）；ic 经 `postMessage` JSON 传入、非 JSON 心跳忽略（RN:293-301）；Android 侧 `CookieManager.setAcceptThirdPartyCookies` + `domStorage` + `mixedContentMode` 等价配置
  - 密码模式（`ENABLE_PASSWORD_LOGIN=false` 门控，debug build 可开）：username/password + 失败**清 ic 重载滑块**（RN:260-267）
  - 校验失败 Alert i18n：`login_alert_missing_phone`/`login_captcha_required`/`login_sms_send_failed`/`login_resend_sms_seconds`/`login_alertFailedTitle`/`login_alertFailedUnknown` 等（RN zh.json:382-421）
  - 登录成功 → `AuthRepository.login(result, server)`（T7）→ 导航进 Main
- [ ] Compose 测试（模式切换/校验/冷却态）+ 模拟器走查（无真实服务端时 UI 到请求边界）
- [ ] Commit: `feat(login): 登录页（短信/密码双模式+滑块验证码 WebView）`

---

### Task 6: CAS SSO

**Files:**
- Create: `feature/login/ui/AuthWebScreen.kt`、`feature/login/CasApi.kt`
- Test: `CasApiTest.kt`、回调判定逻辑单测（URL 判定函数抽纯函数）

**Interfaces:**
- Produces:
  - `GET {server}/api/v1/settings.oauth` 取 `services[]` 中 `service==='cas' && enabled` 的 `login_url`（RN LoginScreen:91-108）→ CAS 按钮显隐
  - 17 位随机 base36 `ssoToken`；`url = {casLoginUrl}?service={server}/_cas/{ssoToken}`（RN:214-233）
  - WebView 回调判定（**抽成纯函数** `evaluateCasRedirect(url, serverHost, ssoToken)` 便于 TDD）：先 `decodeURIComponent`；带 `ticket` → 放行加载；`service` 参数 host == server host → 成功回调 `login(Cas(ssoToken))` + 关页（RN AuthWebScreen:37-69）
  - WebView 配置：JS/domStorage/mixedContent/全 origin 白名单/共享 cookie（RN:84-99）
  - `AuthWebScreen` 同时承载「忘记密码」Web（RN 同屏复用）
- [ ] 纯函数 TDD（ticket 放行/同 host 拦截/异 host 放行）+ API 测试
- [ ] Commit: `feat(login): CAS SSO WebView（回调判定对照 AuthWebScreen）`

---

### Task 7: 会话持久化与启动恢复

**Files:**
- Create: `core/datastore/AuthSessionStore.kt`、`core/datastore/OrgSessionCache.kt`、`core/push/PushTokenRegistrar.kt`、`domain/session/AuthRepository.kt`
- Test: `AuthSessionStoreTest.kt`、`OrgSessionCacheTest.kt`（fake KvStore）

**Interfaces:**
- Produces:
  - `AuthSessionStore`（MMKV，等价 RN `auth-storage`）：`{token, user, serverUrl}` 三字段；`isAuthenticated = 三者齐全`（RN authStore.ts:174-189，**不 ping 服务器**）
  - `OrgSessionCache`（MMKV 实例 id `org-session-by-host`，单 key `sessions-v1`，JSON `Record<host, {token,userId,username,name?}>`；RN orgSessionByHost.ts:1-57）
  - `PushTokenRegistrar`：`register()` → `POST /api/v1/push.token {value, type:'gcm', appName:'cn.appia.im'}`；`unregister()` → `DELETE /api/v1/push.token`；登录/登出各埋一点（M6 实现真推送，M1 只埋点+本机 deviceId 占位实现）
  - `AuthRepository`：`login(result, server)`（存 session + registerPush）、`restore()`（启动判定）、`logout()`（T10 完整编排）
- [ ] TDD（fake KvStore）：持久化往返/缺字段判定/组织缓存覆盖语义
- [ ] Commit: `feat(auth): 会话持久化、组织会话缓存与推送埋点`

---

### Task 8: RealtimeSessionManager（generation 引导）

**Files:**
- Create: `domain/session/RealtimeSessionManager.kt`、`domain/session/StreamNames.kt`
- Test: `RealtimeSessionManagerTest.kt`（MockWebServer+MockWebSocket 全栈）

**Interfaces:**
- Produces（对照 RN session.ts:533-638 逐步骤）:
  - `bootstrap(server, token)`：sessionKey 短路/inflight 合并 → generation 快照 → hydrate REST → **切库**（`DatabaseManager.databaseFor(server)`，REST 落库前）→ DDP resume（`!hasDdpUserId` 才 resume）→ 每步后 generation 检查中止 → 初始 REST 会话同步（T9，失败仅 warn 不阻断）→ `requestUserPresence` → 后台 fire-and-forget（settings/emojis/permissions——M1 允许空实现接口占位 + 日志，M5 补全）
  - 订阅表（RN:389-417）：`stream-notify-user`(`{uid}/subscriptions-changed`,`{uid}/rooms-changed`,`{uid}/userData`)、`stream-notify-logged`(`permissions-changed`)、`stream-roles`(`roles`)、`stream-notify-all`(`public-settings-changed`)
  - 重连：close → 标记 needsResume + checkAndReopen；connected → 串行 tail → resume + 重订阅（RN:214-226,165-186）
  - 会话失效：resume 错误匹配 `you've been logged out by the server` / `your session has expired`（原文匹配，RN:96-105）→ 事件 → AuthRepository.logout
  - `teardown()`（RN:672-694 全清单）
  - generation 递增 API：`resetForOrgSwitch()`（RN:644-651）
- [ ] TDD：bootstrap 步序（fake sdk 记录调用序）、generation 竞态（bootstrap 中途 reset → 后续步骤中止）、失效文本三态、重连恢复
- [ ] Commit: `feat(session): RealtimeSessionManager（generation 引导/重连/失效识别）`

---

### Task 9: RoomsSyncRepository（会话同步落库）

**Files:**
- Create: `domain/session/RoomsSyncRepository.kt`、`domain/chat/ChatMerger.kt`
- Modify: `core/database/dao/`（按需批量 upsert/delete）
- Test: `ChatMergerTest.kt`（移植 RN mergeSubscriptionAndRoom.test.ts 关键用例）、`RoomsSyncRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `GET subscriptions.get + rooms.get`（`Promise.all` 等价；`updatedSince?` ISO 串；响应兼容 `update[]/subscriptions[]/rooms[]/裸数组` + `remove[]`，RN chat.ts:14-29 + roomSyncFromApi.ts:16-32）
  - `ChatMerger.merge(sub, room)`：subscription 为底、room 覆盖、lastMessage 最后；主键 `_id = sub.rid || room._id`（缺则抛）、`subscription_doc_id = sub._id`；`lm` 链与 `room_updated_at` 计算（RN mergeSubscriptionAndRoom.ts:526-575）
  - 防御集合（RN:754-839）：`PRESERVE_WHEN_MERGED_UNDEFINED_KEYS`（open/unread/alert/f/t/uids/draft/announcement/todoCount…增量缺字段不清本地）、`PRESERVE_NONEMPTY_STRING_KEYS`（name/fname 空串不覆盖）、`tSearch` 取 max
  - 写库：只查涉及 rid（`IN`）、create/update 字段级 diff/delete；全量 prune（本地有服务端无）、增量绝不 prune；Room batch 分块 500（RN roomSyncFromApi.ts:108-223）
  - 游标：MMKV `roomsUpdatedAt:${server}`（RN roomsSyncCursor.ts）；增量空包自动全量重拉一次（RN session.ts:464-471）；下拉全量（M2 UI 用）
- [ ] TDD：merge 链路 8+ 用例（含 PRESERVE 集合）、三分写库、prune 规则、游标增量
- [ ] Commit: `feat(chat): 会话同步落库（merge 防御集合对照 mergeSubscriptionAndRoom）`

---

### Task 10: 组织切换 + 登出编排

**Files:**
- Create: `domain/session/OrgSwitchCoordinator.kt`、`feature/org/OrgListRepository.kt`、`feature/org/ui/OrgSwitchSheet.kt`（最小 UI）
- Modify: `AuthRepository.logout()`
- Test: `OrgSwitchCoordinatorTest.kt`、`OrgListRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `OrgSwitchFlag`（AtomicBoolean begin/end/isInProgress——RN orgSwitchInProgress.ts）+ 互斥 promise 链（RN orgSwitchMutex.ts）；401 豁免已在 M0 接好（AuthInterceptor 读同一 flag）
  - `switchTo(server)` 完整序列（RN auth.ts:175-238 + finishOrgSwitchAfterAuth.ts:32-54）：cleanup（generation++→摘监听→清队列→退订→清 query 等价物）→ 缓存命中走 `connectTargetOrgSession`（resume 失败 socket not open 则 connect 后重试一次）→ 未命中 `{userId, userToken, url: 旧server}` 换票（成功写缓存）→ `applySession`（store.login + 切库）→ `bootstrap(target, token, connectAndResume=false)` → **异常回滚快照**（不 logout）
  - `OrgListRepository`：`GET login.getSwitchCandidate`（sdk methodCall 或 REST，RN company.ts:8-16）+ MMKV 缓存 key `login-switch-candidates-${username}`（**不含 token**，RN loginSwitchCandidatesCache.ts:7-8）；`waitSdkRestLogin` 等价（50ms 轮询 15s 上限，RN waitSdkRestLogin.ts）
  - `logout()` 完整清单（RN authStore.ts:138-169，**无 REST logout 调用**）：orgSwitch 中直接 return → OrgSessionCache.clearAll → 候选缓存清 → teardown → store 清 → unregisterPush（失败吞）→ `resetDatabase(server)`（M0 已建）→ 回落 prelogin 库
- [ ] TDD：换票 wire body、缓存命中/清除路径、回滚、互斥串行、logout 顺序
- [ ] Commit: `feat(org): 组织切换协调器与登出编排（对照 authStore/finishOrgSwitch）`

---

### Task 11: 导航骨架与启动恢复串联

**Files:**
- Modify: `MainActivity.kt`/导航图、`AppiaApplication.kt`
- Create: `domain/session/SessionBootstrapOrchestrator.kt`（启动恢复→bootstrap 串联）
- Create: 占位 `MainScreen`（显示已登录 server + 用户名 + 登出/切组织入口——M2 会替换成 RoomList）
- Test: `SessionBootstrapOrchestratorTest.kt`

**Interfaces:**
- Produces:
  - 启动：读 `AuthSessionStore`（同步）→ `isAuthenticated ? Main : Auth`（**首帧即定，无闪屏**——RN RootNavigator.tsx:66-95）；Main 进入 → `bootstrap(server, token)`（等价 RN MainNavigator:44-51）→ 会话失效事件 → 回 Auth
  - 占位 MainScreen：server/user/切组织（OrgSwitchSheet）/登出按钮 + 连接状态文本（为 M2 的横幅预留）
- [ ] TDD：恢复判定三分支（有/无/半残 session）；模拟器走查：登出→企业码→（无凭证止步于登录页）→ 杀进程重启恢复 Main
- [ ] Commit: `feat(app): 导航骨架与启动恢复串联`

---

### Task 12: M1 收尾——门禁、冒烟与用户自测协议

- [ ] 全量门禁：`./gradlew :app:testDebugUnitTest :app:lintDebug` 全绿
- [ ] Maestro：更新 `maestro/launch_android.yaml`（`.debug` 包 + 启动断言）+ 新增 `maestro/login_reach_login.yaml`（企业码输入任意字符→错误 Alert 可见——无凭证也能验证 UI 链路）
- [ ] 模拟器全流程走查：启动→企业码页→长按后门→登出→重启恢复
- [ ] **产出《用户自测协议》**（`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`）：安装 `.debug` APK 步骤、自测清单（企业码→收码→登录→列表数据落库核对【可用 `adb shell` + debug.db 查看】→切组织→杀进程恢复→登出→与 RN 版 side-by-side 对照点）、问题反馈格式
- [ ] Commit + push: `chore(m1): 门禁冒烟与用户自测协议`

---

## 用户自测协议（凭证验证分工）

Agent 侧已验证：协议 wire 形态（MockWebServer 逐字段）、session 步序/generation、merge 防御、持久化往返、UI 链路到请求边界。
**用户侧验收清单**（我交付 APK 后执行）：真实企业码登录、SMS 收发码+滑块、CAS（如组织启用）、登录后数据落库、组织切换、杀进程恢复、登出、`auth_session_expired` 场景（服务端踢出）——每项与 RN 版 side-by-side。发现问题按「复现步骤 + RN 表现 vs 原生表现」反馈。

## M1 完成定义

1. 12 任务全部 SDD 闭环（实现+评审+修复）
2. 全量单测 + lint 绿；模拟器 UI 链路走查通过
3. `.debug` APK 交付 + 用户自测协议文档
4. 用户完成真实凭证验收（或明确遗留问题清单）后，M1 关闭
