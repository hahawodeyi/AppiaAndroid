package cn.appia.im.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前主题色板，对应 RN 的 `useTheme().colors`。 */
val LocalAppiaColors = staticCompositionLocalOf { AppiaColors.light }

/** RN 主题的 Compose 入口：`isDark` 映射 colors.ts 的 light/dark。 */
@Composable
fun AppiaTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (isDark) AppiaColors.dark else AppiaColors.light
    CompositionLocalProvider(LocalAppiaColors provides colors) {
        content()
    }
}
