# M12 · UI 优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 AA 视觉丑感三根因（M3 默认紫色主题 / 文字字形图标 / 四域局部偏差），对齐 RN 冻结版观感。

**Architecture:** 三层递进——T1 主题桥接（一处改动全 app 生效）→ T2 图标基建（material-icons-core + RN SVG 手工移植 drawable）→ T3-T7 按域替换与精修（共享头/聊天/会话列表/邮件+资料/全仓清扫）→ T8 验收（全测试 + 字形清扫 grep + 截图 + Maestro）。

**Tech Stack:** 既有栈（Kotlin/Compose/Robolectric/JUnit5）；新增唯一依赖 `androidx.compose.material:material-icons-core:1.7.8`。

**Spec:** `docs/superpowers/specs/2026-10-20-m12-ui-optimization-design.md`（诊断 / §3.1 映射表 / §3.2 图标方案 / §3.3 四域精修 / §4 不做项 / §5 验收标准）

## Global Constraints

- **RN 冻结版为唯一视觉基准**（spec §3.3）；RN 仓只读：`/Users/bitmain/Projects/rebuild-mobile/appiaMobile`
- **不动登录域品牌**：AuthBrandedLayout/LoginTheme/PrivacyConsentScreen 已对齐（spec §4）；glyph 清扫例外：AuthWebScreen/AreaCodeScreen 的 `←`（非品牌组件，属 §3.2 全仓兜底）
- **不建全局 Typography、不重刷字号**（spec §4）；**不动深色模式架构**；**不做像素级复刻/动效**
- **testTag 零变化**：所有替换保持原 testTag 在可点击节点上（spec §5-2）；i18n key 不增不删
- **禁跑 scripts/migrate_i18n.py**（M4 起常设禁令）；pre-commit 拦 .kt/.kts 中文标点（U+3001 顿号也拦）；minify 保持 false
- **main 直做（无 worktree），每任务一提交，收尾 push**；提交信息中文 `类型(范围): 描述`
- 既有 flake（RealtimeSessionManagerTest / MeetingRoomScreenSmokeTest / MediaCacheTest）**隔离绿即算过**（M11 口径）
- 版本号不动：versionName 1.2.0（spec 无版本要求；Maestro 断言 1.2.0 不变）

## 实施前裁定（spec §3.2 表格的核实修正——依据 spec 总则「RN 冻结版为唯一视觉基准」）

spec §3.2 表格是核实前的预估；逐文件核实 RN 后有五处修正，reviewer 勿按原表打回：

| spec 原表 | 核实结果 | 裁定 |
|---|---|---|
| `▶` → PlayArrow（MessageAttachments ×2、RoomSearchScreen:536） | RN 自己就是 `▶` 文本字形（`MessageVideo/index.tsx:46`、`MessageAudio/index.tsx:48`、playIcon style fontSize 28/白） | **保留字形**（RN parity） |
| `↻` → Refresh（MessageAttachments:385） | RN 无 ↻ 图标——失败徽标是**文字 pill**「Retry {{name}}」（`LocalAttachmentPreviewList.tsx:103-121`：absolute top6 left6，paddingH6/V3，radius 10，bg #FF4D4F，10sp 白） | **对齐文字徽标**（去掉 18dp 红圆 + ↻） |
| `★` → Star（ChatRow:175） | RN 行内**无星标**——置顶行整行灰底 `rgb(238,238,238)`（`RoomItemWrapper` favorite ? shadowBackGround : 白；MailChannelItem 同） | **去星标换整行灰底 #EEEEEE** |
| AppiaIcons 只列 Mute 一个 | 用户批准的混合方案 = 「标准动作用库，**RN 特有图形**手工转」；Send（`↑` 发送）/Stop（`⏹` AI 停止）/Email（`✉`）/Compose（`✎`）/RightArrow（ProfileScreen `›`）均为 RN 特有 SVG（RN 分别用 SendIcon/StopIcon/EmailIcon/ComposeIcon/RightArrowIcon） | **六个一起移植**（Mute/Send/Stop/Email/Compose/RightArrow） |
| `⌕` RoomScreen:727 → Icons.Outlined.Search | 确认（RN RoomScreen/index.tsx:189 SearchIcon 22dp，放大镜线性图形与 Material 近似） | 照 spec 执行 |

**保留字形的最终清单**（RN 同款字形，非差距，勿改）：ChatListScreen:210 `⌕`（RN RoomListSearchBar:29 同字形）、全部 `›`/`▾`/`▸`/`⌄` chevron（RN SettingsRow:41、TeamScreen:270、ForwardSelectScreen:466、RoomListSectionedChatList:83 `▼` 等同字形）、LoginScreen:824 `▾`。

---

### Task 1: M3 colorScheme 桥接

**Files:**
- Modify: `app/src/main/kotlin/cn/appia/im/core/theme/Theme.kt`（全文 21 行，追加映射函数 + MaterialTheme 包裹）
- Test: `app/src/test/kotlin/cn/appia/im/core/theme/AppiaColorSchemeTest.kt`（新建）

**Interfaces:**
- Consumes: `AppiaColors.light/dark`（既有 Palette 实例，字段名同 RN）
- Produces: `fun appiaColorScheme(isDark: Boolean): ColorScheme`；`AppiaTheme(isDark)` 内包裹 `MaterialTheme`——后续所有任务的 M3 组件（Button/TextField/AlertDialog/DropdownMenu/Switch/IconButton）自动继承品牌蓝

- [ ] **Step 1: 写失败测试**

