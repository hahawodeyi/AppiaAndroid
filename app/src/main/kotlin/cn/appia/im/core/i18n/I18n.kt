package cn.appia.im.core.i18n

import android.content.Context

/**
 * 文案查找：RN i18n 的 t() 对齐实现。
 * key 不区分大小写（资源名由迁移脚本统一小写），缺 key 回退显示 key 本身（与 RN 行为一致）。
 * Compose 环境经 CompositionLocal 取 Context；文案中的 {{x}} 插值占位符由调用方按 RN 语义替换。
 */
fun Context.t(key: String, vararg args: Any): String {
    val resId = resources.getIdentifier(key.lowercase(), "string", packageName)
    if (resId == 0) return key // 缺失回退：显示 key 本身（与 RN t() 行为一致）
    // 空参走非格式化路径：spread 空数组仍会进 String.format，裸 % 文案会抛 UnknownFormatConversionException
    if (args.isEmpty()) return resources.getString(resId)
    return getString(resId, *args)
}
