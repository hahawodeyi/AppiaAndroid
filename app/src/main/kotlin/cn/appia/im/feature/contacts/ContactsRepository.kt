package cn.appia.im.feature.contacts

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/** RN ContactsPhase。 */
enum class ContactsPhase { UNLOAD, LOADING, LOAD_ERROR, LOADED }

/**
 * 通讯录数据源（RN stores/contactStore.ts zustand 模块单例 + services/api/contacts.ts +
 * hooks/useContacts.ts 自动拉取语义的合并移植）：
 * - `hrm/v2.users.list` → parseContactsMap（data 包裹/顶层字段双兼容）
 * - refresh 序号防竞态（refreshSeq：旧响应迟到丢弃）
 * - phase UNLOAD 时进屏自动拉取（useContacts effect）；LOAD_ERROR 后重试由页面手动触发
 *   （RN 同——避免失败死循环）
 *
 * 模块级 object（RN create() 单例等价）；登出清空（AuthRepository.logout 同位 reset，
 * PermissionsStore 先例）。
 */
object ContactsStore {

    private val _phase = MutableStateFlow(ContactsPhase.UNLOAD)
    val phase: StateFlow<ContactsPhase> = _phase

    private val _payload = MutableStateFlow(ContactsMapPayload())
    val payload: StateFlow<ContactsMapPayload> = _payload

    private val refreshSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** RN refresh：LOADING → fetch → LOADED / LOAD_ERROR；序号不匹配丢弃。 */
    suspend fun refresh(sdk: RocketSdk) {
        val seq = refreshSeq.incrementAndGet()
        _phase.value = ContactsPhase.LOADING
        try {
            val raw: JsonElement = sdk.get("hrm/v2.users.list")
            if (refreshSeq.get() != seq) return
            _payload.value = parseContactsMap(raw)
            _phase.value = ContactsPhase.LOADED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (refreshSeq.get() != seq) return
            _phase.value = ContactsPhase.LOAD_ERROR
        }
    }

    /** 进屏自动拉取（RN useContacts effect：仅 UNLOAD 拉一次；失败不重试防死循环）。 */
    suspend fun refreshIfUnloaded(sdk: RocketSdk) {
        if (_phase.value != ContactsPhase.UNLOAD) return
        runCatching { refresh(sdk) }
    }

    /** 登出清空（RN 无显式 reset——zustand 模块单例随进程存活；Android 会话切换需回 UNLOAD）。 */
    fun reset() {
        refreshSeq.incrementAndGet()
        _phase.value = ContactsPhase.UNLOAD
        _payload.value = ContactsMapPayload()
    }
}

/**
 * 仓库薄层（OrgListRepository 先例：测试可注入 MockWebServer sdk）。
 * 纯转发到 [ContactsStore]；存在意义是给 UI 测试一个可构造的接缝。
 */
class ContactsRepository(private val sdk: RocketSdk) {
    suspend fun refresh() = ContactsStore.refresh(sdk)
    suspend fun refreshIfUnloaded() = ContactsStore.refreshIfUnloaded(sdk)
}
