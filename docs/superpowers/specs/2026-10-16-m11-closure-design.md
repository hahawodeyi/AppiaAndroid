# M11 收尾验收设计（closure & acceptance）

- 日期：2026-10-16
- 状态：已批准（brainstorming 六问 + 方案 A 结构确认）
- 前序：总纲 §4.10（M9+M10 遗留与 M11 前置任务固化）；M10 已收口（efceee7）
- spec 权威性：本文件是 M11 实施计划（writing-plans 产物）的权威依据；与总纲冲突时以本文件 + 用户六问裁定为准

## 0. 背景与范围

AppiaAndroid 原生重写 M0-M10 十个功能域已全部完成（2756 单测、lint 0 error、Maestro 双绿、模拟器零崩溃、versionName 1.1.0）。M11 是最终里程碑：全量功能验收 + 发布工程收尾，产出可发布的签名 APK 与验收协议，正式闭合重写工程。

**范围**：RN delta 盘点、全量功能 checklist（十域 side-by-side）、缺口修复（主链路）、发布工程（图标/release 构建/abiFilters 评估/工程清理）、i18n 双语言核对、性能走查、验收协议收尾。

**不在范围**：上架商店、AAB、R8 混淆（minify 保持 false）、Macrobenchmark、backlog 三页的存量项修复（只评估分类不实施，见 §7）、RN 冻结后新功能的实现（只盘点裁定）。

## 1. 已锁定裁定（brainstorming 六问）

| # | 问题 | 裁定 |
|---|---|---|
| 1 | 验收基准 | **冻结基线 + delta 评估**：checklist 对照冻结基线（M1-M10 十域实现依据）；RN 冻结后新提交单独 delta 盘点，逐条裁定「追进发布/记档不做」，结果入协议 |
| 2 | 性能口径 | **定性走查 + 实测记录**：冷启动/列表滚动/切房/图片加载等关键路径真机/模拟器走查无明显卡顿即可，不设硬指标；走查实测数据记录入协议供参考；不引入 Macrobenchmark/baseline profile |
| 3 | release 深度 | **签名 APK 可安装可跑**：assembleRelease 在 release.properties 就位时产出签名 APK，安装后启动零崩溃、核心链路冒烟绿；M11 内验证「无 keystore 时降级 debug 签名可跑」，真签名构建留用户域（用户放入 keystore 后自行验证）；不做 AAB/上架 |
| 4 | 缺口处置 | **主链路修/次要记档**：盘点缺口按 FIX/BACKLOG/CLOSE 三分流（沿 M1-M10 终审口径）；影响主链路（登录/消息/导航级）的在 M11 内修复；次要（边角交互/视觉偏差）记档 backlog 不修 |
| 5 | 双语言核对 | **脚本比对 + 关键界面抽查**：脚本比对 zh/en strings.xml key 集合对称性 + 与 RN locale 文件 key 覆盖度，差异清单人工裁定；关键界面（登录/列表/设置/会议/邮件/语音）中英文显示抽查 |
| 6 | 图标与 keystore | **搬 RN 图标 + keystore 留用户域**：从 RN 仓搬 mipmap 全套（ic_launcher/ic_launcher_round 全密度）+ manifest 加 android:icon/roundIcon；真签名验证留用户域 |

## 2. 任务结构（方案 A：盘点先行 + 修复槽位）

8 任务 + 修复槽位机制。任务序即依赖序（T1→T8）。

