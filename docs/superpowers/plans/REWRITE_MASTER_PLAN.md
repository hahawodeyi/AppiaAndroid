# AppiaAndroid 原生重写总体方案（Master Plan）

> **状态**：已与需求方达成共识（2026-09-10 grilling 会话，24 个决策全部锁定）
> **执行方式**：每个里程碑启动前产出详细实施计划（`docs/superpowers/plans/`），批准后执行
> **事实来源**：`/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN 0.84.1，冻结基线）

---

## 1. 目标

用 Android 原生（Kotlin + Jetpack Compose）完整重写 appiaMobile，全功能对齐后以 `cn.appia.im` 原地升级替换上线。后端（Rocket.Chat 架构：REST API v1 + DDP/WebSocket + SSE）不变，RN 代码是协议行为的唯一事实来源。

## 2. 全局约束（所有任务隐含遵守）

| 约束 | 值 |
|---|---|
| 语言 | Kotlin（协程 + Flow） |
| 发布版本号 | 正式包 `versionCode` 必须 **> 28187853**（用户设备上生产 RN 版 1.21.9 的当前值，2026-09-11 观测；且 RN 侧启用过 ABI splits，取其观测最大值） |
| UI | Jetpack Compose，单 Activity；Material 3 风格，**配色/间距/圆角 tokens 与 RN 版一致**（源：`appiaMobile/src/theme/colors.ts` + `tokens.ts`） |
| applicationId | `cn.appia.im`（原地升级，沿用原签名） |
| SDK | minSdk 24 · targetSdk 36 · compileSdk 36 |
| 网络层 | Retrofit/OkHttp（REST）· OkHttp WebSocket（DDP 移植）· OkHttp EventSource（SSE） |
| 序列化 | kotlinx.serialization（Json `{ ignoreUnknownKeys = true }`——服务端字段只增不减） |
| 本地库 | Room，**每组织一个独立 db 文件**（命名规则对照 `db.ts`：规范化 server 串，未登录 `__prelogin__`） |
| KV | MMKV（与 RN 版同一库） |
| DI | Hilt |
| 图片 | Coil |
| 通话/会议 | Agora 原生 Android SDK |
| 推送 | 阿里云推送 Android SDK |
| E2E | Maestro（applicationId 相同，YAML 可复用） |
| 文案 | **全部走 i18n**，zh/en.json 脚本化搬迁保持原 key（`{模块}_{含义}` 风格）；代码中禁止硬编码中文（pre-commit 检查） |
| 提交规范 | 中文 `类型(范围): 描述`，例 `feat(chat): 新增消息长按复制功能` |
| UI 参照 | 以 RN 代码/运行效果为准；ASC Storybook 仅参考视觉；**禁止复用 ASC 组件实现代码** |
| 测试 | 纯逻辑 JUnit；协议层（REST/DDP）单测必须覆盖；Compose UI 用测试 API 抽查关键交互 |
| 里程碑验收 | 功能 checklist 与 RN 版 side-by-side 对照；每批交付可安装运行的版本 |

## 3. 架构分层（对照 RN src 结构）

```
cn.appia.im/
├── AppiaApplication.kt          # Hilt 入口
├── MainActivity.kt              # 单 Activity + NavHost
├── core/
│   ├── network/                  # services/sdk + services/realtime 的移植
│   │   ├── rest/                 # restClient → Retrofit；401 统一登出
│   │   ├── ddp/                  # ddpClient.ts → DdpClient（OkHttp WebSocket）
│   │   ├── sse/                  # react-native-sse → OkHttp EventSource
│   │   └── api/                  # services/api/*.ts 50+ 文件的接口定义
│   ├── database/                 # WatermelonDB schema v6（9 表）→ Room
│   ├── datastore/                # MMKV 封装（auth、设置、i18n 偏好）
│   ├── i18n/                     # strings 资源 + 查找兼容层
│   ├── theme/                    # colors.ts/tokens.ts → Compose MaterialTheme
│   └── common/                   # utils、结果类型、时间格式化
├── domain/                       # lib/ 的移植：聊天同步、排序、搜索、worktable
├── feature/                      # screens/ 一屏一包
│   ├── login/  ├── roomlist/  ├── room/  ├── contacts/
│   ├── search/  ├── settings/  ├── todo/  ├── labor/
│   ├── meeting/  ├── mail/  └── voice/
└── di/                           # Hilt modules
```

**数据流**（与 RN 版一致）：DDP stream → 协议解析 → Room 写入 → Flow（Room DAO 的 observe）→ Compose UI 自动刷新。Zustand 的运行时状态（authStore、presenceStore 等）→ 各 feature 的 ViewModel + StateFlow。

## 4. 里程碑路线图

每行 = 一个详细计划文档的范围。M1 起每个计划在启动前编写。

| # | 名称 | 范围 | 验收标准 |
|---|---|---|---|
| M0 | 基础设施 | Gradle 骨架、依赖、主题 tokens、i18n 搬迁脚本、DDP/REST 协议层、Room schema、git/GitHub | 可构建安装启动（空白壳）+ 协议层单测通过 + Maestro launch YAML 通过 |
| M1 | 认证与多组织 | 企业码→登录（密码/SMS/SSO WebView）→Verification；token 持久化；组织切换+独立 db | 双账号真实登录/登出/切换，side-by-side 对照 RN |
| M2 | 会话列表+单聊 | RoomList（置顶/未读/左滑：置顶/标未读/删除）、连接状态横幅、RoomScreen 文本消息收发、历史分页 | 文本消息双端收发一致，列表状态一致 |
| M3 | 消息完整功能 | 图片/视频/文档/附件上传下载、富文本渲染、WebView 编辑器（ProseMirror）、转发、表情回应、已读回执、@提及 | 各消息类型 side-by-side |
| M4 | 群组+联系人 | RoomInfo/成员管理/公告、联系人+组织架构+团队、名片 | 群操作双端一致 |
| M5 | 搜索+presence+设置 | 全局搜索（spotlightv2）、房间内搜索、presence 流、设置/资料编辑 | 搜索结果与在线状态一致 |
| M6 | 推送+自更新 | 阿里云推送、深链导航到房间+高亮消息、应用内升级弹窗 | 推送点击跳转行为一致 |
| M7 | 待办+工作台+AI | 待办列表/房间待办/徽标、Labor WebView 工作台、AI Agent SSE 流式消息 | 三模块可用性对照 |
| M8 | 会议 | Agora 原生 SDK 集成、会议页、悬浮窗、横屏 | 会议创建/加入对照 |
| M9 | 邮件 | 凭证 RSA-OAEP 加密传输、账户设置、收件箱/详情/撰写 | 邮件收发对照 |
| M10 | 语音通话 | oncall 完整实现（Agora + callkeep 等价物），**方案可重做，不等 RN**（RN 版仍在 M0 gate） | 双端语音通话 |
| M11 | 收尾 | 全量功能 checklist 验收、性能（冷启动/列表帧率）、双语言核对、release 签名构建 | 全 checklist 通过 |

**依赖关系**：M0 → M1 → M2 → M3 → M4 → M5 →（M6–M10 依赖 M2/M3 的基础，可乱序）→ M11。

## 4.1 M0 遗留与 M1 前置任务（2026-09-11 最终评审裁定，不得静默丢失）

**M1 计划必须包含一个显式的「session 层加固」任务**，一次性吸收（全在 M1 必经路径上）：
1. per-request `timeoutMs`（组织切换需要 30s，TS `restClient.ts` 按次超时）
2. host 归一单源化：REST/DDP/db 名三处共用一个 `normalizeServer` 顶层函数（现为两套口径：DDP 静默降级 ws://，Retrofit 抛错）
3. 共享 OkHttpClient 单例（现每次 `RetrofitFactory.create` 新建，M1 多组织 = N 客户端 N 连接池）
4. DatabaseManager 缓存并发加固（注释或 ConcurrentHashMap/sync）
5. TS `raw?.data ?? raw` 平铺回退建模进 LoginResponse

**M2 开工前处理**：FontSize `Dp`→`sp`（无障碍字体缩放）；`t()` resId lazy 缓存（进 Compose 每帧调用前必须）；Maestro YAML appId 参数化（配 `.debug` 后缀）；`AppiaTheme` 包 MaterialTheme/Material3 colorScheme；debug 构建 `applicationIdSuffix ".debug"`（M1 落，同步 YAML）。

**M11 发布清单**：release.properties VERSION_* 接线（versionCode > 28187853）；应用图标；.gitignore 死路径清理；gradlew.bat 重生成。

**可带走的小项**（机会主义清理）：AppiaColors KDoc 错位；dark/light 字段集断言同义反复；pre-commit 脚本轮边（暂存区校验、启发式假阴性）；MmkvKvStore 委托补测；`1.json` 末尾换行；DdpClientTest 慢机 flake 加固；`File.delete()` 返回值检查（Windows 场景）。

## 4.2 M1 遗留与 M2 前置任务（2026-09-14 M1 终审裁定，不得静默丢失）

**写 M2 计划时必须列为显式任务行**：
1. 主线程 `resetDatabase` 文件删除移到后台 dispatcher（M1 已知偏差，M0 终审即挂账）
2. M2 起强制 stream handler 主线程 hop（`setStreamHandler` KDoc 线程契约已写明——第一个房间列表 handler 落地时执行）
3. Maestro 断言从可见文案换 testID（开启 `testTagsAsResourceId`）
4. orgCandidates 两段式（缓存先行即时显、REST 到后刷新——对照 RN MineMenu 行为）
5. 组织切换中手动登出按钮的豁免语义（当前切换中手动登出仍导航，终审记为可留 M2）
6. MainNavigationFlowTest 补 MockWebServer shutdown（线程卫生）

**flake 监控延续**：RealtimeSessionManagerTest `resubscribed on new connection` 仅全量偶发 WS 时序超时（M1 期 0/3 未复现）；CI 接入前按预案复验。

**可带走**（M1 终审 triage 全部核过）：trim NBSP/BOM 差异（触发面不存在）、POST 空 body "{}"、tSearch NULL vs 0（查询等价）、T7 verified 已修（终审顺车）、其余记录在案项。

## 5. 关键技术决策记录

1. **DDP 客户端移植**（`ddpClient.ts` → Kotlin）：连接握手（`connect` version "1" + support）、ping/pong 心跳（20s）、resume 登录、`sub`/`unsub`（25s 超时、`ready`/`nosub` ack）、重连（5s reopen）、按 `msg`/`collection`/`id` 三路事件分发。**逐行为对齐 TS 实现，单测覆盖每种消息**。
2. **REST 401 处理**：非组织切换期间收到 401 → 全局登出（移植 `restClient.ts` 的 `AUTH_SESSION_EXPIRED_ERROR` 行为）。
3. **Room schema 对照 WatermelonDB v6**（8 表：chats、subscriptions、rooms、messages、users、settings、uploads、custom_emojis；✍️ 修订 2026-09-11：原记 9 表含 threads——核实 Thread.ts 为 0 字节未注册文件，RN 以 `messages.tmid` 表达线程，故无 threads 表；M2 若需线程表再以权威来源设计）。列名保持 snake_case 原名（`_id`、`fname`、`tunread` 等），便于与服务端文档字段直接对照。JSON 复杂字段（roles、uids 等）存 TEXT，kotlinx.serialization 编解码。
4. **富文本编辑器**：优先方案 c——WebView 包装现有 ProseMirror（10tap-editor）构建产物，JS bridge 收发 HTML/Markdown；若 bridge 调试不可行降级方案 b（纯文本输入 + 附件，渲染侧仍完整）。M3 第一个任务做技术验证 spike。
5. **i18n key 不转换**：zh/en.json 的 `{模块}_{含义}` key 原样搬入 `strings.xml`（key 含大写/下划线，Android 资源名需转义——用 `res/values/strings-zh.xml` + 自定义查找函数 `t(key)` 封装，避免手工重命名 29KB×2 的映射错误）。pre-commit 中文检查用等价 shell 脚本。
6. **每组织独立数据库**：db 文件名 = 规范化 server URL（对照 `db.ts` 的 `PRELOGIN_NORMALIZED = '__prelogin__'`），MMKV 存多组织认证信息，切换 = 断 DDP → 换 db → 重连（generation 机制取消进行中操作）。

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| ProseMirror WebView bridge 复杂度 | M3 首任务 spike，失败即切方案 b，不阻塞其它消息类型 |
| DDP 边界行为（重连风暴、订阅竞态） | 逐行移植 + 单测复刻 `session.*.test.ts` 场景 |
| RN 版仍在迭代（移动靶） | 冻结基线；RN 侧新功能记录到 backlog，M11 统一评估 |
| 语音通话无 RN 参照物 | 已决策：完整实现、方案可重做、优先级靠后（M10） |
| minSdk 24 与新库兼容 | M0 锁定全部依赖版本并跑通 minSdk 24 模拟器构建 |
