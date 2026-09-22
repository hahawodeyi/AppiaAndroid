package cn.appia.im.core.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 权限缓存（RN stores/permissionsStore.ts 的 zustand 模块单例直译）：
 * 权限 id → 具备该权限的服务端角色名列表（对齐旧版 Redux `state.permissions`）。
 *
 * 模块级 object（RN `create()` 模块单例等价）；登出清空（RN authStore.ts:147，
 * AuthRepository.logout 同位调用 [reset]）。
 */
object PermissionsStore {

    private val _permissions = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** RN `permissions` state：StateFlow 承载；写路径 update{} CAS（DDP 线程与同步协程并发不丢补丁）。 */
    val permissions: StateFlow<Map<String, List<String>>> = _permissions

    /** RN setPermissionsMap：整表覆盖。 */
    fun setPermissionsMap(map: Map<String, List<String>>) {
        _permissions.value = map.toMap()
    }

    /** RN updatePermission：单条 upsert。 */
    fun updatePermission(id: String, roles: List<String>) {
        _permissions.update { it + (id to roles.toList()) }
    }

    /** RN getPermissionRoles。 */
    fun getPermissionRoles(id: String): List<String>? = _permissions.value[id]

    /** RN reset：登出清空。 */
    fun reset() {
        _permissions.value = emptyMap()
    }

    /**
     * `stream-notify-logged` permissions-changed 帧消费（RN session.ts:107-126
     * handleStreamNotifyLogged 逐行）：eventName 含 `permissions-changed` → args[1]
     * 取 `{_id, roles}`，roles 过滤字符串后单条 upsert（**不筛 tracked 清单**——RN 同，
     * 任意权限 id 都进 store）。坏帧静默忽略（RN :123-125 try/catch 同义）。
     *
     * 接入：SessionModule 经 RealtimeSessionManager 分发注册表注册（M1 接口零改动，
     * NotifyUserPersistence 先例）；handler 在 DDP IO 线程被调，StateFlow 写路径原子无需 hop。
     */
    fun applyPermissionsChangedFrame(ddpMessage: JsonElement) {
        try {
            val fields = (ddpMessage as? JsonObject)?.get("fields") as? JsonObject ?: return
            val eventName = fields.str("eventName") ?: return
            if (!eventName.contains("permissions-changed")) return
            val args = fields["args"] as? JsonArray ?: return
            if (args.size < 2) return
            val payload = args[1] as? JsonObject ?: return
            val id = payload.str("_id") ?: return
            val rolesArray = payload["roles"] as? JsonArray ?: return
            updatePermission(id, rolesArray.mapNotNull { it.strValue() })
        } catch (_: Exception) {
            // ignore malformed stream payloads
        }
    }
}

/** JSON 字段取串：缺失/非字符串原始型 → null（NotifyUserPersistence.str 同口径）。 */
private fun JsonObject.str(key: String): String? = this[key].strValue()

private fun JsonElement?.strValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
