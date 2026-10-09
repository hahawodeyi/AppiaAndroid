package cn.appia.im.core.update

/**
 * 判断服务端版本是否**严格新于**本地/对照版本（RN src/utils/compareAppVersions.ts:8-20 逐行）。
 * - 优先 semver：提取字符串中首个版本式数字段后按数值比较（RN semver.coerce + gt；coerce 丢弃
 *   `v` 前缀/prerelease/build 后缀，且对 x.y / x 形式零填充补 `.0`——评审 I-1：不补会静默漏提示，
 *   如 server=`1.10` local=`1.2.3` 应 true）。
 * - 提不出任何版本式数字 → 回退旧版字典序：`local < server`（忽略大小写，RN :19）。
 * - 空串恒 false（RN :11）。
 */
fun isServerVersionNewer(serverVersion: String, localOrBaseline: String): Boolean {
    val s = serverVersion.trim()
    val l = localOrBaseline.trim()
    if (s.isEmpty() || l.isEmpty()) return false

    val sCo = s.coerceSemver()
    val lCo = l.coerceSemver()
    if (sCo != null && lCo != null) {
        val (smaj, smin, spat) = sCo
        val (lmaj, lmin, lpat) = lCo
        return smaj > lmaj || (smaj == lmaj && (smin > lmin || (smin == lmin && spat > lpat)))
    }

    return l.lowercase() < s.lowercase()
}

private val SEMVER_XYZ = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

/** RN semver.coerce 的提取：首个版本式数字段（x.y.z / x.y / x，缺省组件补 0），按数值组件返回。 */
private fun String.coerceSemver(): Triple<Long, Long, Long>? =
    SEMVER_XYZ.find(this)?.let { m ->
        Triple(
            m.groupValues[1].toLong(),
            m.groupValues[2].ifEmpty { "0" }.toLong(),
            m.groupValues[3].ifEmpty { "0" }.toLong(),
        )
    }
