package cn.appia.im.core.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * RN `src/theme/tokens.ts` 的间距/圆角/字号转录（RN 数值单位即 dp）。
 * 当前零消费者：M5 设置域决定采用或移除（M4 终审裁定），勿新增消费——留待 M5 统一裁决。
 */
class FontSize(
    val xs: Dp,
    val sm: Dp,
    val base: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
)

class Spacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
)

/** RN 键名 `2xl` 在 Kotlin 需反引号转义。 */
class Radius(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val `2xl`: Dp,
    val full: Dp,
)

object AppiaDimens {
    val fontSize = FontSize(
        xs = 11.dp,
        sm = 12.dp,
        base = 14.dp,
        md = 15.dp,
        lg = 17.dp,
        xl = 20.dp,
    )

    val spacing = Spacing(
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        lg = 16.dp,
        xl = 20.dp,
    )

    val radius = Radius(
        xs = 2.dp,
        sm = 4.dp,
        md = 6.dp,
        lg = 8.dp,
        xl = 10.dp,
        `2xl` = 12.dp,
        full = 9999.dp,
    )
}
