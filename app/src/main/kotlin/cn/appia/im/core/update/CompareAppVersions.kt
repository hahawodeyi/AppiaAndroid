package cn.appia.im.core.update

/**
 * 判断服务端版本是否**严格新于**本地/对照版本（RN src/utils/compareAppVersions.ts:8-20 逐行）。
 * - 优先 semver：提取字符串中首个 `x.y.z` 后按数值比较（RN semver.coerce + gt；coerce 丢弃
 *   `v` 前缀/prerelease/build 后缀）。
 *   `ponytail:` RN coerce 还兼容 `x.y`→`x.y.0`、`x`→`x.0.0`；本移植仅提 x.y.z——线上版本号
 *   恒为 `x.y.z[-suffix]`（research §5.2），无此形态，出现时落字典序回退分支。
 * - 任一侧提不出 x.y.z → 回退旧版字典序：`local < server`（忽略大小写，RN :19）。
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

private val SEMVER_XYZ = Regex("""(\d+)\.(\d+)\.(\d+)""")

/** RN semver.coerce 的 x.y.z 提取（首个数字三元组，按数值组件返回）。 */
private fun String.coerceSemver(): Triple<Long, Long, Long>? =
    SEMVER_XYZ.find(this)?.let { m ->
        Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
    }
