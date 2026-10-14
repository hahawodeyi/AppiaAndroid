package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/voiceCallDismissed.ts verbatim（本地忽略来电记录）。
 * 纯内存 Map（RN 模块级 Map）；AA 侧为普通类，session 域单例由后续 Controller 持有。
 */
class VoiceCallDismissedStore {

    private data class DismissedEntry(val inviteSnapshot: String)

    private val dismissedRecords = mutableMapOf<String, DismissedEntry>()

    /** voiceCallDismissed.ts:10。 */
    private fun normalizeRecordId(recordId: String): String = recordId.trim()

    /** voiceCallDismissed.ts:12-24：忽略时的邀请快照（status/exist/inChannel/selfOp/selfUserStatus）。 */
    fun buildVoiceInviteSnapshot(callMsg: VoiceCallMsg, username: String): String {
        val self = callMsg.userStatus?.find { it.username == username }
        return buildString {
            append("{\"status\":\"").append(callMsg.status).append('"')
            append(",\"exist\":").append(callMsg.recordDataObj?.exist?.toString() ?: "null")
            append(",\"inChannel\":").append(countUsersInVoiceChannel(callMsg))
            append(",\"selfOp\":").append(self?.operation?.let { "\"$it\"" } ?: "null")
            append(",\"selfUserStatus\":").append(self?.status?.let { "\"$it\"" } ?: "null")
            append("}")
        }
    }

    /** voiceCallDismissed.ts:26-40：recordId 空白不记；无 callMsg/username 时快照为空串。 */
    fun markVoiceCallDismissed(recordId: String, callMsg: VoiceCallMsg? = null, username: String? = null) {
        val id = normalizeRecordId(recordId)
        if (id.isEmpty()) return
        val inviteSnapshot = if (callMsg != null && username != null) {
            buildVoiceInviteSnapshot(callMsg, username)
        } else {
            ""
        }
        dismissedRecords[id] = DismissedEntry(inviteSnapshot)
    }

    /** voiceCallDismissed.ts:42-43。 */
    fun isVoiceCallDismissed(recordId: String): Boolean = dismissedRecords.containsKey(normalizeRecordId(recordId))

    /**
     * voiceCallDismissed.ts:45-58：快照不同（如 mid-call reinvite）视为新邀请——
     * 删除记录并放行（不 block）。
     */
    fun shouldBlockDismissedIncoming(recordId: String, inviteSnapshot: String): Boolean {
        val id = normalizeRecordId(recordId)
        val entry = dismissedRecords[id] ?: return false
        if (entry.inviteSnapshot != inviteSnapshot) {
            dismissedRecords.remove(id)
            return false
        }
        return true
    }

    /** voiceCallDismissed.ts:60-62。 */
    fun clearVoiceCallDismissed(recordId: String) {
        dismissedRecords.remove(normalizeRecordId(recordId))
    }

    /** voiceCallDismissed.ts:64-66。 */
    fun clearAllVoiceCallDismissed() {
        dismissedRecords.clear()
    }
}
