package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 个人名片二维码（RN src/screens/MyCardScreen/fetchUserQrcode.ts + qrPayload.ts 逐级移植）：
 * - 7 级回退（fetchUserQrcode.ts:15-22 逐级）：GET 无参 → GET 空参 → method.call REST 无参 →
 *   method.call REST [userId] → DDP 无参 → DDP [userId] →（userId 缺省时两级 userId 尝试跳过）
 * - 解析（qrPayload.ts parseQrMethodResult）：{success,data} 剥壳（≤4 层）→ 数组元组
 *   [imgUrl, expire] → JSON 字符串再解（≤6 层）→ 字段名 10 键轮询（imgUrl/imgURL/imageUrl/
 *   url/qr/qrCode/base64/data/image/content）→ EJSON {$binary}/{base64} 叶子 → 纯 base64 补
 *   `data:image/png;base64,` 前缀
 */

/** RN QrData {imgUrl, expire}。 */
data class QrData(val imgUrl: String, val expire: Long = 0)

/** RN toQrImageUri（qrPayload.ts:7-13）：data:/http(s): 原样；其余按纯 base64 补 PNG data URI。 */
internal fun toQrImageUri(raw: String): String {
    val s = raw.trim()
    if (s.startsWith("data:") || s.startsWith("http://") || s.startsWith("https://")) return s
    return "data:image/png;base64,$s"
}

private val IMAGE_STRING_KEYS = listOf(
    "imgUrl", "imgURL", "imageUrl", "url", "qr", "qrCode", "base64", "data", "image", "content",
)

private fun asRecord(v: JsonElement?): JsonObject? =
    (v as? JsonObject)?.takeIf { it !is JsonNull }

/** RN extractLeafImageString（qrPayload.ts:42-49）：字符串 / {$binary} / {base64} 叶子。 */
private fun extractLeafImageString(v: JsonElement?): String? {
    if (v is JsonPrimitive && v !is JsonNull && v.isString && v.content.trim().isNotEmpty()) return v.content
    val r = asRecord(v) ?: return null
    (r["\$binary"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }
        ?.let { if (it.content.trim().isNotEmpty()) return it.content }
    (r["base64"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }
        ?.let { if (it.content.trim().isNotEmpty()) return it.content }
    return null
}

private fun pickImageString(o: JsonObject): String? {
    for (k in IMAGE_STRING_KEYS) {
        extractLeafImageString(o[k])?.let { return it }
    }
    return null
}

/** RN pickExpire（qrPayload.ts:15-23）：number 或数字串，NaN → 0。 */
private fun pickExpire(o: JsonObject): Long {
    val e = o["expire"] ?: return 0
    if (e is JsonPrimitive && e !is JsonNull) {
        e.contentOrNull?.toDoubleOrNull()?.let { return it.toLong() }
    }
    return 0
}

/** RN unwrapQrPayload（qrPayload.ts:60-68）：`{success:true, data}` 剥壳，≤4 层防无限递归。 */
internal fun unwrapQrPayload(raw: JsonElement, depth: Int = 0): JsonElement {
    if (depth > 4) return raw
    val o = asRecord(raw) ?: return raw
    val success = (o["success"] as? JsonPrimitive)?.contentOrNull == "true"
    val data = o["data"]
    if (success && data != null && data !is JsonNull) return unwrapQrPayload(data, depth + 1)
    return raw
}

/**
 * RN parseQrMethodResult（qrPayload.ts:73-133）：REST GET / method.call / DDP、字段名、
 * 嵌套、JSON 字符串、数组元组全形态解析；无图片字段 → null。
 */
fun parseQrMethodResult(raw: JsonElement?, depth: Int = 0): QrData? {
    if (depth > 6 || raw == null || raw is JsonNull) return null

    val unwrapped = unwrapQrPayload(raw)

    // 数组元组 [imgUrl, expire]（head 递归对象；tail 数字/数字串）
    if (unwrapped is JsonArray && unwrapped.isNotEmpty()) {
        val head = unwrapped[0]
        val tail = unwrapped.getOrNull(1)
        val headStr = extractLeafImageString(head)
        if (headStr != null && headStr.trim().isNotEmpty()) {
            val expire = when {
                tail is JsonPrimitive && tail !is JsonNull -> tail.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0
                else -> 0
            }
            return QrData(toQrImageUri(headStr), expire)
        }
        if (asRecord(head) != null) return parseQrMethodResult(head, depth + 1)
        return null
    }

    // 整串：JSON 对象/数组再解一层；否则按整段图片 payload
    if (unwrapped is JsonPrimitive && unwrapped.isString) {
        val s = unwrapped.content.trim()
        if (s.isEmpty()) return null
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
            val parsed = runCatching { Json.parseToJsonElement(s) }.getOrNull()
            if (parsed != null) return parseQrMethodResult(parsed, depth + 1)
        }
        return QrData(toQrImageUri(s))
    }

    val top = asRecord(unwrapped) ?: return null

    var candidate = pickImageString(top)
    var expire = pickExpire(top)

    if (candidate == null) {
        val nested = asRecord(top["result"]) ?: asRecord(top["data"])
        if (nested != null) {
            candidate = pickImageString(nested)
            if (expire == 0L) expire = pickExpire(nested)
        }
    }

    if (candidate == null || candidate.trim().isEmpty()) return null
    return QrData(toQrImageUri(candidate), expire)
}

/**
 * RN fetchUserQrcodePayload（fetchUserQrcode.ts:8-37）：7 级回退逐级尝试，
 * 任一级解析出 imgUrl 即返回；全败 → null。
 *
 * 通道映射（binding ②）：Android 无 DDP 直调必要——5/6 级（RN sdk.callMethod）按已有通道
 * 等价映射为「DDP transport 已开（会话 bootstrap 常态）才直调」，未连接即跳过
 * （等价 RN 未连 WebSocket 时 callMethod 抛错换下一级）。不预置 sdk.connect：
 * REST 前置级已覆盖常规部署，主动连 DDP 反而在纯 REST 网关上引入多余握手。
 */
suspend fun fetchUserQrcodePayload(sdk: RocketSdk, userId: String?): QrData? {
    suspend fun attempt(block: suspend () -> JsonElement?): QrData? = try {
        parseQrMethodResult(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null // 尝试下一数据源
    }

    // 1. GET 无参（RN sdk.get('qrcode.query')——无 params 时 REST 无 query string）
    attempt { sdk.get("qrcode.query") }?.let { return it }
    // 2. GET 空对象（RN sdk.get('qrcode.query', {})——encodeQuery({}) 产出空串，同 1；保留占位对齐 RN 次序）
    attempt { sdk.get("qrcode.query", emptyMap()) }?.let { return it }
    // 3. method.call REST 无参
    attempt { sdk.methodCall("qrcode.query") }?.let { return it }
    // 4. method.call REST [userId]（userId 缺省跳过）
    if (!userId.isNullOrEmpty()) {
        attempt { sdk.methodCall("qrcode.query", listOf(kotlinx.serialization.json.JsonPrimitive(userId))) }
            ?.let { return it }
    }
    // 5. DDP 直调无参（transport 已开才尝试——映射 RN sdk.callMethod）
    val ddp = sdk.ddp?.takeIf { it.isTransportOpen() }
    if (ddp != null) {
        attempt { ddp.callMethod("qrcode.query") }?.let { return it }
        // 6. DDP 直调 [userId]（userId 缺省跳过）
        if (!userId.isNullOrEmpty()) {
            attempt { ddp.callMethod("qrcode.query", kotlinx.serialization.json.JsonPrimitive(userId)) }
                ?.let { return it }
        }
    }
    return null
}
