package cn.appia.im.feature.mail

import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.mail.LatestMailPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 邮件角标/预览持久态（mailChannelStore.ts zustand persist version 2 移植）。
 *
 * RN 形态：`mailChannelPinned` + 三张 per-org 分桶表（lastVisitAt/unreadCount/preview），
 * MMKV 持久化 + version 2 migrate（v1 扁平字段 → 分桶）。Android 等价：单 KvStore 键存
 * v2 JSON 快照，内存 StateFlow 驱动 UI（会话列表行实时刷新），写路径同步落盘；
 * v1 扁平形态（RN zustand persist 旧键名）解析失败前回落兼容。
 *
 * org 分桶语义（mailPreviewByOrg.ts）：多组织并存互不覆盖；orgKey = normalizeMailOrgKey(serverUrl)。
 */

/** mailPreviewByOrg.ts:2-3 normalizeMailOrgKey：trim + 去尾斜杠。 */
fun normalizeMailOrgKey(serverUrl: String?): String = serverUrl?.trim()?.trimEnd('/') ?: ""

/** mailPreviewByOrg.ts:16-24 selectMailPreviewForOrg：空 key → null；缺桶 → null。 */
internal fun selectPreviewForOrg(
    byOrg: Map<String, LatestMailPreview?>,
    orgKey: String,
): LatestMailPreview? = if (orgKey.isEmpty()) null else byOrg[orgKey]

/** mailPreviewByOrg.ts:26-34 applyMailPreviewForOrg：null 预览删除桶（RN delete 语义）。 */
internal fun applyPreviewForOrg(
    byOrg: Map<String, LatestMailPreview?>,
    orgKey: String,
    preview: LatestMailPreview?,
): Map<String, LatestMailPreview?> {
    if (orgKey.isEmpty()) return byOrg
    val next = byOrg.toMutableMap()
    if (preview == null) next.remove(orgKey) else next[orgKey] = preview
    return next
}

/** mailPreviewByOrg.ts:36-43 selectCountForOrg：空 key → 0；缺桶 → 0。 */
internal fun selectCountForOrg(byOrg: Map<String, Int>, orgKey: String): Int =
    if (orgKey.isEmpty()) 0 else byOrg[orgKey] ?: 0

/** mailPreviewByOrg.ts:45-52 applyCountForOrg：负值钳 0。 */
internal fun applyCountForOrg(
    byOrg: Map<String, Int>,
    orgKey: String,
    count: Int,
): Map<String, Int> = if (orgKey.isEmpty()) byOrg else byOrg + (orgKey to maxOf(0, count))

/** mailPreviewByOrg.ts:54-61 selectLastVisitAtForOrg：空 key → 0；缺桶 → 0。 */
internal fun selectLastVisitAtForOrg(byOrg: Map<String, Long>, orgKey: String): Long =
    if (orgKey.isEmpty()) 0L else byOrg[orgKey] ?: 0L

/** mailPreviewByOrg.ts:63-70 applyLastVisitAtForOrg。 */
internal fun applyLastVisitAtForOrg(
    byOrg: Map<String, Long>,
    orgKey: String,
    at: Long,
): Map<String, Long> = if (orgKey.isEmpty()) byOrg else byOrg + (orgKey to at)

/** 持久化快照（RN PersistedMailChannelState）。 */
@Serializable
data class MailBadgeSnapshot(
    val pinned: Boolean = false,
    val lastVisitAtByOrg: Map<String, Long> = emptyMap(),
    val unreadCountByOrg: Map<String, Int> = emptyMap(),
    val previewByOrg: Map<String, LatestMailPreview?> = emptyMap(),
)

/**
 * 角标 store（mailChannelStore.ts create(persist(...)) 等价）。
 *
 * 线程契约：DDP IO 线程（推送增量）与主线程（UI 读写）并发调用；写路径先改内存
 * StateFlow 再同步落盘——KvStore/MMKV 自带线程安全，快照整体覆盖无读改写竞态。
 */
