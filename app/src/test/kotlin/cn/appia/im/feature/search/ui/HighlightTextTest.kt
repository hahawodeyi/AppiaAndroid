package cn.appia.im.feature.search.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * \u9ad8\u4eae\u5206\u6bb5\uff08RN splitTextBySearchKeyword.test \u9010\u6761\u5bf9\u9f50\uff09\uff1a
 * \u7a7a\u8bcd\u5355\u6bb5 / \u5927\u5c0f\u5199\u4e0d\u654f\u611f\u5207\u5206 / \u591a\u6b21\u51fa\u73b0\u5168\u9ad8\u4eae / \u7279\u6b8a\u5b57\u7b26\u6309\u5b57\u9762\u5339\u914d / \u6574\u4e32\u5373\u5173\u952e\u8bcd\u3002
 */
class HighlightTextTest {

    @Test
    fun `returns single segment when keyword is empty`() {
        assertEquals(
            listOf(SearchTextSegment("Appia", false)),
            splitTextBySearchKeyword("Appia", "   "),
        )
    }

    @Test
    fun `returns single segment when text is empty`() {
        assertEquals(
            listOf(SearchTextSegment("", false)),
            splitTextBySearchKeyword("", "kw"),
        )
    }

    @Test
    fun `splits matched segments case-insensitively`() {
        assertEquals(
            listOf(
                SearchTextSegment("App", true),
                SearchTextSegment("ia Design", false),
            ),
            splitTextBySearchKeyword("Appia Design", "app"),
        )
    }

    @Test
    fun `highlights multiple occurrences`() {
        assertEquals(
            listOf(
                SearchTextSegment("aa", true),
                SearchTextSegment("AA", true),
            ),
            splitTextBySearchKeyword("aaAA", "aa"),
        )
    }

    @Test
    fun `literal-matches regex special characters in keyword`() {
        // RN escapeRegExp\uff1aa.b \u6309\u5b57\u9762\u547d\u4e2d\uff0c\u4e0d\u628a '.' \u5f53\u4efb\u610f\u5b57\u7b26
        assertEquals(
            listOf(SearchTextSegment("a.b", true)),
            splitTextBySearchKeyword("a.b", "a.b"),
        )
        // \u5b57\u9762\u4e0d\u547d\u4e2d\uff1aaxb \u4e0d\u88ab a.b \u547d\u4e2d\uff08regex \u8bed\u4e49\u624d\u4f1a\u547d\u4e2d\uff09
        assertEquals(
            listOf(SearchTextSegment("axb", false)),
            splitTextBySearchKeyword("axb", "a.b"),
        )
    }

    @Test
    fun `whole text equal to keyword highlights`() {
        // JS split \u539f\u59cb\u8fd4\u56de ['', 'kw', '']\uff0cRN filter \u540e ['kw'] \u2192 matched \u5355\u6bb5
        assertEquals(
            listOf(SearchTextSegment("kw", true)),
            splitTextBySearchKeyword("kw", "kw"),
        )
    }

    @Test
    fun `no match returns single unmatched segment`() {
        assertEquals(
            listOf(SearchTextSegment("hello", false)),
            splitTextBySearchKeyword("hello", "zz"),
        )
    }

    @Test
    fun `keyword with regex specials in middle matches literally`() {
        val text = "\u4ef7\u683c\u662f \$100 (\u542b\u7a0e)"
        assertEquals(
            listOf(
                SearchTextSegment("\u4ef7\u683c\u662f ", false),
                SearchTextSegment("\$100 (\u542b", true),
                SearchTextSegment("\u7a0e)", false),
            ),
            splitTextBySearchKeyword(text, "\$100 (\u542b"),
        )
    }
}