```kotlin
package cn.appia.im.core.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** M12-T1：M3 colorScheme 桥接映射断言（spec §3.1 表逐字段）。 */
class AppiaColorSchemeTest {

    @Test
    fun `light scheme bridges RN palette`() {
        val s = appiaColorScheme(false)
        assertEquals(AppiaColors.light.tintColor, s.primary)          // #1D74F5
        assertEquals(AppiaColors.light.backgroundColor, s.background) // #FFFFFF
        assertEquals(AppiaColors.light.titleText, s.onSurface)        // #0D0E12
        assertEquals(AppiaColors.light.auxiliaryText, s.onSurfaceVariant)
        assertEquals(AppiaColors.light.dangerColor, s.error)
        assertEquals(AppiaColors.light.borderColor, s.outline)
        assertEquals(AppiaColors.light.separatorColor, s.outlineVariant)
    }

    @Test
    fun `dark scheme bridges RN palette`() {
        val s = appiaColorScheme(true)
        assertEquals(AppiaColors.dark.tintColor, s.primary)
        assertEquals(AppiaColors.dark.backgroundColor, s.background)  // #030B1B
        assertEquals(AppiaColors.dark.titleText, s.onSurface)         // #F9F9F9
        assertEquals(AppiaColors.dark.auxiliaryText, s.onSurfaceVariant)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.theme.AppiaColorSchemeTest"`
Expected: 编译失败 `unresolved reference: appiaColorScheme`

- [ ] **Step 3: 实现 Theme.kt（整文件替换）**

```kotlin
package cn.appia.im.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 当前主题色板，对应 RN 的 `useTheme().colors`。 */
val LocalAppiaColors = staticCompositionLocalOf { AppiaColors.light }

/**
 * M3 colorScheme 桥接（M12-T1 / spec §3.1）：RN Palette 字段 → Material 角色。
 * 仅覆盖使用面角色，其余留 M3 默认——桥接前全 app M3 组件渲染 Material 基线紫，
 * 这是「UI 难看」的头号根因；primary 用 tintColor #1D74F5（RN 控件蓝）。
 */
fun appiaColorScheme(isDark: Boolean): ColorScheme {
    val p = if (isDark) AppiaColors.dark else AppiaColors.light
    val container = if (isDark) p.focusedBackground else Color(0xFFE8F3FF) // Colors.primaryLight
    val onContainer = if (isDark) p.titleText else Color(0xFF1D2129)       // Colors.textPrimary
    return if (isDark) {
        darkColorScheme(
            primary = p.tintColor, onPrimary = Color.White,
            primaryContainer = container, onPrimaryContainer = onContainer,
            secondary = p.tintActive,
            background = p.backgroundColor, onBackground = p.titleText,
            surface = p.backgroundColor, onSurface = p.titleText,
            surfaceVariant = p.auxiliaryBackground, onSurfaceVariant = p.auxiliaryText,
            error = p.dangerColor,
            outline = p.borderColor, outlineVariant = p.separatorColor,
        )
    } else {
        lightColorScheme(
            primary = p.tintColor, onPrimary = Color.White,
            primaryContainer = container, onPrimaryContainer = onContainer,
            secondary = p.tintActive,
            background = p.backgroundColor, onBackground = p.titleText,
            surface = p.backgroundColor, onSurface = p.titleText,
            surfaceVariant = p.auxiliaryBackground, onSurfaceVariant = p.auxiliaryText,
            error = p.dangerColor,
            outline = p.borderColor, outlineVariant = p.separatorColor,
        )
    }
}

/** RN 主题的 Compose 入口：`isDark` 映射 colors.ts 的 light/dark。 */
@Composable
fun AppiaTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (isDark) AppiaColors.dark else AppiaColors.light
    CompositionLocalProvider(LocalAppiaColors provides colors) {
        MaterialTheme(colorScheme = appiaColorScheme(isDark), content = content)
    }
}
```

（注：删掉示例里的无用 `builder` 局部变量——那是笔误，最终代码不含它。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.theme.AppiaColorSchemeTest"`
Expected: PASS（2 tests）

- [ ] **Step 5: 全量回归（主题是全局改动）**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（既有三 flake 隔离绿口径；若 Robolectric Compose 冒烟出现非 flake 失败，优先查是否依赖紫色默认——按 spec 这类失败属预期修正，改断言需在报告中列出）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/cn/appia/im/core/theme/Theme.kt app/src/test/kotlin/cn/appia/im/core/theme/AppiaColorSchemeTest.kt
git commit -m "feat(theme): M3 colorScheme 桥接 RN 色板去紫"
```

---

### Task 2: 图标基建（material-icons-core 依赖 + 六个 RN SVG 手工移植）

**Files:**
- Modify: `gradle/libs.versions.toml`（libraries 段加一行；bundles.compose 追加）
- Modify: `app/build.gradle.kts`（无——bundle 已被 :121 `implementation(libs.bundles.compose)` 引用，无需改此文件）
- Create: `app/src/main/res/drawable/ic_appia_mute.xml`、`ic_appia_send.xml`、`ic_appia_stop.xml`、`ic_appia_email.xml`、`ic_appia_compose.xml`、`ic_appia_right_arrow.xml`、`ic_appia_unread.xml`、`ic_appia_read.xml`、`ic_appia_star.xml`、`ic_appia_star_filled.xml`（后四个 T5 滑钮用，一并建齐）
- Test: `app/src/test/kotlin/cn/appia/im/core/theme/AppiaIconsSmokeTest.kt`（新建）

**Interfaces:**
- Produces: `R.drawable.ic_appia_mute / ic_appia_send / ic_appia_stop / ic_appia_email / ic_appia_compose / ic_appia_right_arrow / ic_appia_unread / ic_appia_read / ic_appia_star / ic_appia_star_filled`——T3-T7 以 `Icon(painterResource(R.drawable.ic_appia_*), tint = <色>, contentDescription = null, modifier = ...)` 消费；`Icons.Filled.Close / Add / MoreVert`、`Icons.Outlined.Search`、`Icons.AutoMirrored.Filled.ArrowBack`（icons-core 提供）
- 版本钉死原因：compose BOM 2026.06.01 已不再对齐 icons 工件（冻结于 1.7.8），必须显式 version；icons-core 仅依赖 ui-graphics，与 compose 1.11.4 二进制兼容（依赖树升级收敛已有先例：foundation 1.7.0 → 1.11.4）

- [ ] **Step 1: 加依赖**

`gradle/libs.versions.toml` libraries 段（compose 区块）加：

```toml
compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core", version = "1.7.8" }
```

`bundles.compose` 列表（`:92` 附近）追加 `"compose-material-icons-core"`：

```toml
compose = ["compose-ui", "compose-material3", "compose-foundation", "compose-material-icons-core", "navigation-compose", "hilt-navigation-compose"]
```

（先 `grep -n "bundles.compose\|implementation(libs.bundles.compose)" app/build.gradle.kts` 确认 app 模块经 bundle 引用 compose——是则无需改 build.gradle.kts；若各自单独 implementation，则在 app/build.gradle.kts dependencies 加 `implementation(libs.compose.material.icons.core)`。）

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（新依赖解析自 google() 仓，已核实可达且含 1.7.8）

- [ ] **Step 2: 写六个 drawable（RN SVG path 逐值转录，禁改坐标）**

`ic_appia_mute.xml`（RN `src/components/icons/MuteIcon.tsx`，IcoMoon notification-disabled，viewBox 1024，fill）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <path
        android:fillColor="#FF000000"
        android:pathData="M781.837 402.634c0-5.245-0.154-10.454-0.461-15.622l-63.539 63.539v265.363h-265.363l-66.979 66.979h425.363c12.944 0 24.611-7.795 29.565-19.754 4.954-11.955 2.214-25.722-6.938-34.874l-51.648-51.648v-273.984zM704.765 217.373l-45.254 45.255c-35.638-35.351-84.704-57.189-138.867-57.189-108.909 0-197.194 88.287-197.194 197.195v196.054l-64.001 64v-260.054c0-144.254 116.941-261.195 261.194-261.195 71.837 0 136.899 29.001 184.122 75.934zM433.578 808.934c0 48.086 38.982 87.066 87.066 87.066s87.066-38.979 87.066-87.066h-174.131zM854.275 190.982c-11.334-11.334-29.709-11.334-41.043 0l-612.732 612.733c-11.333 11.331-11.333 29.709 0 41.040 11.334 11.334 29.709 11.334 41.043 0l612.732-612.731c11.334-11.333 11.334-29.709 0-41.043z"/>
</vector>
```

