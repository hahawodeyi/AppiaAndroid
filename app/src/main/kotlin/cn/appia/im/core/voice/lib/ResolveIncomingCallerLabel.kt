package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/resolveIncomingCallerLabel.ts verbatim。
 * 来电横幅/悬浮条展示名：callMsg 同步抖动时避免在姓名与 username 间闪烁。
 */

/** resolveIncomingCallerLabel.ts:3-6 IncomingCallerIdentity。 */
data class IncomingCallerIdentity(
    val username: String,
    val displayName: String,
)

/** resolveIncomingCallerLabel.ts:8-16。 */
private fun looksLikeUsernameOnly(label: String, username: String): Boolean {
    if (label.trim().isEmpty()) {
        return true
    }
    if (username.trim().isEmpty()) {
        return false
    }
    return label == username
}

/** resolveIncomingCallerLabel.ts:19-40：解析主叫 username 与展示名（不把 username 插在 name 字段中间）。 */
fun resolveIncomingCallerIdentity(callMsg: VoiceCallMsg?): IncomingCallerIdentity {
    val username = (
        callMsg?.invitorUsername?.trim()?.ifEmpty { null }
            ?: callMsg?.recordDataObj?.initiator?.trim()?.ifEmpty { null }
            ?: callMsg?.uObj?.username?.trim()?.ifEmpty { null }
        ) ?: ""

    val explicitName = (
        callMsg?.invitorName?.trim()?.ifEmpty { null }
            ?: callMsg?.recordDataObj?.initiatorName?.trim()?.ifEmpty { null }
        ) ?: ""

    val uName = callMsg?.uObj?.name?.trim()
    val displayName = explicitName.ifEmpty {
        if (!uName.isNullOrEmpty() && uName != username) uName else ""
    }.ifEmpty { username }

    return IncomingCallerIdentity(username, displayName)
}

/** resolveIncomingCallerLabel.ts:45-65。 */
fun resolveIncomingCallerLabel(callMsg: VoiceCallMsg?, previousLabel: String? = null): String {
    val (username, displayName) = resolveIncomingCallerIdentity(callMsg)
    val prev = previousLabel?.trim() ?: ""

    if (prev.isEmpty()) {
        return displayName
    }
    if (looksLikeUsernameOnly(prev, username) && !looksLikeUsernameOnly(displayName, username)) {
        return displayName
    }
    if (!looksLikeUsernameOnly(displayName, username)) {
        return displayName
    }
    return prev
}

/** resolveIncomingCallerLabel.ts:67-72。 */
fun getIncomingCallRecordId(callMsg: VoiceCallMsg?): String =
    callMsg?.recordId?.trim()?.ifEmpty { null }
        ?: callMsg?.recordDataObj?.recordId?.trim()?.ifEmpty { null }
        ?: ""
