package cn.appia.im.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isServerVersionNewer 对照（RN src/utils/compareAppVersions.ts:8-20）：
 * semver coerce 提取 x.y.z 后数值 gt；任一侧提不出 → 小写字典序 local < server；空串恒 false。
 */
class CompareAppVersionsTest {

    // ---- semver 主路径 ----

    @Test
    fun `same semver is not newer`() {
        assertFalse(isServerVersionNewer("1.2.3", "1.2.3"))
    }

    @Test
    fun `server newer by patch minor major`() {
        assertTrue(isServerVersionNewer("1.2.4", "1.2.3"))
        assertTrue(isServerVersionNewer("1.3.0", "1.2.9"))
        assertTrue(isServerVersionNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `server older or equal is not newer`() {
        assertFalse(isServerVersionNewer("1.2.2", "1.2.3"))
        assertFalse(isServerVersionNewer("1.1.0", "1.2.3"))
        assertFalse(isServerVersionNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `numeric compare not lexicographic`() {
        // 字典序会把 "1.10.0" 排在 "1.9.0" 前——semver 路径必须按数值
        assertTrue(isServerVersionNewer("1.10.0", "1.9.0"))
        assertFalse(isServerVersionNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `suffixes are dropped by coerce`() {
        // RN semver.coerce 丢弃 prerelease/build 后缀：核心三元组相等即不新于
        assertFalse(isServerVersionNewer("1.2.3-beta", "1.2.3"))
        assertFalse(isServerVersionNewer("1.2.3", "1.2.3-rc.1"))
        assertFalse(isServerVersionNewer("1.2.3+build.7", "1.2.3"))
        assertTrue(isServerVersionNewer("1.2.4-rc.1", "1.2.3"))
    }

    @Test
    fun `extracts xyz from surrounding noise`() {
        assertTrue(isServerVersionNewer("v1.2.3 (build 45)", "1.2.2"))
        assertTrue(isServerVersionNewer("release-2.10.1-final", "2.9.9"))
    }

    // ---- 字典序回退 ----

    @Test
    fun `non semver falls back to lowercase lexicographic`() {
        assertTrue(isServerVersionNewer("beta", "alpha"))
        assertFalse(isServerVersionNewer("alpha", "beta"))
        assertFalse(isServerVersionNewer("same", "same"))
    }

    @Test
    fun `lexicographic fallback is case insensitive`() {
        assertTrue(isServerVersionNewer("BETA", "alpha"))
        assertFalse(isServerVersionNewer("Alpha", "beta"))
    }

    @Test
    fun `one side non semver uses lexicographic even against semver`() {
        // RN :13-19：一侧提不出 coerce → 整体走字典序 local < server（不与 semver 数值混比；
        // JS 字符串序里数字在字母前，故 "1.2.3" < "abc"）
        assertTrue(isServerVersionNewer("abc", "1.2.3"))
        assertFalse(isServerVersionNewer("1.2.3", "abc"))
    }

    // ---- 空串 ----

    @Test
    fun `empty or whitespace always false`() {
        assertFalse(isServerVersionNewer("", "1.2.3"))
        assertFalse(isServerVersionNewer("1.2.3", ""))
        assertFalse(isServerVersionNewer("", ""))
        assertFalse(isServerVersionNewer("   ", "1.2.3"))
        assertFalse(isServerVersionNewer("1.2.3", "   "))
    }

    @Test
    fun `surrounding whitespace is trimmed before compare`() {
        assertFalse(isServerVersionNewer(" 1.2.3 ", " 1.2.3 "))
        assertTrue(isServerVersionNewer(" 1.2.4 ", "1.2.3"))
    }
}
