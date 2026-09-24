package cn.appia.im.feature.search.ui

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * 按搜索词切分文本（RN src/lib/chat/splitTextBySearchKeyword.ts 逐条移植）：
 * 大小写不敏感；关键词按字面匹配（等价 RN regex 转义）；空词/空文本单段返回。
 * 供全局搜索高亮渲染（HighlightText）与测试共用。
 */
data class SearchTextSegment(val text: String, val matched: Boolean)

fun splitTextBySearchKeyword(text: String, keyword: String): List<SearchTextSegment> {
    val q = keyword.trim()
    if (text.isEmpty() || q.isEmpty()) {
        return listOf(SearchTextSegment(text, false))
    }
    // JS `text.split(new RegExp(`(${escapeRegExp(q)})`, 'gi'))` + filter 空段：
    // 交替追加未匹配段与匹配段；匹配段 matched=true。
    // 注意：整串等于关键词时（JS 原始 split 长度 3）RN 仍高亮——按「是否出现匹配」判定而非段数。
    val segments = mutableListOf<SearchTextSegment>()
    var i = 0
    val lower = text.lowercase()
    val needle = q.lowercase()
    while (i < text.length) {
        val idx = lower.indexOf(needle, i)
        if (idx < 0) {
            segments += SearchTextSegment(text.substring(i), false)
            break
        }
        if (idx > i) segments += SearchTextSegment(text.substring(i, idx), false)
        segments += SearchTextSegment(text.substring(idx, idx + q.length), true)
        i = idx + q.length
    }
    if (segments.none { it.matched }) {
        return listOf(SearchTextSegment(text, false))
    }
    return segments
}

/** RN SearchHighlightText highlight 样式：#3677F2 半粗。 */
private val HighlightColor = androidx.compose.ui.graphics.Color(0xFF3677F2)

/**
 * 搜索高亮文本（RN components/SearchHighlightText）：
 * - 默认：整串 AnnotatedText 按段染色（matched 段高亮色半粗）。
 * - keepMatchVisible（单行）：首个匹配前的段头部省略（…前缀）、其后尾部省略——
 *   长文本中匹配词仍可见（对齐旧版全局搜索消息行 HighLightText）。
 */
@Composable
fun HighlightText(
    text: String,
    keyword: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    keepMatchVisible: Boolean = false,
) {
    val segments = splitTextBySearchKeyword(text, keyword)
    val firstMatchIndex = segments.indexOfFirst { it.matched }
    val shouldKeep = keepMatchVisible && maxLines == 1 && firstMatchIndex > 0

    if (shouldKeep) {
        // Row(IntrinsicSize.Min)：三段同高；前缀 weight+右对齐收缩、关键词不缩、后缀收缩
        Row(modifier.height(IntrinsicSize.Min)) {
            Text(
                segments.subList(0, firstMatchIndex).joinToString("") { it.text },
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis, // 头部省略（…显示在左侧）
                softWrap = false,
            )
            Text(
                segments[firstMatchIndex].text,
                color = HighlightColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                segments.subList(firstMatchIndex + 1, segments.size).joinToString("") { it.text },
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
        return
    }

    val colors = LocalAppiaColors.current
    val annotated = buildAnnotatedString {
        for (segment in segments) {
            if (segment.matched) {
                withStyle(SpanStyle(color = HighlightColor, fontWeight = FontWeight.SemiBold)) {
                    append(segment.text)
                }
            } else {
                withStyle(SpanStyle(color = colors.titleText)) { append(segment.text) }
            }
        }
    }
    Text(annotated, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