`ic_appia_send.xml`（RN SendIcon.tsx，viewBox 20，fill）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="20" android:viewportHeight="20">
    <path
        android:fillColor="#FF000000"
        android:pathData="M3.4 2.6L18 10L3.4 17.4L5.4 10.8L12.6 10L5.4 9.2L3.4 2.6Z"/>
</vector>
```

`ic_appia_stop.xml`（RN StopIcon.tsx，20 盒内 12×12 圆角方 rx2，fill）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="20" android:viewportHeight="20">
    <path
        android:fillColor="#FF000000"
        android:pathData="M6,4 h8 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 h-8 a2,2 0 0 1 -2,-2 v-8 a2,2 0 0 1 2,-2 z"/>
</vector>
```

`ic_appia_email.xml`（RN EmailIcon.tsx，viewBox 24，双 stroke 元素）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <!-- RN Rect x3 y5 w18 h14 rx2 stroke1.5 -->
    <path
        android:strokeColor="#FF000000" android:strokeWidth="1.5"
        android:pathData="M3,7 A2,2 0 0 1 5,5 H19 A2,2 0 0 1 21,7 V17 A2,2 0 0 1 19,19 H5 A2,2 0 0 1 3,17 Z"/>
    <path
        android:strokeColor="#FF000000" android:strokeWidth="1.5"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M4,7 L12,13 L20,7"/>
</vector>
```

`ic_appia_compose.xml`（RN ComposeIcon.tsx，viewBox 24，笔+横线 stroke；实现时打开 RN 源文件核对第二 path 的完整属性）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:strokeColor="#FF000000" android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:pathData="M15.2 4.2L19.8 8.8L8.2 20.4H4V16.8L15.2 4.2Z"/>
    <path
        android:strokeColor="#FF000000" android:strokeWidth="1.5"
        android:strokeLineCap="round"
        android:pathData="M13.4 5.9L18 10.5"/>
</vector>
```

`ic_appia_right_arrow.xml`（RN RightArrowIcon.tsx，viewBox 20，fill）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="20" android:viewportHeight="20">
    <path
        android:fillColor="#FF000000"
        android:pathData="M8.0755 15.574L6.92645 14.425L11.3519 9.99951L6.92645 5.57404L8.0755 4.42499L13.65 9.99951L8.0755 15.574Z"/>
</vector>
```

（fillColor 全部 `#FF000000` 占位：运行时 `Icon(tint=)` 统一着色。）

`ic_appia_unread.xml`（RN UnreadIcon.tsx，viewBox 32，双 path fill——信封+右上圆点）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="32" android:viewportHeight="32">
    <path
        android:fillColor="#FF000000"
        android:pathData="M28 9.334c1.061 0 2.078 -0.421 2.828 -1.172 0.75 -0.75 1.172 -1.767 1.172 -2.828 0 -1.061 -0.421 -2.078 -1.172 -2.828 -0.75 -0.75 -1.767 -1.172 -2.828 -1.172 -1.061 0 -2.078 0.421 -2.828 1.172 -0.75 0.75 -1.172 1.767 -1.172 2.828 0 1.061 0.421 2.078 1.172 2.828 0.75 0.75 1.767 1.172 2.828 1.172z"/>
    <path
        android:fillColor="#FF000000"
        android:pathData="M29.333 26.667v-14.8c-0.88 0.179 -1.787 0.179 -2.667 0v13.467H5.333V9.636L16 19.117l8.879 -7.892c-0.803 -0.426 -1.511 -1.012 -2.08 -1.719L16 15.549 6.007 6.667h15.46c-0.179 -0.88 -0.179 -1.787 0 -2.667H4c-0.354 0 -0.693 0.14 -0.943 0.39 -0.25 0.25 -0.39 0.59 -0.39 0.944v20.333c0 0.354 0.14 0.693 0.39 0.943 0.25 0.25 0.59 0.39 0.943 0.39h24c0.354 0 0.693 -0.14 0.943 -0.39 0.25 -0.25 0.39 -0.59 0.39 -0.943z"/>
