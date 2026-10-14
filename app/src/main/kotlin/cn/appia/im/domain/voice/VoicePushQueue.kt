package cn.appia.im.domain.voice

/**
 * RN voicePushQueue.ts verbatim：推送语音意图内存队列（登录前/解析失败的暂存）。
 *
 * - enqueue：isCallEnd 丢弃；同 dedupeKey（recordId ?? `rid:receivedAt`）先删后插（重拨刷新）。
 * - drain：过滤 90s TTL 过期条目后整批取走逐条处理（处理中再入队由 drainPending 循环冲刷）。
 */
class VoicePushQueue {

    /** RN voicePushQueue.ts:3-12 VoicePushIntent。 */
    data class Intent(
        val rid: String,
        val recordId: String? = null,
        val org: String? = null,
        val callMsg: cn.appia.im.core.voice.VoiceCallMsg? = null,
        val callMsgRaw: String? = null,
        val isCallEnd: Boolean = false,
        val host: String? = null,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    private var queue: MutableList<Intent> = mutableListOf()

    /** RN :18-19 TTL_MS = 90_000。 */
    private val ttlMs = 90_000L

    private fun isExpired(intent: Intent): Boolean = System.currentTimeMillis() - intent.receivedAt > ttlMs

    /** RN :21-22 dedupeKey。 */
    private fun dedupeKey(intent: Intent): String =
        intent.recordId ?: "${intent.rid}:${intent.receivedAt}"

    /** RN :24-31 enqueueVoicePushIntent。 */
    fun enqueue(intent: Intent) {
        if (intent.isCallEnd) return
        synchronized(lock) {
            val key = dedupeKey(intent)
            queue.removeAll { dedupeKey(it) == key }
            queue.add(intent)
        }
    }

    /** RN :33-41 drainVoicePushQueue：取走未过期条目并清队列。 */
    fun drain(): List<Intent> = synchronized(lock) {
        val pending = queue.filter { !isExpired(it) }
        queue = mutableListOf()
        pending
    }

    /** RN :43-44 clearVoicePushQueue。 */
    fun clear() {
        synchronized(lock) { queue = mutableListOf() }
    }

    /** RN :47 getPendingVoicePushIntentCount（AA 测试/观测用）。 */
    val pendingCount: Int
        get() = synchronized(lock) { queue.size }
}
