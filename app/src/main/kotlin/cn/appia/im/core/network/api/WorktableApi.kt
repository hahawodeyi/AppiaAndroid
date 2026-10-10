package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 工作台 wire 类型（RN types/worktable.ts 逐字段）：服务端字段名 verbatim
 * （`need_auth`/`url_type`/`needVPN` 原样下发，勿「规范化」为 camelCase）。
 * 全字段默认值：config 端点为服务端运营数据，缺字段按 RN `undefined` 语义兜底
 * （status 缺省 = 0 → 归一化期被 status<=0 过滤，与 RN `undefined > 0 === false` 同）。
 */
@Serializable
data class WorktableItemExtra(
    val source: String? = null,
    val name: String? = null,
    val needVPN: Boolean? = null,
)

@Serializable
data class WorktableItem(
    val name: String = "",
    val desc: String = "",
    val status: Int = 0,
    val type: Int = 0,
    val seq: Int = 0,
    val url: String = "",
    val icon: String = "",
    @SerialName("need_auth") val needAuth: Boolean = false,
    @SerialName("url_type") val urlType: Int? = null,
    val extra: WorktableItemExtra? = null,
)

/** `row` 为服务端旧版分栏字段：RN LaborScreen 布局不消费（styles.gridRow 4 列换行），仅随形保留。 */
@Serializable
data class WorktableGroup(
    val name: String = "",
    val row: Int = 0,
    val items: List<WorktableItem> = emptyList(),
)

private val worktableJson = Json { ignoreUnknownKeys = true }

/**
 * RN fetchWorktableConfig.ts:8-21 —— `GET {server}/appia_be/v1/api/worktable_config?platform=app`。
 *
 * **裸 fetch 契约（研究坑 8）**：RN 原生 fetch 不带任何 IM 鉴权头；AA 侧有意**不经**
 * RocketSdk/RetrofitFactory（二者均挂 AuthInterceptor 注入 X-Auth-Token/X-User-Id，
 * 且 401 会误发 SessionExpiredBus 登出）——直接用共享 [RocketHttp.client]（零 interceptor
 * 构造，RocketHttp.kt:10）。client 参数仅供测试注入 MockWebServer 地址。
 * `data` 非数组（RN `Array.isArray` 假分支）或单条解析失败 → 丢弃该条（normalize 侧
 * `typeof g !== 'object'` 守卫的解析期等价）。
 */
suspend fun fetchWorktableConfig(
    serverUrl: String,
    client: OkHttpClient = RocketHttp.client,
): List<WorktableGroup> = withContext(Dispatchers.IO) {
    val url = "${ServerUrl.normalizeServer(serverUrl)}/appia_be/v1/api/worktable_config?platform=app"
    client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw cn.appia.im.core.network.rest.ApiException("worktable_config HTTP ${resp.code}", resp.code)
        }
        val body = resp.body.string()
        val root = if (body.isEmpty()) null else runCatching { worktableJson.parseToJsonElement(body) }.getOrNull()
        val data = (root as? JsonObject)?.get("data") as? JsonArray ?: return@use emptyList()
        data.mapNotNull { entry -> runCatching { worktableJson.decodeFromJsonElement<WorktableGroup>(entry) }.getOrNull() }
    }
}