</vector>
```

`ic_appia_read.xml`（RN ReadIcon.tsx，viewBox 27，单 path fill——已读信封）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="27" android:viewportHeight="27">
    <path
        android:fillColor="#FF000000"
        android:pathData="M26.393 8.328L14.243 0.227c-0.222 -0.148 -0.483 -0.227 -0.749 -0.227 -0.267 0 -0.528 0.079 -0.75 0.227L0.594 8.328c-0.183 0.124 -0.333 0.291 -0.437 0.486 -0.104 0.195 -0.158 0.413 -0.157 0.634v16.202c0 0.358 0.142 0.701 0.395 0.955 0.253 0.253 0.597 0.395 0.955 0.395h24.3c0.358 0 0.701 -0.142 0.955 -0.395 0.253 -0.253 0.395 -0.597 0.395 -0.955V9.448c-0.001 -0.222 -0.057 -0.441 -0.163 -0.636 -0.106 -0.196 -0.259 -0.362 -0.444 -0.484zM13.5 2.968l9.558 6.373 -9.558 5.319 -9.558 -5.319 9.558 -6.373zM24.3 24.3H2.7V11.744l10.139 5.63c0.205 0.118 0.438 0.181 0.675 0.181 0.237 0 0.47 -0.062 0.675 -0.181L24.3 11.744V24.3z"/>
</vector>
```

`ic_appia_star.xml`（RN StarIcon.tsx 非 filled 分支，viewBox 24，stroke 1.5）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="22dp" android:height="22dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:strokeColor="#FF000000" android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:pathData="M11.48 3.499a0.562 0.562 0 011.04 0l2.125 5.111a0.563 0.563 0 00.475 0.345l5.518 0.442c0.499 0.04 0.701 0.663 0.321 0.988l-4.204 3.602a0.563 0.563 0 00-0.182 0.557l1.285 5.385a0.562 0.562 0 01-0.84 0.61l-4.725 -2.885a0.563 0.563 0 00-0.586 0l-4.725 2.885a0.562 0.562 0 01-0.84 -0.61l1.285 -5.385a0.563 0.563 0 00-0.182 -0.557l-4.204 -3.602a0.562 0.562 0 010.321 -0.988l5.518 -0.442a0.563 0.563 0 00.475 -0.345L11.48 3.5z"/>
</vector>
```

`ic_appia_star_filled.xml`（RN StarIcon.tsx filled 分支，fillRule evenodd）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="22dp" android:height="22dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:fillType="evenOdd"
        android:pathData="M10.788 3.21c0.448 -1.077 1.976 -1.077 2.424 0l2.082 5.007 5.404 0.433c1.164 0.093 1.636 1.545 0.749 2.305l-4.084 3.947 1.215 5.306c0.276 1.215 -1.064 2.063 -1.963 1.725L12 19.295l-4.21 2.215c-0.9 0.38 -2.24 -0.51 -1.964 -1.725l1.216 -5.306 -4.084 -3.947c-0.887 -0.76 -0.415 -2.212 0.749 -2.305l5.404 -0.433 2.082 -5.006z"/>
</vector>
```

**六个核心 RN SVG path 已在写计划时逐值核对过**（MuteIcon.tsx 1024 盒 / SendIcon.tsx 20 盒 / StopIcon.tsx 20 盒 rx2 / EmailIcon.tsx 24 盒 stroke1.5 / ComposeIcon.tsx 24 盒 / RightArrowIcon.tsx 20 盒）；实现时打开各文件再核一遍 pathData 与 stroke 属性（尤其 ComposeIcon 第二 path 的 strokeLineCap），转录错误会被 T2 冒烟测试兜住（pathData 语法错误抛异常）。

- [ ] **Step 3: 写渲染冒烟测试**

```kotlin
package cn.appia.im.core.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import cn.appia.im.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M12-T2：十个手工移植 drawable 可加载可渲染（pathData 语法错在此暴露）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppiaIconsSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `ten hand-ported icons render`() {
        rule.setContent {
            Row {
                Icon(painterResource(R.drawable.ic_appia_mute), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_send), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_stop), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_email), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_compose), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_right_arrow), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_unread), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_read), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_star), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_star_filled), contentDescription = null)
            }
        }
        rule.waitForIdle() // pathData 非法在此抛异常
    }
}
```

- [ ] **Step 4: 跑测试 + 编译**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.theme.AppiaIconsSmokeTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add gradle/libs.versions.toml app/src/main/res/drawable/ic_appia_*.xml app/src/test/kotlin/cn/appia/im/core/theme/AppiaIconsSmokeTest.kt
git commit -m "feat(ui): 引入 material-icons-core 与十个 RN SVG 手工图标"
```

---

### Task 3: 共享顶栏返回箭头（RoomHeader + SettingsHeader）

**Files:**
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/ui/RoomHeader.kt:62-75`（navigationIcon 的 `←` Text）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/settings/ui/SettingsRow.kt:97-107`（SettingsHeader 的 `←` Text）

**Interfaces:**
- Consumes: `Icons.AutoMirrored.Filled.ArrowBack`（T2 icons-core）
- Produces: 26 个屏（mail/roominfo/search/contacts/todo/meeting/voice/labor/forward…）的返回箭头一次性换成原生箭头图标（两文件是共享组件）；testTag `qa-room-header-back` / `qa-settings-header-back` 不变

- [ ] **Step 1: RoomHeader 替换**

`navigationIcon` 槽内 `Text("←", ...)` 整块换成（保持 modifier 链与 testTag 完全一致）：

```kotlin
Icon(
    Icons.AutoMirrored.Filled.ArrowBack,
    contentDescription = null,
    tint = colors.headerTintColor,
    modifier = Modifier
        .padding(start = 12.dp)
        .size(40.dp)
        .wrapContentSize(Alignment.Center)
        .clickable(onClick = onBack)
        .testTag("qa-room-header-back"),
)
```

imports 加：`androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.automirrored.filled.ArrowBack`、`androidx.compose.material3.Icon`。删除注释「无 icons 依赖（material3 未带 icons-core 扩展）：文本箭头足够…」（已过时）。

- [ ] **Step 2: SettingsHeader 同款替换**

SettingsRow.kt:97-107 的 `Text("←", ...)` 同样换成 Icon（`qa-settings-header-back` 或原 tag 参数保持）。

- [ ] **Step 3: 编译 + 受影响冒烟**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest --tests "cn.appia.im.feature.settings.ui.SettingsScreensSmokeTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/cn/appia/im/feature/chat/ui/RoomHeader.kt app/src/main/kotlin/cn/appia/im/feature/settings/ui/SettingsRow.kt
git commit -m "fix(ui): 共享顶栏返回箭头换原生图标"
```

