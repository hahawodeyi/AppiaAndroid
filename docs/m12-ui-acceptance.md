# M12 UI 优化验收记录

**验收日期**：2026-10-20 · **验收人**：Task 8（自动验收）· **分支**：main @ f1af5e5

**范围**：T1 主题桥接（M3 colorScheme 去紫）/ T2 图标基建（material-icons-core + RN SVG 移植）/ T3 返回箭头 / T4 聊天域 / T5 会话列表域 / T6 邮件域+资料页 / T7 全仓字形清扫 / T8 本验收。

---

## 1. 字形清扫 grep 终态

```bash
grep -rn 'Text("' app/src/main/kotlin/ --include="*.kt" | grep -P 'Text\("[^"]*[←→↑↓◄►▲▼⌕⋮✓✕＋☰◀▶⌄]'
```

终态命中 **10 处**（单行 grep 模式；另有多行 `Text(` 形态漏计，见下）。逐处经 RN 冻结源核实，拆两栏：

**栏 A · 已核实 RN parity（RN 用同款文本字形）**：

| 位置 | 字形 | RN 对照 |
|---|---|---|
| MessageAttachments.kt:213/240 | `▶` | RN MessageVideo:46 / MessageAudio:48 同为 `▶` 文本字形 |
| RoomSearchScreen.kt:540 | `▶` | RN 搜索播放按钮同字形 |
| RoomMembersScreen.kt:526/615 | `✓` | RN RoomMembersScreen:108 勾选同字形 |
| DocPreview.kt:150（多行） | `↓` | RN DocPreviewScreen:204 同为 `↓` 文本字形 |
| ChatListScreen.kt:253（多行） | `⌕` | RN RoomListSearchBar:29 同字形（计划保留项） |

**栏 B · AA 现状保留，RN 为 SVG/无对应——真差距，记档 M13**（M12 终审新增盘点，详见总纲 §4.11）：

| 位置 | 字形 | RN 实际 |
|---|---|---|
| UnreadBanner.kt:53 | `↓` | RN 用 ToTopIcon SVG（#2878FF 14dp） |
| AttachmentViewer.kt:195（多行） | `↓` | RN 用 DownloadIcon SVG（保存钮，MediaPreviewScreen:137） |
| MailDetailScreen.kt:369 | `↓` | RN 用 DownloadIcon SVG（附件下载，MailDetailScreen:519） |
| RoomScreen.kt:891（多行） | `↓` | AA 自有滚到底控件，RN 无对应按钮 |
| MeetingFormScreen.kt:605/927 | `✓` | RN 会议室选择为时间轴选格、成员选择无 ✓ 字形 |
| RoomInfoScreen.kt:746 | `✓` | RN RoomUsageSettingDrawer 无 ✓，仅底色选中 |
| RoomScreen.kt:717 | `☑` | RN 用 TodoIcon SVG（聊天头待办入口；`☑` 不在 grep 字符类内，第 15 处游离项） |

全仓真终态 = 单行 10 + 多行 4 + 游离 ☑ 1 = **15 处**。已被替换的：`←`（返回箭头 → AutoMirrored ArrowBack ×10）、`⋮`（MoreVert）、`⌕`（RoomScreen 房内搜索 → Icons.Outlined.Search；ChatListScreen:253 保留）、`★`（去星标换置顶整行灰底）、`↻`（去红圆换文字 pill「重试」）、`⌹/⏹/✉/✎/›`（RN 特有 SVG 移植 + RightArrow）。

**图标终态计数**：Material `Icons.*` 22 处；RN SVG 手工移植 drawable（`ic_appia_*`，15 个资源）`painterResource` 15 处调用点，覆盖 9 文件（chat/chatlist/settings/mail/contacts/voice 域）。

## 2. 全量测试与构建

- `./gradlew :app:testDebugUnitTest`：**2775 tests, 0 failed, 0 skipped——BUILD SUCCESSFUL**（flake 三件套 RealtimeSessionManagerTest / MeetingRoomScreenSmokeTest / MediaCacheTest 本次直接全绿，无需隔离重跑）
- `./gradlew :app:assembleDebug`：**BUILD SUCCESSFUL**（app-debug.apk 93.1MB → 实测 94.5MB）

### 验收中发现并修复的测试回归（非 M12 产品缺陷）