| # | 任务 | 交付物 | 体量预估 |
|---|---|---|---|
| T1 | RN delta 盘点 | 冻结点（2026-09-10，M0 评审锁定）到 RN HEAD 的 `git log` 逐条盘点；每条裁定：追进发布 / 记档不做 / 无法离线判定（用户域）；报告并入协议文档 | 中（提交数决定） |
| T2 | checklist 基础域 | M1-M4（认证多组织/会话列表单聊/消息完整/群组联系人）side-by-side checklist：RN 功能点 → AA 状态三列（✅已实现 / ❌缺口 / 🔶授权差异——引用协议已知差异节或总纲裁定）| 大（文档任务） |
| T3 | checklist 扩展域 | M5-M7（搜索 presence 设置/推送自更新/待办工作台 AI）同结构 | 大 |
| T4 | checklist RTC 域 | M8-M10（会议/邮件/语音通话）同结构 | 大 |
| T5 | 缺口修复槽位 | 按 T2-T4 盘点结果分流实施：主链路缺口修复任务（计划预留 ≤3 个槽位）；次要缺口汇总记档（并入协议 + 追加 backlog）；预算超限时升级为「缺口报告 + 用户裁定」不静默扩体量 | 由盘点决定 |
| T6 | 发布工程 | RN mipmap 图标搬运 + manifest android:icon/roundIcon 接线；`assembleRelease` 构建验证（无 keystore 降级 debug 签名路径）+ 模拟器安装冒烟（启动零崩溃 + Maestro 启动链）；abiFilters splits 评估结论（arm64 单 ABI 93.1MB → 是否拆 per-ABI，结论+理由记协议）；.gitignore 死路径清理；gradlew.bat 重生成；versionName 1.2.0 | 中 |
| T7 | i18n 双语言核对 | 脚本（一次性，scripts/ 或协议附录）比对：AA zh/en key 集合对称性；AA ↔ RN locale key 覆盖度（RN camelCase → AA lowercase 归一后比对）；差异清单逐条裁定；关键界面中英显示抽查（截图或走查记录） | 中 |
| T8 | 性能走查 + 验收协议收尾 | 性能：冷启动（time-to-first-draw / 冷启动到登录页）、会话列表滚动、切房、图片加载走查，实测数据记录（不设阈值，供参考）；协议：第二十四节（M11 验收清单——含各域外部依赖汇总）、第二十五节（发布状态：版本/签名/图标/已知差异总索引）；README M11 段；门禁×3 + Maestro + 模拟器走查 | 中 |

### T5 槽位机制

T2/T3/T4 三个 checklist 任务的报告必须含结构化缺口清单（域/功能点/严重度：main-chain | minor | visual）。T5 dispatch 前由 controller 汇总三域缺口清单，按裁定 4 分流：

- main-chain 缺口 → 槽位任务逐个修复（每缺口一个 dispatch，审查同 SDD 标准）；超 3 个时停下升级用户裁定
- minor/visual 缺口 → 追加记档：backlog-m11.md 新建（或并入协议文档），不实施

## 3. 关键技术点

### 3.1 RN 冻结点与 delta