---

### Task 4: 聊天域（RoomScreen + 附件 + 弹层 + 预览器）

**Files:**
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/ui/RoomScreen.kt`（:727 ⌕、:944 ✕、:984 ✕、:1108 ⏹/…、:1126 ↑）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/AttachmentSelector.kt:117-128`（`+`）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/ui/TableKatexOverlays.kt:70-80、:203-213`（✕ ×2）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/ui/MessageAttachments.kt:381-401`（↻ 徽标改文字 pill）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chat/ui/AttachmentViewer.kt:173`、`DocPreview.kt:131`、`VideoPlayer.kt:73`（`←`）

**Interfaces:**
- Consumes: `R.drawable.ic_appia_send / ic_appia_stop`（T2）；`Icons.Outlined.Search`、`Icons.Filled.Close`、`Icons.Filled.Add`、`Icons.AutoMirrored.Filled.ArrowBack`（icons-core）
- Produces: 无（叶子改动）；`▶` 字形保留（裁定表）

统一替换模式（所有 `✕`/`←` 同形，保 modifier 链 + testTag）：

```kotlin
// 原：Text("✕", color = colors.auxiliaryText, fontSize = 16.sp, modifier = …)
Icon(
    Icons.Filled.Close,
    contentDescription = null,
    tint = colors.auxiliaryText,
    modifier = <原 modifier 链不动>,
)
```

- [ ] **Step 1: RoomScreen 六处替换**

| 位置 | 原字形 | 换成 | 备注 |
|---|---|---|---|
| :727 | `⌕` 20sp in 36dp box，tag `qa-room-header-search` | `Icon(Icons.Outlined.Search, tint = colors.headerTintColor, modifier = Modifier.size(22.dp))`，外包原 36dp wrapContentSize/clickable/testTag 链 | RN SearchIcon 22dp |
| :944 | `✕`，tag `qa-reply-close` | Close 16dp（tint colors.auxiliaryText，原 padding(8) clickable 链不动） | |
| :984 | `✕`，tag `qa-edit-close` | 同上 | |
| :1102-1113 | `⏹` 18sp in 40dp box，tag `qa-room-ai-stop` | `Icon(painterResource(R.drawable.ic_appia_stop), tint = colors.tintColor, modifier = Modifier.size(18.dp))` | RN StopIcon 18 |
| :1108 | stopping 态 `"…"` | `CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.tintColor)` | RN ActivityIndicator small |
| :1126 | `↑` 22sp in 40dp box，tag `qa-room-send` | `Icon(painterResource(R.drawable.ic_appia_send), tint = <原三态色逻辑不动>, modifier = Modifier.size(20.dp))` | RN SendIcon 18 in 36 钮 |

- [ ] **Step 2: AttachmentSelector `+` 替换**

:117 `Text("+", color = colors.tintColor, fontSize = 22.sp, …)` → `Icon(Icons.Filled.Add, tint = colors.tintColor, modifier = Modifier.size(24.dp))`，外链（padding end 8 / 40dp box / clickable / `qa-room-attachment`）不动。RN 是 material `plus` 24（ChatInputBar Icon name="plus" size 24）。

- [ ] **Step 3: TableKatexOverlays ✕ ×2**

:75（`qa-markdown-table-overlay-close`）与 :208（`qa-katex-overlay-close`）按统一模式换 Close 16dp。

- [ ] **Step 4: MessageAttachments 重试徽标改文字 pill（RN 形态）**

:381-401 的 `Box { content(); Text("↻", …18dp 红圆…) }` 改为（对齐 RN LocalAttachmentPreviewList:103-121 + styles:123-135）：

```kotlin
Box {
    content()
    Text(
        t("message_retryattachment").replace("{{name}}", item.name.orEmpty()),
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFF4D4F))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clickable(enabled = !retryRequested) { /* 原 onClick 体不动 */ }
            .testTag("qa-local-attachment-retry-${item.id}"),
    )
}
```

（`item.name` 已核实为非空 String——MessageAttachments.kt:367 `Text(item.name)` 直接消费；`message_retryattachment` 键已在 strings.xml:725 / values-zh:725。原 `testTag("qa-local-attachment-retry-…")` 保留在 Text 上。）

- [ ] **Step 5: 三个预览器 `←`**

AttachmentViewer:173 / DocPreview:131 / VideoPlayer:73 按 T3 模式换 `Icons.AutoMirrored.Filled.ArrowBack`（24dp，tint 用原 color）。

- [ ] **Step 6: 编译 + 相关冒烟 + 字形自查**

```bash
./gradlew :app:compileDebugKotlin
grep -n '"⌕"\|"✕"\|"←"\|"↑"\|"⏹"\|"↻"' app/src/main/kotlin/cn/appia/im/feature/chat/ui/RoomScreen.kt app/src/main/kotlin/cn/appia/im/feature/chat/ui/TableKatexOverlays.kt app/src/main/kotlin/cn/appia/im/feature/chat/AttachmentSelector.kt app/src/main/kotlin/cn/appia/im/feature/chat/ui/MessageAttachments.kt
```

Expected: 编译过；grep 零命中（MessageAttachments 的 `▶` ×2 保留不在本 grep 集）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/cn/appia/im/feature/chat/
git commit -m "fix(chat): 聊天域图标替换与重试徽标对齐 RN"
```

