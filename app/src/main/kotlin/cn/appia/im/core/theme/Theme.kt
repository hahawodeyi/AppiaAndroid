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
            error = p.dangerColor, onError = Color.White,
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
            error = p.dangerColor, onError = Color.White,
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