`MainNavigationFlowTest.org switch click survives sheet dismissal and refreshes main screen` 在 a8dda73 后稳定失败。根因（诊断过程见 task-8-report）：T5 给顶栏加头像后，**头像 initial「B」与候选公司名「B」文本撞车**（`onNodeWithText("B")` 匹配到不可点的顶栏 initial）；且**顶栏 AsyncImage 与候选 REST 同落 MockWebServer FIFO**（avatar 请求先到，吃掉候选 JSON）。修复（f1af5e5，纯测试侧）：候选名改 BetaCorp + 垫一个 404 给 avatar 请求。产品代码零改动。

## 3. 装机截图（模拟器可达面）

模拟器 Pixel_7 AVD（Apple Silicon），`adb install -r` 装机后实拍：

| 截图 | 内容 | 验证点 |
|---|---|---|
| `/tmp/m12-enterprise.png` | 企业码页（首启隐私同意通过后） | M3 控件无紫（OutlinedTextField 聚焦蓝 #1D74F5）、蓝色主按钮、字体层级 |
| `/tmp/m12-envhost.png` | envHost 后门页（长按 label，debug 特有） | OutlinedTextField 描边/聚焦态无紫、titleMedium 字体 |

**登录态界面（会话列表/聊天页/设置/邮件）模拟器无凭证不可达**（M11-T7 同款边界，如实记档）——登录态视觉由用户真机确认，清单见 §5。

## 4. Maestro 冒烟

- `maestro test maestro/launch_android.yaml`：**PASS**（launch → 隐私同意 optional → enterprise_code_input 可见 → 「版本: 1.2.0」断言通过——versionName 未被 M12 任何任务改动）
- `maestro test maestro/login_reach_login.yaml`：**PASS**（企业码失败链路完整走通）

testTag 设计验证成立：M12 为视觉替换，`qa-*` 标签模板未动。唯一例外已在 T5 提交内披露：assistant 分区按 RN parity 不再渲染段头，`qa-room-list-section-assistant` 实例随之消失（tag 模板 `qa-room-list-section-${sectionId}` 保留），`ChatListScreenTest` 同提交内改用 `textExists(t("Agent"))` 定位，Maestro 对该 tag 零引用。

## 5. 用户真机确认清单（登录态五屏）

模拟器无凭证，以下界面请用户在真机（真实账号）上目视确认 M12 效果：

1. **会话列表**：顶栏头像（40dp 圆 + presence 绿点）+ 分区头（▼ 折叠箭头 + 4dp 间距 + #F5F6F8 底色）+ 置顶行整行灰底（无星标）+ ⋮ 菜单原生图标
2. **聊天页**：顶栏返回 AutoMirrored 箭头 + 发送 `↑`（ic_appia_send）+ AI 停止 `⏹` + 失败重试文字 pill（红底白字「重试」）+ 附件 `▶` 播放
3. **资料设置**：SettingsHeader 返回箭头 + 资料页 `›`（ic_appia_right_arrow）行箭头 + `✎`（ic_appia_compose）编辑入口
4. **邮件**：列表行 `✉`（ic_appia_email）+ 账户绑定 `✎` + 详情展开 `↓` + 撰写 `✎`
5. **任一弹层**（如「我的企业」）：ModalBottomSheet 无紫色痕迹、Checkbox/列表色板对齐 RN
6. **深色模式**（终审补）：切系统 dark 再走一遍上述五屏——T1 主题桥接改了全局 dark 映射，模拟器验收仅覆盖 light 态；已知遗留：4 个硬编码 `containerColor = Color.White` 的 ModalBottomSheet（OrgSwitchSheet/RoomInfo/RoomMembers/SmsCaptcha）在 dark 下为白底，M12 前遗留、记档总纲 §4.11

## 6. 登录域未动声明

M12 未改动登录链路任何逻辑：企业码/验证码/SSO/隐私同意门禁的行为与 M11 验收态一致；`AuthWebScreen`/`AreaCodeScreen` 仅换返回箭头图标（AutoMirrored ArrowBack）。Maestro 双流通过即行为证据。

## 7. 遗留收口（验收前置四项）

1. Theme.kt `onError = Color.White` 补齐（error/onError 对，spec §3.1）✅
2. AppiaColorSchemeTest 补 primaryContainer/onPrimaryContainer 断言（light #E8F3FF/#1D2129，dark focusedBackground/titleText）✅
3. ChatListScreen 死 import `cn.appia.im.R` 删除（grep 确认零 `R.` 引用后删）✅
4. 分区头 chevron 与标题间 4dp 间距（RN chevronBox marginRight 4）✅

以上随 `fix(ui): 审查遗留收口（onError 映射与分区头间距）`（36da63a）提交，全量测试覆盖。