---

### Task 5: 会话列表域（顶栏头像 / 分区头 / 置顶底色 / 静音图标）

**Files:**
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chatlist/ui/ChatListScreen.kt`（顶栏 :191-229、分区头 :338-349、折叠态）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chatlist/ChatRow.kt`（MuteGlyph :277-292 删除、★ :174-176 删除、置顶底色 :138-144）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chatlist/ChatRowActions.kt`（SwipeActionButton 加图标，:249-279）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/chatlist/ui/MailChannelRow.kt`（✉ :74-75、★ :77-79、置顶底色）

**Interfaces:**
- Consumes: `R.drawable.ic_appia_mute / ic_appia_email`（T2）；`Icons.Filled.MoreVert`（icons-core）；`presenceBadge(userId, username, fallbackStatus, avatarSize)`（`feature/chat/ui/PresenceBadge.kt:43` 既有）；头像 URL 构造模式抄 `ProfileScreen.kt:60-67`
- Produces: 无

- [ ] **Step 1: 顶栏加头像（RN RoomListProfileHeader parity）**

`Row`（:191 头部）内、姓名 Text 之前插入（RN：AVATAR_SIZE 40，avatarWrap marginRight 10，name 18/700）：

```kotlin
val user = session?.user
val headerUsername = user?.username?.trim().orEmpty()
val headerName = user?.name?.trim().orEmpty()
val headerDisplay = headerName.ifEmpty { headerUsername }
val density = LocalDensity.current  // 既有则复用
val headerAvatarUrl = if (serverUrl.isBlank() || headerUsername.isBlank()) null else buildString {
    append(serverUrl.trimEnd('/')).append("/avatar/").append(headerUsername)
    append("?version=1&format=png&size=").append(with(density) { 40.dp.roundToPx() })
    if (!user?.id.isNullOrEmpty() && !session?.token.isNullOrEmpty()) {
        append("&rc_token=").append(session?.token).append("&rc_uid=").append(user!!.id)
    }
}
// Row 内首个元素：
Box(Modifier.size(40.dp)) {
    Box(
        Modifier.matchParentSize().clip(CircleShape).background(colors.chatComponentBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(headerDisplay.take(1).uppercase().ifEmpty { "?" }, color = colors.auxiliaryText, fontSize = 16.sp)
        AsyncImage(model = headerAvatarUrl, contentDescription = null, modifier = Modifier.matchParentSize())
    }
    presenceBadge(
        userId = user?.id,
        username = headerUsername.takeIf { it.isNotEmpty() },
        fallbackStatus = null,
        avatarSize = 40.dp,
    )()
}
Spacer(Modifier.width(10.dp))
```

姓名 Text 加 `color = colors.titleText`（RN styles.name #111）。imports：`CircleShape`、`AsyncImage`（coil3）、`width`。

- [ ] **Step 2: `⋮` 换 MoreVert**

:221 `Text("⋮", …)` → `Icon(Icons.Filled.MoreVert, tint = colors.headerTintColor, modifier = <原 40dp wrapContentSize/clickable/`qa-room-list-menu` 链不动>)`。注意 DropdownMenu 的锚点 Box 结构不变。

- [ ] **Step 3: 分区头 RN parity（底色 + ▼ + 折叠）**

读 `ChatListSectioner.kt` 确认分段键类型与 assistant 段处理。**已核实**：枚举 `ChatListSectionKey { ASSISTANT, TODO, CHANNELS }`（ChatListSectioner.kt:16-20），AA 现状对 ASSISTANT 段也无条件渲染段头；RN `RoomListSectionedChatList.tsx:43-45` **assistant 段无头**（`if (section.key !== 'assistant')` 才 push 段头）。替换 :338-349 的头渲染：

```kotlin
// LazyColumn 之前：var collapsedSections by remember { mutableStateOf(setOf<String>()) }
sections.forEach { section ->
    val sectionId = section.key.name.lowercase()
    if (section.key != ChatListSectionKey.ASSISTANT) { // RN :43-45 assistant 段无头
        item(key = "header-${section.key}", contentType = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F6F8)) // RN sectionHeader bg
                    .clickable {
                        collapsedSections =
                            if (sectionId in collapsedSections) collapsedSections - sectionId else collapsedSections + sectionId
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("qa-room-list-section-${sectionId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (sectionId in collapsedSections) "▶" else "▼", // RN chevronGlyph ▼ 10sp #666
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    modifier = Modifier.size(22.dp).wrapContentSize(Alignment.Center), // RN chevronBox 22
                )
                Text(
                    context.t(section.key.titleKey),
                    color = Color(0xFF666666), // RN sectionTitle #666
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, // RN 500
                )
            }
        }
    }
    if (sectionId !in collapsedSections) {
        items(section.chats, key = { it._id }, contentType = { "chat" }) { chat -> … } // 原体不动
    }
}
```

（折叠箭头方向：RN 是 ▼ 不旋转、仅淡出动画——AA 用 ▼/▶ 二态文字等义，属「不做动效」约束内的形态。）

- [ ] **Step 4: ChatRow 置顶底色 + 去星标 + 静音图标**

- :138-144 Row 加底色：`.background(if (chat.f) Color(0xFFEEEEEE) else Color.Transparent)`（RN shadowBackGround rgb(238,238,238)）
- :174-176 `if (chat.f) { Text("★"…) }` 整块删除
- MuteGlyph（:277-292）删除，:235-239 调用点换：

```kotlin
Icon(
    painterResource(R.drawable.ic_appia_mute),
    contentDescription = null,
    tint = if (unread > 0) MuteUnreadBlue else MuteGray,
    modifier = Modifier.padding(start = 5.dp, end = 6.dp).size(20.dp), // RN MuteIcon 默认 20
)
```

imports 清理：`Canvas`/`StrokeCap`/`Stroke` 若无他用即删；加 `Icon`/`painterResource`/`R`。

