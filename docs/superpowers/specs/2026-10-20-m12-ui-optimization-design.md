# M12 · UI 优化设计（主题桥接 / 图标 / 四域精修）

- **日期**：2026-10-20
- **背景**：M11 收尾后用户实测反馈：AA 与 RN 版 UI 差距大、观感差。代码层诊断出三个系统性根因（见 §1），叠加各屏与 RN 的局部偏差。
- **目标**：去掉「一眼丑」的来源（默认紫色主题、文字字形图标），并对四个高频域做逐屏 RN 对齐。**不做像素级全屏复刻**，验收线是「无丑感、品牌一致、与 RN 无突兀差异」。

## 1. 诊断（根因，代码实锤）

| # | 根因 | 证据 |
|---|---|---|
| 1 | **M3 组件全用默认紫色主题**：`AppiaTheme` 只提供 `LocalAppiaColors`，从未设置 `MaterialTheme.colorScheme`（全仓 grep `lightColorScheme\|colorScheme =` 零命中）。全 app 32 个文件用 AlertDialog/DropdownMenu/Button/OutlinedTextField 等 M3 组件，全部渲染 Material 基线紫 | `core/theme/Theme.kt`（21 行，无 MaterialTheme 包裹）；`MainActivity.kt:3458` 为唯一入口 |
| 2 | **图标是文字字形**：`"⌕" "⋮" "✕" "＋" "▶" "↻" "★"` 约 20 处 Unicode 字符当图标，粗细/基线/大小不齐；静音图标是 Canvas 画的圆+斜线占位（`ChatRow.kt:277` 自注「M2 占位图形（RN MuteIcon SVG 移植前）」） | grep 实测清单见 §3 |
| 3 | **局部屏与 RN 有形态偏差**（输入框、顶栏、间距等） | 四域精修清单见 §4 |

字体字号本身不是根因：现有 166+98+77+…处 fontSize 硬编码是逐屏转录 RN 的准确值（`roomRowSharedStyles.ts` 对照过：18/16/13/12sp 一致），**不重刷**。

## 2. 范围与顺序

1. **M3 主题桥接**（一次修复全 app，最高杠杆）
2. **图标替换**（混合方案：Material Icons + 少量手工移植 RN SVG）
3. **四域精修**：会话列表 → 聊天页 → 个人资料/设置 → 邮件域
4. 验收（前后截图对比 + 全测试绿）

## 3. 方案

### 3.1 M3 colorScheme 桥接（Theme.kt）

`AppiaTheme(isDark)` 内包 `MaterialTheme(colorScheme = appiaColorScheme(isDark), content = content)`，从已转录的 RN `Palette`（`AppiaColors.kt`）映射，**不新造色值**：

| M3 字段 | light 取值 | dark 取值 |
|---|---|---|
| primary / onPrimary | `tintColor #1D74F5` / 白 | `Palette.dark.tintColor` 同理 |
| primaryContainer / onPrimaryContainer | `Colors.primaryLight #E8F3FF` / `textPrimary` | dark 对应 |
| secondary（含 Container/on） | `tintActive #549DF9` 系 | 同 |
| background / surface / onBackground / onSurface | `backgroundColor #FFFFFF` / `titleText #0D0E12` | dark 对应 |
| surfaceVariant / onSurfaceVariant | `auxiliaryBackground #EFEFF4` / `auxiliaryText #9CA2A8` | 同 |
| error / onError | `dangerColor #F5455C` / 白 | 同 |
| outline / outlineVariant | `borderColor #E1E5E8` / `separatorColor #CBCBCC` | 同 |

- 映射函数 `appiaColorScheme(isDark: Boolean): ColorScheme` 放 `Theme.kt`，用 `lightColorScheme()/darkColorScheme()` 为底、只覆盖上表字段（其余 M3 角色留默认，避免过度映射引入意外）。
- 登录域 `LoginTheme`（AuthBrandedLayout.kt）已逐值对齐 RN，不动。
- 单测：Robolectric Compose 测试断言 `AppiaTheme(false)` 内 `MaterialTheme.colorScheme.primary == AppiaColors.light.tintColor` 等关键字段（沿用仓内既有 Compose 测试基建）。

### 3.2 图标替换（混合）

**依赖**：优先用 `androidx.compose.material:material-icons-core`（若 material3 传递链已带则零改动；没有则在 `libs.versions.toml` 加一行 BOM 托管的 core，**禁引 extended 大库**）。T1 核实。

**替换清单**（文字字形 → Material `Icons.Outlined.*`；`testTag` 必须留在可点击节点上不动）：

