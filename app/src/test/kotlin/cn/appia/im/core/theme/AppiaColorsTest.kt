package cn.appia.im.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class AppiaColorsTest {

    // colors.ts 字面量大小写不统一（'#2878FF' 与 '#ffffff' 并存），Compose Color 只保留数值，
    // 故 hexValue 统一小写输出（剥离 alpha 通道），比对时忽略大小写。
    private fun Color.hexValue(): String = "#%06x".format(Locale.ROOT, toArgb() and 0xFFFFFF)

    private fun assertHex(expected: String, actual: Color) =
        assertEquals(expected.lowercase(Locale.ROOT), actual.hexValue())

    @Test
    fun `light palette matches RN colors ts`() {
        assertHex("#2878FF", AppiaColors.light.primary)
        assertHex("#ffffff", AppiaColors.light.backgroundColor)
        assertHex("#0d0e12", AppiaColors.light.titleText)
        assertHex("#f5455c", AppiaColors.light.dangerColor)
        assertHex("#2de0a5", AppiaColors.status.online) // STATUS_COLORS
    }

    @Test
    fun `dark palette has same field set as light`() {
        assertEquals(
            AppiaColors.light.javaClass.declaredFields.map { it.name }.toSet(),
            AppiaColors.dark.javaClass.declaredFields.map { it.name }.toSet(),
        )
    }

    @Test
    fun `palettes match RN field counts`() {
        // colors.ts 的 light/dark/black 各 68 个自有字段 + mentions 展开 5 个 = 73
        // （Compose 编译器会注入合成字段 $stable，不计入）
        assertEquals(73, AppiaColors.light.paletteFieldCount())
        assertEquals(73, AppiaColors.dark.paletteFieldCount())
        assertEquals(73, AppiaColors.black.paletteFieldCount())
    }

    // 排除编译器生成字段：Compose 的 $stable 与 Kotlin object 的静态 INSTANCE
    private fun Any.paletteFieldCount(): Int =
        javaClass.declaredFields.count { !it.name.startsWith("$") && it.name != "INSTANCE" }

    @Test
    fun `tokens Colors matches RN tokens ts`() {
        assertHex("#2878FF", Colors.primary)
        assertHex("#00B42A", Colors.success)
        assertHex("#F53F3F", Colors.danger)
        // tokens.ts 的 Colors 全量 15 个字段（不计 $stable 合成字段）
        assertEquals(15, Colors.paletteFieldCount())
    }
}
