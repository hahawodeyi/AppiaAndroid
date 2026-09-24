package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `POST /api/v1/files.search`（RN src/lib/chat/filesSearch.ts / src/services/api/files.ts）：
 * body `{ text, cursor? }`；响应形状按 Rocket.Chat 常见字段解析（顶层 / data 嵌套 / cursor·filesNextCursor
 * 双名），缺省保守为空列表。
 */
object FilesSearchApi {

    /** RN GlobalSearchFileItem 原始 JSON（file 嵌套/attachments 等由 ViewModel 层解析）。 */
    data class Page(val files: List<JsonObject>, val nextCursor: String?, val hasMore: Boolean)

    /** RN fetchFilesSearchPage（filesSearch.ts:14-57）：多形状响应解析逐条移植。 */
    suspend fun search(sdk: RocketSdk, text: String, cursor: String? = null): Page {
        val q = text.trim()
        if (q.isEmpty()) return Page(emptyList(), null, false)
        val body = buildJsonObject {
            put("text", q)
            if (!cursor.isNullOrEmpty()) put("cursor", cursor)
        }
        val raw = sdk.post("files.search", body).let { it as? JsonObject } ?: return Page(emptyList(), null, false)

        // RN payload 双形状：顶层 files 数组，否则 data 对象下取
        val payload = if (raw["files"] is JsonArray) raw else (raw["data"] as? JsonObject) ?: raw

        val files = (payload["files"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        val nextCursor = payload.strOrNull("nextCursor")
            ?: payload.strOrNull("cursor")
            ?: raw.strOrNull("nextCursor")
            ?: raw.strOrNull("cursor")
        val hasMore = (payload.bool("hasMore") ?: payload.bool("filesHasMore")
            ?: raw.bool("hasMore") ?: raw.bool("filesHasMore"))
            ?: (files.isNotEmpty() && nextCursor != null)
        return Page(files, nextCursor, hasMore)
    }
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