class MailBadgeStore(
    private val kv: KvStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** RN persist name 'mail-channel-storage' + version 2。 */
        private const val KEY = "mail-channel-storage"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val snapshotSerializer = MailBadgeSnapshot.serializer()
    }

    private val state = MutableStateFlow(loadSnapshot())

    /** 全量快照流（RN useMailChannelStore(selector) 等价；装配层按 orgKey map 派生行数据）。 */
    val snapshot: StateFlow<MailBadgeSnapshot> = state.asStateFlow()

    private fun loadSnapshot(): MailBadgeSnapshot {
        val raw = kv.getString(KEY, "")
        if (raw.isEmpty()) return MailBadgeSnapshot()
        // RN version 2 快照；AA 无 v1 存量（RN 的 v1 在其自身 MMKV，不共享），损坏 JSON → 默认态
        return json.runCatching { decodeFromString(snapshotSerializer, raw) }.getOrNull() ?: MailBadgeSnapshot()
    }

    private fun persist(snapshot: MailBadgeSnapshot) {
        kv.putString(KEY, json.encodeToString(snapshotSerializer, snapshot))
    }

    private fun mutate(block: (MailBadgeSnapshot) -> MailBadgeSnapshot) {
        val next = block(state.value)
        state.value = next
        persist(next)
    }

    // ---- RN store actions（orgKey 空串守卫 = resolveOrgKey 后 !key return）----

    /** setPinned。 */
    fun setPinned(pinned: Boolean) = mutate { it.copy(pinned = pinned) }

    /**
     * markChannelVisited：lastVisitAt = now + 未读清零（RN :67-78 两表同写）。
     * MailChannelScreen focus 触发（RN useFocusEffect）。
     */
    fun markChannelVisited(orgKey: String) {
        if (orgKey.isEmpty()) return
        mutate { s ->
            s.copy(
                lastVisitAtByOrg = applyLastVisitAtForOrg(s.lastVisitAtByOrg, orgKey, nowMs()),
                unreadCountByOrg = applyCountForOrg(s.unreadCountByOrg, orgKey, 0),
            )
        }
    }

    /** incrementUnread（推送 newCount 增量；负值钳 0 后累加）。 */
    fun incrementUnread(n: Int, orgKey: String) {
        if (orgKey.isEmpty()) return
        mutate { s ->
            s.copy(
                unreadCountByOrg = applyCountForOrg(
                    s.unreadCountByOrg,
                    orgKey,
                    selectCountForOrg(s.unreadCountByOrg, orgKey) + maxOf(0, n),
                ),
            )
        }
    }

    /** setUnreadCount（启发式全量覆写）。 */
    fun setUnreadCount(count: Int, orgKey: String) {
        if (orgKey.isEmpty()) return
        mutate { s -> s.copy(unreadCountByOrg = applyCountForOrg(s.unreadCountByOrg, orgKey, count)) }
    }

    /** setPreview（null 删桶）。 */
    fun setPreview(preview: LatestMailPreview?, orgKey: String) {
        if (orgKey.isEmpty()) return
        mutate { s -> s.copy(previewByOrg = applyPreviewForOrg(s.previewByOrg, orgKey, preview)) }
    }

    /**
     * ensureLastVisitAtInitialized（RN :113-127）：无桶才写 now（首装基线——
     * 未读启发式 since 语义：首装前的邮件不计未读）。
     */
    fun ensureLastVisitAtInitialized(orgKey: String) {
        if (orgKey.isEmpty()) return
        if (selectLastVisitAtForOrg(state.value.lastVisitAtByOrg, orgKey) > 0) return
        mutate { s -> s.copy(lastVisitAtByOrg = applyLastVisitAtForOrg(s.lastVisitAtByOrg, orgKey, nowMs())) }
    }

    // ---- 读口（RN getter selector 等价；orgKey 现读由调用方传入）----

    fun previewForOrg(orgKey: String): LatestMailPreview? = selectPreviewForOrg(state.value.previewByOrg, orgKey)

    fun unreadCountForOrg(orgKey: String): Int = selectCountForOrg(state.value.unreadCountByOrg, orgKey)

    fun lastVisitAtForOrg(orgKey: String): Long = selectLastVisitAtForOrg(state.value.lastVisitAtByOrg, orgKey)

    fun pinned(): Boolean = state.value.pinned

    /** resetForLogout（RN :131-132 set defaultState）。 */
    fun reset() {
        state.value = MailBadgeSnapshot()
        persist(MailBadgeSnapshot())
    }
}