- 冻结点：2026-09-10 grilling 锁定的 RN HEAD（M0 计划引用的提交）。T1 实施时先 `git -C appiaMobile log --oneline <冻结点>..HEAD` 确认提交数；若提交量大（>100），按目录前缀聚类盘点（src/features/*、src/i18n/*、android/* 等）而非逐提交
- 每条 delta 的裁定输入：该提交是否触及 M1-M10 已实现域的功能行为（vs 纯重构/埋点/CI/RN 侧 bug 修复不改变冻结基线功能面）
- 「无法离线判定」类目：需要服务端/真机/双账号才能验证的功能（如服务端开关门控的新 UI）

### 3.2 checklist 文档结构（T2/T3/T4 统一）

每域一份小节，行结构：

```markdown
| RN 功能点（文件锚） | AA 状态 | 证据/差异引用 |
|---|---|---|
| 单聊会话左右滑归档/标记（SwipeRow.tsx） | ✅ | M2 终审 + RoomListScreen.kt |
```

- 功能点粒度：RN screens/components 层的用户可见功能（非内部函数）
- 证据列：AA 实现文件锚、里程碑终审记录、协议已知差异节编号
- ❌ 缺口行必须在任务报告的结构化缺口清单中登记（供 T5 消费）

### 3.3 发布工程细节（T6）

- 图标：`appiaMobile/android/app/src/main/res/mipmap-*` → `app/src/main/res/`；manifest `<application android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round">`；注意 mipmap-anydpi-v26 的 adaptive icon XML 引用的 drawable（前景/背景色）一并搬运
- release 构建：`./gradlew :app:assembleRelease`——无 keystore 时降级 debug 签名（既有机制），产物安装 Pixel_7 验证启动零崩溃 + `maestro test launch_android.yaml` 绿（注意 launch yaml 的 appId 是 debug 包名 `.debug`——release 包冒烟需要临时安装 release 产物到模拟器后手动启动验证，或用 `adb shell monkey`/am start；Maestro config 若仅 debug 包名则 release 冒烟以 am start + logcat 零 FATAL 为准）
- versionCode：release.properties VERSION_CODE 机制已建（下限守卫 28187900）；M11 不放 keystore，构建时用环境变量 VERSION_CODE 覆盖机制验证注入（build.gradle.kts 既有）
- abiFilters 评估：现 `ndk.abiFilters += "arm64-v8a"`（M8 裁定）单 ABI 93.1MB。评估维度：拆分收益（x86_64 模拟器支持/intel 设备）vs 成本（AAB 才能享受 per-ABI 动态下发，纯 APK 拆分只增产物数）。预期结论：APK 形态保持 arm64 单 ABI，x86_64 需求走临时移除 abiFilters（既有惯例）——结论与理由记协议二十五节
- .gitignore：M0 时列的死路径（如 `.superpowers/` 变动）核对清理；gradlew.bat：`gradle wrapper --gradle-version <current>` 重生成（若工程从无 bat 文件，此项核实后改为「确认不需要」记档）

### 3.4 i18n 比对脚本（T7）

- 输入：`app/src/main/res/values/strings.xml`（默认=en）+ `values-zh/strings.xml` + RN `appiaMobile/src/i18n/en.json`/`zh.json`
- 比对逻辑：key 集合对称（AA zh vs AA en）；覆盖度（RN key lowercase 后 AA 是否有——M4 手工 5+2 键、M9/M10 各域补充键的口径核对）；格式化占位符一致性（%1$s 等两侧数量/类型）
- 一次性脚本不进 scripts/ 常驻（用后即弃或挂协议附录）；差异裁定输出入协议

### 3.5 性能走查（T8）

- 冷启动：`adb shell am start -W cn.appia.im.debug/cn.appia.im.MainActivity` 取 TotalTime；连做 5 次取中位
- 列表帧率：开发者选项 GPU 渲染条 / `adb shell dumpsys gfxinfo cn.appia.im.debug framestats` 滚动会话列表采样
- 无阈值判定：走查过程无肉眼可感卡顿（掉帧直方图记录供参考）+ 无 ANR
- 依赖真实会话数据的路径（登录后列表/切房）需要测试账号——若账号不可用，记录「模拟器无会话态走查」边界如实入协议

## 4. 错误处理与风险

| 风险 | 吸收 |
|---|---|
| 盘点出大量主链路缺口 | T5 预算 ≤3 槽位；超限升级用户裁定（缺口报告呈现 trade-off，不静默扩体量） |
| checklist 文档任务失控 | 按域拆三（T2/T3/T4）；每任务的功能点粒度锚定「用户可见功能」；报告附结构化缺口清单供 T5 |
| RN delta 提交量大 | 按目录聚类盘点；「无法离线判定」类目直接入协议不硬判 |
| release 构建暴露签名/版本问题 | minify 已 false 无 R8 面；降级 debug 签名路径是既有机制；问题即修复（属主链路） |
| i18n 差异清单过大 | 多数预期为 RN 有 AA 无的未使用键（各域裁定的授权差异）；逐条裁定而非逐条修 |
| 测试账号不可用 | 性能走查/界面抽查降级为「无会话态」边界记录，如实入协议 |

## 4a. 域级验收标准（总纲 §3「全 checklist 通过」的细化）

- T2/T3/T4 checklist：每域每行有状态三列标记 + 证据锚；❌ 缺口全部进入结构化清单（无遗漏登记）
- T5：main-chain 缺口修复后对应 checklist 行翻转为 ✅（带新证据锚）；超限项有用户裁定记录
- T6：图标在模拟器安装后可见（桌面图标非默认机器人）；assembleRelease 产物安装启动零崩溃；abiFilters 结论入协议；.gitignore/gradlew.bat 项有处置记录
- T7：差异清单每条有裁定（对称性差异必须修复——zh/en 键缺失属主链路；覆盖度差异按裁定 4 分流）；关键界面中英截图/走查记录入协议
- T8：性能实测数据入协议；协议二十四/二十五节完整；门禁×3 零新增 flake；Maestro 双绿；versionName 1.2.0；push

## 5. 测试策略

- 单测/门禁：沿用既有口径（testDebugUnitTest 全量 + lintDebug + assembleDebug，门禁×3，既有 flake 隔离绿标准）
- Maestro：launch + login 链双绿（沿 M10 口径，断言 1.2.0）
- release 冒烟：assembleRelease 产物 am start + logcat 零 FATAL（§3.3 细节）
- checklist/i18n/性能的验证方式分别是文档审查 + 脚本输出 + 走查记录——不新增单测面（T5 修复任务按修复内容补测试）

## 6. 已知差异与文档约定

- 协议二十四节（M11 验收清单）：合并 M1-M10 各节自测清单的外部依赖汇总（EMAS 控制台/worktable/bot/凭据抓包/Agora AppId/语音双账号/shengwang/真机 OEM）+ M11 新增项
- 协议二十五节（发布状态）：版本 1.2.0、签名状态（keystore 用户域）、图标状态、abiFilters 结论、全量已知差异总索引（各域已知差异节 → 总表）
- backlog-m11.md：T5 记档缺口 + T1 delta「记档不做」项 + backlog-m6/m8/m10 三页的 M11 评估结论（每页头部加「M11 评估：…」行——只评估分类不实施）

## 7. backlog 三页收编评估（总纲 §4.10-9）

T5 dispatch 前由 controller 完成一次性评估，结论写各页头部：

- **backlog-m6.md**（20 行）：录音 UI 类不需要（M10 已实现语音但录音缓 M10 backlog——注意区分）；FCM taskNames 启发式等工具链项 → 「记档不做（工具链，等 FCM 真正启用时再议）」
- **backlog-m8.md**（25 行）：视频帧上屏（§4.9-2）→ 评估「记档不做（发布不阻塞，RTC 域用户域验收覆盖）」或升级；其余 minor 批量 → 记档不做
- **backlog-m10.md**（21 行）：#1 录音/摘要 → 记档不做（依赖服务端）；#2 系统级浮窗 → 记档不做（FGS 通知承载 v1）；#3 busy 信令 → 无法离线判定（服务端依赖）；#4/#5 用户域；#6-#13 低危随触碰
- 评估只改各页头部一行，不逐项展开

## 8. 执行方式

SDD（superpowers:subagent-driven-development）：每任务 fresh implementer + task review（Spec ✅ + Quality 双轴）+ 修复轮；T5 槽位任务同样走完整 SDD 循环。main 直做（用户既定工作流），每任务一提交，里程碑收尾统一 push。

## 9. 成功判据

- 全量 checklist（十域）无未处置的 ❌ 行（修复或记档均有记录）
- release APK（降级 debug 签名）安装可跑、冒烟绿、图标就位、versionName 1.2.0
- i18n 对称性差异清零；覆盖度差异全裁定
- 协议二十四/二十五节 + backlog-m11 评估结论落盘
- 门禁×3 + Maestro 双绿，push 完成里程碑闭合
