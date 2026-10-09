package cn.appia.im.core.push

/**
 * 推送点击待导航队列（RN roomPushNavigationQueue.ts 逐行移植）。
 *
 * 语义：
 * - 内存队列，90s TTL（`TTL_MS`），过期项在入队/冲刷时双向丢弃（坑 7）；
 * - 按 `rid:messageId` 去重（同 key 重入队 = 旧项移除、新项追加，RN :18-30）；
 * - `drain()` 取出全部未过期项并清空队列（RN drainRoomPushNavigationQueue :33-41）；
 * - drain 门控（`isAuthenticated && navReady`）由消费方（T5 导航层）负责，本队列只存取；
 * - 时钟可注入，测试用 FakeClock 推进。
 */
class PendingPushNavigation(private val clock: () -> Long = System::currentTimeMillis) {

    data class Intent(
        val rid: String,
        val t: String,
        val title: String? = null,
        val messageId: String? = null,
        val receivedAt: Long,
    )

    private val items = ArrayDeque<Intent>()

    private fun Intent.dedupeKey(): String = "$rid:${messageId ?: ""}"

    private fun isExpired(intent: Intent): Boolean = clock() - intent.receivedAt > TTL_MS

    /** RN enqueueRoomPushNavigation :21-31（外加：入队时顺带清扫过期项，见 brief 语义）。 */
    @Synchronized
    fun enqueue(
        rid: String,
        t: String,
        title: String?,
        messageId: String?,
        receivedAt: Long? = null,
    ) {
        val next = Intent(rid, t, title, messageId, receivedAt ?: clock())
        items.removeAll { isExpired(it) || it.dedupeKey() == next.dedupeKey() }
        items.addLast(next)
    }

    /** RN drainRoomPushNavigationQueue :33-41：过期项丢弃，有效项按序全部交出，队列清空。 */
    @Synchronized
    fun drain(): List<Intent> {
        val pending = items.filter { !isExpired(it) }
        items.clear()
        return pending
    }

    @Synchronized
    fun clear() = items.clear()

    val pendingCount: Int
        @Synchronized get() = items.size

    companion object {
        /** RN roomPushNavigationQueue.ts:11 `TTL_MS = 90_000`。 */
        const val TTL_MS = 90_000L

        /**
         * 进程级共享实例：PushClickRouter.shared 入队，T5 导航层 drain 同一实例。
         */
        val shared = PendingPushNavigation()
    }
}