- [ ] **Step 4b: SwipeActionButton 加图标（RN RectButton 内容形态）**

`SwipeActionButton` 加 `icon: @Composable () -> Unit` 参数（默认 `{}`），Column 化对齐 RN（图标在上、标签在下，RN `actionBtn` justifyContent/alignItems center + `actionLabel` marginTop 4、fontSize 14、fontWeight 600、white）：

```kotlin
private fun SwipeActionButton(
    label: String,
    background: Color,
    contentReady: Boolean,
    onClick: () -> Unit,
    tag: String,
    icon: @Composable () -> Unit = {},
) {
    Box(
        Modifier
            .width(ACTION_WIDTH)
            .fillMaxHeight()
            .background(background)
            .clickable(enabled = contentReady, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (contentReady) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                icon()
                Text(
                    label,
                    color = Color.White,
                    fontSize = 14.sp,      // RN actionLabel 14/600（原 13sp 改 14）
                    fontWeight = FontWeight.W600,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp), // RN marginTop 4
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```

三处调用点分别传 icon（RN 尺寸：UnreadIcon 20 白 / StarIcon 22（filled=chat.f，色 buttonText）/ ReadIcon 24 白；star 口径：filled 填充 path fill，非 filled stroke 1.5——两个 drawable 都建）：

```kotlin
// 标未读钮（qa-swipe-mark-unread）
icon = { Icon(painterResource(R.drawable.ic_appia_unread), tint = Color.White, contentDescription = null, modifier = Modifier.size(20.dp)) }
// 收藏钮（qa-swipe-favorite）——background=colors.favoriteBackground，色取 RN buttonText（白）
icon = { Icon(painterResource(if (chat.f) R.drawable.ic_appia_star_filled else R.drawable.ic_appia_star), tint = Color.White, contentDescription = null, modifier = Modifier.size(22.dp)) }
// 标已读钮（qa-swipe-mark-read）
icon = { Icon(painterResource(R.drawable.ic_appia_read), tint = Color.White, contentDescription = null, modifier = Modifier.size(24.dp)) }
```

**T2 需追加四个 drawable**（本任务实现前先补，归属 T2 产物）：`ic_appia_unread`（RN UnreadIcon.tsx，viewBox 32，双 path fill）、`ic_appia_read`（RN ReadIcon.tsx，viewBox 27，单 path fill）、`ic_appia_star`（StarIcon.tsx 非 filled 分支，viewBox 24，stroke 1.5）、`ic_appia_star_filled`（filled 分支，fillRule evenodd）。pathData 从 RN 源逐值转录（已核：UnreadIcon 两段 d 串、ReadIcon 单段 d 串、StarIcon 两分支），随 T2 一并冒烟。

- [ ] **Step 5: MailChannelRow 同款（Email + 置顶底色 + 去星标）**

- :74-75 `Text("✉", …)` → `Icon(painterResource(R.drawable.ic_appia_email), tint = Color.White, modifier = Modifier.size(24.dp))`（RN EmailIcon 24 白）
- :77-79 `★` 块删除；Row（:58 附近）加 `.background(if (chat.f) Color(0xFFEEEEEE) else Color.Transparent)`

- [ ] **Step 6: 编译 + 冒烟**

```bash
./gradlew :app:testDebugUnitTest --tests "cn.appia.im.feature.chatlist.*"
```

Expected: PASS（ChatRowSmokeTest / ChatListScreenTest / MailChannelRowSmokeTest——Step 4b 改 SwipeActionButton 形态；先 grep 确认测试未引用 `★`/`✉`/`↻` 字形文本，若断言到被删元素需同步改断言并在报告列出）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/cn/appia/im/feature/chatlist/ app/src/main/res/drawable/ic_appia_*.xml
git commit -m "fix(chatlist): 顶栏头像分区头滑钮图标对齐 RN"
```

---

### Task 6: 邮件域 + 资料页

**Files:**
- Modify: `app/src/main/kotlin/cn/appia/im/feature/mail/ui/MailChannelScreen.kt:136-161`（✎/⌕）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/mail/ui/MailAccountSetupScreen.kt:162`（✉）
- Modify: `app/src/main/kotlin/cn/appia/im/feature/settings/ui/ProfileScreen.kt:174`（`›`）

**Interfaces:**
- Consumes: `R.drawable.ic_appia_compose / ic_appia_email / ic_appia_right_arrow`（T2）；`Icons.Outlined.Search`
- Produces: 无

- [ ] **Step 1: MailChannelScreen 头部两钮**

:136-146 `Text("✎", …)` → `Icon(painterResource(R.drawable.ic_appia_compose), tint = Colors.textPrimary, modifier = Modifier.size(24.dp))`，外链（end 12 / 32dp box / clickable / `mail-compose-toggle`）不动。
:148-161 `Text("⌕", …)` → `Icon(Icons.Outlined.Search, tint = if (state.searchVisible) Colors.primary else Colors.textPrimary, modifier = Modifier.size(20.dp))`，外链同上（`mail-search-toggle`）。

- [ ] **Step 2: MailAccountSetupScreen ✉**

:162 → `Icon(painterResource(R.drawable.ic_appia_email), tint = Colors.primary, modifier = Modifier.size(22.dp))`，原 modifier/testTag 不动。

- [ ] **Step 3: ProfileScreen 导航箭头**

:174 `Text("›", color = LocalAppiaColors.current.auxiliaryText, fontSize = 18.sp)` → `Icon(painterResource(R.drawable.ic_appia_right_arrow), tint = Color(0xFF86909C), modifier = Modifier.padding(start = 4.dp).size(20.dp))`（RN ProfileScreen:74/108 RightArrowIcon #86909C 20，chevron marginLeft 4——RN chevron 色 #c7c7cc 仅用于 SettingsRow 族，Profile 用 #86909C）。

- [ ] **Step 4: 编译 + 冒烟 + 提交**