| 文件 | 现状 | 换成 |
|---|---|---|
| ChatListScreen.kt:221 | `⋮` 菜单 | `MoreVert` |
| RoomScreen.kt:727 | `⌕` 房内搜索 | `Search` |
| RoomScreen.kt:944/984 | `✕` 关闭 | `Close` |
| TableKatexOverlays.kt:75/208 | `✕` 关闭 | `Close` |
| MessageAttachments.kt:212/239 | `▶` 播放 | `PlayArrow` |
| MessageAttachments.kt:385 | `↻` 重载 | `Refresh` |
| ReactionBar.kt:203 | `＋` 加表态 | `Add` |
| MailChannelScreen.kt:153 | `⌕` 邮件搜索 | `Search` |
| LaborScreen.kt:176 | `⌕` 工作台搜索 | `Search` |
| CreateChannelMembersScreen.kt:835 | `✕` 移除已选 | `Close` |
| VoiceIncomingBanner.kt:117 / VoiceOtherCallBanner.kt:106 / VoiceParticipantCenter.kt:155 | `✕` | `Close` |
| RoomSearchScreen.kt:536 | `▶` 分区展开 | 对照 RN（RightArrowIcon 语义）→ `KeyboardArrowDown`/`KeyboardArrowRight`，T 时按 RN 定 |
| ChatRow.kt:175 | `★` 置顶星标 | `Star`（金色 `StarGold` 不变） |

**保留文字字形**（RN 自己就用字形，不算差距）：ChatListScreen.kt:210 头部 `⌕`（RN RoomListSearchBar:29 同为 `⌕`）。

**手工移植**（RN 特有图形，SVG path → Compose `ImageVector`，建 `core/theme/AppiaIcons.kt`）：

| 组件 | RN 源 | 说明 |
|---|---|---|
| `AppiaIcons.Mute` | `components/icons/MuteIcon.tsx`（IcoMoon 单 path，viewBox 1024） | 替换 ChatRow.kt Canvas 占位（MuteGlyph 删除）；颜色逻辑（未读深蓝/灰）不变 |

实现时以 `grep -rn '⌕\|⋮\|✕\|＋\|▶\|↻\|★\|☰'` 全仓兜底复查，防漏网。

### 3.3 四域精修（RN 冻结版为唯一视觉基准）

每域固定流程：读 RN 对应文件 → 列 per-screen diff 表（色值/字号/间距/组件形态）→ 只修偏差项 → 不改业务逻辑、不动 testTag、不动 i18n key。

1. **会话列表**：`RoomListProfileHeader.tsx`（头像 40dp+姓名 18/700+右侧菜单钮的形态——AA 现在无头像，按 RN 补头像位）、`roomRowSharedStyles.ts`（行 padding 14/10/16 已对齐，复核）、行内图标（§3.2）、`MailChannelItem` 对照 MailChannelRow。
2. **聊天页**：RoomScreen 顶栏/输入栏/消息行对照 RN `RoomHeader.tsx` + RoomScreen 样式；附件卡（音频播放/视频/文件图标与圆角）；MessageActionsSheet 底部弹层形态。
3. **个人资料/设置**：ProfileScreen/StatusEditScreen 对照 RN `ProfileScreen`/`MineMenuScreen`（行高、分隔线、箭头、二维码行）。
4. **邮件域**：MailChannelScreen/MailThreadScreen/MailDetailScreen/MailComposeScreen/MailAccountSetupScreen 对照 RN 同名屏（列表行、未读标记、撰写页工具栏）。

## 4. 明确不做（记档）

- **不建全局 Typography**、不重写 166+ 处硬编码字号（是 RN 准确转录，重写大 diff 零视觉收益）。
- 不动深色模式架构（Palette.dark 已转录，桥接自动覆盖）。
- 不做像素级全屏复刻、不新增动效。
- 不动登录域 AuthBrandedLayout/LoginTheme（已对齐）。

## 5. 验收标准

1. 模拟器前后截图对比：企业码页/会话列表/聊天页/设置/邮件 ≥5 屏，紫色组件清零、字形图标清零（保留项除外）。
2. 全测试绿：`./gradlew test` 通过；testTag 零变化（Maestro 流与 UI 测试不受影响）。
3. 新增单测：colorScheme 桥接映射断言。

## 6. 约束（沿用常设）

- 禁跑 `scripts/migrate_i18n.py`；strings.xml 手工维护。
- RN 仓 `/Users/bitmain/Projects/rebuild-mobile/appiaMobile` 只读（冻结 c92e3df）。
- pre-commit 拦 .kt/.kts 中文标点；minify 保持 false。
- main 直做（无 worktree），每任务一提交，收尾 push；提交信息中文 `类型(范围): 描述`。
