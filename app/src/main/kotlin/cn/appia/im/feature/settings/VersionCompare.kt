package cn.appia.im.feature.settings

/**
 * RN utils/compareAppVersions.ts isServerVersionNewer：服务端版本是否**严格新于**本地。
 * - 优先 semver 语义（coerce 提取首个 `x.y.z` 三段数字组比较）
 * - 任一侧 coerce 不出 → 回退旧版字典序：`local < server`（忽略大小写）
 * - 任一侧空白 → false
 */
fun isServerVersionNewer(serverVersion: String, localOrBaseline: String): Boolean {
    val s = serverVersion.trim()
    val l = localOrBaseline.trim()
    if (s.isEmpty() || l.isEmpty()) return false

    val sCo = coerceSemver(s)
    val lCo = coerceSemver(l)
    if (sCo != null && lCo != null) {
        val cmp = compareSemver(sCo, lCo)
        if (cmp != 0) return cmp > 0
        return false // coerce 后相等（后缀不同）→ 不视为更新（semver.gt 同口径）
    }
    return l.lowercase() < s.lowercase()
}

/** RN semver.coerce：提取首个 `\d+\.\d+(\.\d+)?` 形态（semver 库降级匹配前两段补 0）。 */
internal fun coerceSemver(v: String): LongArray? {
    val m = Regex("\\d+\\.\\d+(?:\\.\\d+)?").find(v) ?: return null
    val parts = m.value.split(".").map { it.toLong() }
    return longArrayOf(parts[0], parts[1], parts.getOrElse(2) { 0L })
}

private fun compareSemver(a: LongArray, b: LongArray): Int {
    for (i in 0..2) {
        if (a[i] != b[i]) return if (a[i] > b[i]) 1 else -1
    }
    return 0
}
