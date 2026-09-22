package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.permissions.PermissionsStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * permissions.listAll 全量同步（RN services/permissions/syncPermissions.ts
 * syncPermissionsFromServer 逐行）。
 *
 * 固定 id 清单（RN PERMISSION_IDS_TO_TRACK :5-14 逐字）：
 * edit-room / add-user-to-joined-room / remove-user / set-owner / set-moderator /
 * set-ghost-owner / delete-c / delete-p。
 *
 * 响应形状（RN :18-23 定案）：`{success, update:[{_id, roles}], remove:[{_id}]}`；
 * update 只收 tracked 清单且 roles 为数组才写；remove 对**任意** id 删条目（RN :38-41 无
 * tracked 过滤）；update/remove 双空 → 不动 store；失败保留已有缓存/兜底（RN :44-46）。
 */
object PermissionsApi {

    /** RN PERMISSION_IDS_TO_TRACK :5-14（逐字）。 */
    val PERMISSION_IDS_TO_TRACK: Set<String> = setOf(
        "edit-room",
        "add-user-to-joined-room",
        "remove-user",
        "set-owner",
        "set-moderator",
        "set-ghost-owner",
        "delete-c",
        "delete-p",
    )

    /**
     * 全量拉取并写入 [PermissionsStore]。
     * @return 实际写入的条数（测试观测；RN 无返回值）。
     */
    suspend fun syncPermissions(sdk: RocketSdk): Int {
        return try {
            val raw = sdk.get("permissions.listAll")
            val res = raw as? JsonObject ?: return 0
            if (res["success"]?.let { (it as? JsonPrimitive)?.content } != "true") return 0

            val update = res["update"] as? JsonArray ?: JsonArray(emptyList())
            val remove = res["remove"] as? JsonArray ?: JsonArray(emptyList())
            if (update.isEmpty() && remove.isEmpty()) return 0

            val next = PermissionsStore.permissions.value.toMutableMap()
            var written = 0

            for (entry in update) {
                val obj = entry as? JsonObject ?: continue
                val id = obj.str("_id") ?: continue
                if (id !in PERMISSION_IDS_TO_TRACK) continue
                val roles = obj["roles"] as? JsonArray ?: continue
                next[id] = roles.mapNotNull { it.strValue() }
                written++
            }

            for (entry in remove) {
                val id = (entry as? JsonObject)?.str("_id") ?: continue
                next.remove(id)
            }

            PermissionsStore.setPermissionsMap(next)
            written
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0 // 保留已有缓存 / fallback（RN :44-46）
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.strValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