```bash
./gradlew :app:testDebugUnitTest --tests "cn.appia.im.feature.mail.*" --tests "cn.appia.im.feature.settings.ui.*"
git add app/src/main/kotlin/cn/appia/im/feature/mail/ui/ app/src/main/kotlin/cn/appia/im/feature/settings/ui/ProfileScreen.kt
git commit -m "fix(mail): 邮件域图标与资料页箭头对齐 RN"
```

---

### Task 7: 全仓字形清扫（语音/搜索/工作台/联系人/登录域非品牌件）

**Files:**
- Modify: `feature/voice/ui/VoiceIncomingBanner.kt:117`、`VoiceOtherCallBanner.kt:106`、`VoiceParticipantCenter.kt:155`（✕）
- Modify: `feature/contacts/ui/CreateChannelMembersScreen.kt:835`（✕）
- Modify: `feature/chat/ui/ReactionBar.kt:203`（＋）
- Modify: `feature/search/ui/RoomSearchScreen.kt:135`、`GlobalSearchScreen.kt:133`（←；`:536 ▶`、`:511/:545 ›` 保留）
- Modify: `feature/contacts/ui/TeamScreen.kt:201`（←；:565 › 保留）
- Modify: `feature/labor/ui/LaborScreen.kt:176`（⌕ leadingIcon）
- Modify: `feature/login/ui/AuthWebScreen.kt:60`、`AreaCodeScreen.kt:65`（←，非品牌件）

**Interfaces:**
- Consumes: `Icons.Filled.Close`、`Icons.Filled.Add`、`Icons.AutoMirrored.Filled.ArrowBack`、`Icons.Outlined.Search`
- Produces: 无；保留清单见计划头裁定表（`›`/`▾`/`▸`/`⌄`/`▶`/ChatListScreen:210 `⌕`/LoginScreen:824 `▾` 不动）

- [ ] **Step 1: 逐文件替换（统一模式同 T3/T4：保 modifier 链 + testTag，Text→Icon，tint 取原 color）**

- 三个 voice `✕` + CreateChannelMembers `✕` → `Icons.Filled.Close`（尺寸取原字形 fontSize 等值 dp）
- ReactionBar `＋`（:203，tag `qa-reaction-add`）→ `Icon(Icons.Filled.Add, tint = colors.tintColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).size(14.dp))`——**RN 无此组件**（已核实：RN 仓 grep Reaction/ReactionBar 零命中，RN 只有 legacy AddReaction 注释态），`＋` 是 AA 自有控件（ReactionBar.kt:161 注释自证），按混合方案用 Material Add 对齐表态 chip 的视觉重量
- RoomSearch/GlobalSearch/TeamScreen `←` → `Icons.AutoMirrored.Filled.ArrowBack`
- LaborScreen `Text("⌕", color = Muted, fontSize = 16.sp)` → `Icon(Icons.Outlined.Search, tint = Muted, modifier = Modifier.size(16.dp))`（TextField leadingIcon 槽内）
- AuthWebScreen `TextButton(onClick = onBack) { Text("←") }` → `TextButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }`；AreaCodeScreen 同款

- [ ] **Step 2: 编译 + 全仓字形 grep 兜底**

```bash
./gradlew :app:compileDebugKotlin
grep -rn '"⌕"\|"✕"\|"✎"\|"←"\|"↑"\|"↻"\|"＋"\|"★"\|"⋮"\|"✉"\|"⏹"' app/src/main/kotlin --include='*.kt' | grep -v '^\s*//\|^\s*\*\|ShortnameToUnicode'
```

Expected: 仅剩 `ChatListScreen.kt:210 "⌕"`（RN 同字形保留项），其余零命中。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/cn/appia/im/feature/
git commit -m "fix(ui): 全仓字形图标清扫（语音搜索工作台联系人登录）"
```

---

### Task 8: 验收收尾

**Files:**
- Modify: `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（里程碑表加 M12 行）
- Modify: `README.md`（M12 一节，两三行）
- Create: `docs/m12-ui-acceptance.md`（验收记录：截图清单 + grep 结果 + 测试结论）

**Interfaces:**
- Consumes: T1-T7 全部产出
- Produces: 验收记录文档 + push 到 origin/main

- [ ] **Step 1: 全量测试 + release 构建验证**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL ×2（flake 三件套隔离绿口径）

- [ ] **Step 2: 装机走查 + 截图（模拟器可达面）**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop cn.appia.im.debug && adb shell monkey -p cn.appia.im.debug 1
# 企业码页（可达）；长按 label 开 envHost 后门截一张（debug 特有）
adb exec-out screencap -p > /tmp/m12-enterprise.png
```

登录态界面（会话列表/聊天/设置/邮件）模拟器无凭证不可达（M11-T7 已记边界）——**截图边界如实记入验收文档**，登录态视觉由用户真机确认（验收文档列「用户确认清单」五屏）。

- [ ] **Step 3: Maestro 冒烟**

```bash
maestro test maestro/launch_android.yaml
maestro test maestro/login_reach_login.yaml
```

Expected: 两流过（testTag 零变化的设计验证）

- [ ] **Step 4: 写验收文档 `docs/m12-ui-acceptance.md`**

内容：字形清扫 grep 终态（含保留清单及理由）、全测试结果、Maestro 结果、截图路径、用户真机确认清单（会话列表/聊天页/资料设置/邮件/任一弹层）、登录域未动声明。

- [ ] **Step 5: 文档更新 + 提交 + push**

REWRITE_MASTER_PLAN.md 里程碑表加：

```markdown
| M12 | UI 优化 | M3 主题桥接（去紫）、图标混合替换（icons-core + 6 个 RN SVG 移植）、四域 RN 对齐 | 全测试绿 + 字形清扫达预期 |
```

README.md 加 M12 节（对齐 M11 节格式，两三行）。然后：

```bash
git add docs/m12-ui-acceptance.md docs/superpowers/plans/REWRITE_MASTER_PLAN.md README.md
git commit -m "docs(m12): UI 优化验收记录与文档收尾"
git push
```
