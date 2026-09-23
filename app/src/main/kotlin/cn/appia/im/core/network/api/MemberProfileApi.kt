package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * users.info + POTA/OKR 三端点（RN src/services/api/memberProfile.ts + otkr.ts 对照）：
 * - `GET users.info?userId=`（RN :38-42；参数为用户 _id 非 username）
 * - `GET otkr.canQuery?owner=&viewer=` / `GET otkr.date?username=` / `GET otkr.query?username=&time=`
 */

/** RN MemberProfileData（services/api/memberProfile.ts:4-30 字段子集——页面渲染所需）。 */
data class MemberProfileData(
    val _id: String? = null,
    val username: String? = null,
    val name: String? = null,
    val fname: String? = null,
    val emails: List<String> = emptyList(),
    val jobName: String? = null,
    val primaryOrgName: String? = null,
    val status: String? = null,
    val statusConnection: String? = null,
    val onlineStatus: String? = null,
    val statusText: String? = null,
    val leaderNames: List<String> = emptyList(),
    val canViewResume: Boolean = false,
    val resumeDownloadUrl: String? = null,
    val profileUrl: String? = null,
)

/** RN fetchMemberProfile：`GET users.info {userId}`；user 键缺失/异常容忍 null。 */
suspend fun fetchMemberProfile(sdk: RocketSdk, userId: String): MemberProfileData? {
    val raw = sdk.get("users.info", mapOf("userId" to userId))
    return parseMemberProfile(raw)
}

/** RN `res.user`（useMemberProfile :87 `res.user ?? null`；sdk.get 已平铺 data）。 */
internal fun parseMemberProfile(raw: JsonElement?): MemberProfileData? {
    val user = (raw as? JsonObject)?.get("user") as? JsonObject ?: return null
    fun str(key: String): String? =
        (user[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
    val emails = (user["emails"] as? JsonArray)
        ?.mapNotNull { e ->
            (e as? JsonObject)?.let { o ->
                (o["address"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        .orEmpty()
    val leaders = (user["leaderNames"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()
    return MemberProfileData(
        _id = str("_id"),
        username = str("username"),
        name = str("name"),
        fname = str("fname"),
        emails = emails,
        jobName = str("jobName"),
        primaryOrgName = str("primaryOrgName"),
        status = str("status"),
        statusConnection = str("statusConnection"),
        onlineStatus = str("onlineStatus"),
        statusText = str("statusText"),
        leaderNames = leaders,
        canViewResume = (user["canViewResume"] as? JsonPrimitive)?.contentOrNull == "true",
        resumeDownloadUrl = str("resumeDownloadUrl"),
        profileUrl = str("profileUrl"),
    )
}

/** RN buildResumeWebUrl（lib/memberProfile/buildResumeWebUrl.ts）：`//host` → `https://host`。 */
internal fun buildResumeWebUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) return trimmed
    return "https:$trimmed"
}

// ── POTA/OKR（RN services/api/otkr.ts 三端点 + OtkrSection.tsx 消费面）──

/** RN OtkrTab {label, key}。 */
data class OtkrTab(val label: String, val key: String)

/** RN O/KT/KR 层级行（OtkrSection.tsx:10-12；items 逐层嵌套）。 */
data class OtkrKrItem(val index: String, val data: String)
data class OtkrKtItem(val index: String, val data: String, val items: List<OtkrKrItem> = emptyList())
data class OtkrOItem(val index: String, val data: String, val items: List<OtkrKtItem> = emptyList())

/** RN getOtkrCanQuery：`GET otkr.canQuery {owner, viewer}`；仅 success && data 非空放行。 */
internal fun parseOtkrCanQuery(raw: JsonElement?): Boolean {
    val o = raw as? JsonObject ?: return false
    val success = (o["success"] as? JsonPrimitive)?.contentOrNull == "true"
    val hasData = o["data"]?.let { it !is JsonNull && it.toString() != "{}" && it.toString() != "[]" } == true
    return success && hasData
}

/** RN fetchOtkrDate：`GET otkr.date {username}`；data 数组 {label,key}。 */
internal fun parseOtkrDate(raw: JsonElement?): List<OtkrTab> {
    val data = (raw as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
    return data.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        OtkrTab(label, key)
    }
}

/**
 * RN fetchOtkrQuery 解析（OtkrSection.tsx:88 `res?.data?.self?.data ?? res?.data?.data`）+
 * 三层 sanitize（:15-26：逐层滤 null / data 缺失行；KO hidden 标记保留原样）。
 */
internal fun parseOtkrQuery(raw: JsonElement?): List<OtkrOItem> {
    val data = (raw as? JsonObject)?.get("data") as? JsonObject ?: return emptyList()
    val selfData = (data["self"] as? JsonObject)?.get("data") as? JsonArray
        ?: data["data"] as? JsonArray
        ?: return emptyList()
    return selfData.mapNotNull { oEl ->
        val o = oEl as? JsonObject ?: return@mapNotNull null
        val oData = (o["data"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
        OtkrOItem(
            index = (o["index"] as? JsonPrimitive)?.contentOrNull ?: "",
            data = oData,
            items = (o["items"] as? JsonArray).orEmpty().mapNotNull { tEl ->
                val t = tEl as? JsonObject ?: return@mapNotNull null
                val tData = (t["data"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
                OtkrKtItem(
                    index = (t["index"] as? JsonPrimitive)?.contentOrNull ?: "",
                    data = tData,
                    items = (t["items"] as? JsonArray).orEmpty().mapNotNull { kEl ->
                        val k = kEl as? JsonObject ?: return@mapNotNull null
                        val kData = (k["data"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
                        OtkrKrItem(index = (k["index"] as? JsonPrimitive)?.contentOrNull ?: "", data = kData)
                    },
                )
            },
        )
    }
}
