package cn.appia.im.core.media

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.rest.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okio.BufferedSink
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * RN upload.ts:229 LocalFileInput：选择拷贝后的本地上传入参（localPath 已复制至 uploads 目录）。
 */
data class LocalFileInput(
    val name: String,
    val type: String,
    val size: Long? = null,
    val localPath: String,
)

/** rooms.upload 响应解析结果（RN uploadFileForOrchestrator 返回形态）。 */
data class UploadResult(val fileId: String, val messageId: String? = null)

/**
 * RN fileUploadProgress.ts：进度 pub/sub，keyed by 本地 messageId（tempId）。
 * StateFlow 形态等价 store+listeners——订阅即得当前值（无值 null），emit(null) 即清除。
 * 徽标环形进度数据源（MessageStatusBadge 等价，T11 组装）。
 */
object FileUploadProgress {
    data class Data(
        val totalFiles: Int,
        val completedFiles: Int,
        val currentFileProgress: Double, // 0..1
        val currentFileName: String? = null,
    )

    private val flows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Data?>>()

    /** RN emitProgress：p=null 清除（订阅者收到 null）。 */
    fun emit(messageId: String, p: Data?) {
        channel(messageId).value = p
    }

    /** RN subscribeProgress 的响应式形态：立即吐当前值。 */
    fun flow(messageId: String): StateFlow<Data?> = channel(messageId)

    private fun channel(messageId: String): MutableStateFlow<Data?> =
        flows.getOrPut(messageId) { MutableStateFlow(null) }

    /** 测试缝：单例跨用例残留清理。 */
    fun clear() = flows.clear()
}

/**
 * OkHttp 进度体（RNFetchBlob task.uploadProgress 等价）：按块写文件流，逐块回调
 * written/total（ratio ∈ [0,1]，由调用方做 total>0 守卫）。
 */
class ProgressRequestBody(
    private val file: File,
    contentType: String,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : okhttp3.RequestBody() {
    // 选择器给出的 mime 理论上合法，但破损值不该炸整条上传链（RN 不校验）——回退 octet-stream
    private val mediaType = runCatching { contentType.toMediaType() }
        .getOrDefault("application/octet-stream".toMediaType())

    override fun contentType() = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                sink.write(buf, 0, n)
                written += n
                onProgress(written, total)
            }
        }
    }
}

/**
 * 媒体上传 API（逐行为移植 appiaMobile/src/services/media/upload.ts 的 orchestrator 接入面）：
 * - [uploadFileForOrchestrator]：`POST /api/v1/rooms.upload/{rid}` multipart——单文件带
 *   `messageId(tempId)/ts/localPath` + 可选 `msg/md`；多文件逐个 `isMultiAttachment:true` 收集 fileId；
 * - [multiAttachments]：`POST multiAttachments {rid, fileIds, msg?, md?, messageId?}`（append 重试带 messageId）。
 *
 * 鉴权头走 M1 AuthInterceptor（RocketSdk 会话），进度经 [ProgressRequestBody] 由调用方回调。
 */
object UploadApi {

    /**
     * RN uploadFileForOrchestrator :273-369。`md` 仅在 `msg` 非空时上 wire（RN :310 同条件）；
     * 响应 fileId 取 `file._id` → `_id` → `message.file._id`/`message.files[0]._id`；
     * 单文件场景 fileId 可空（服务端已建消息）；多文件缺 fileId 直接抛（multiAttachments 必需）。
     * @param nowIso RN `new Date().toISOString()` 缝（测试注入固定值）
     */
    suspend fun uploadFileForOrchestrator(
        sdk: RocketSdk,
        rid: String,
        file: LocalFileInput,
        isMultiAttachment: Boolean = false,
        /** 单文件作幂等键（= 本地 tempId）；多文件不传。 */
        messageId: String? = null,
        msg: String? = null,
        md: JsonElement? = null,
        onProgress: ((ratio: Double) -> Unit)? = null,
        nowIso: () -> String = { Instant.now().toString() },
    ): UploadResult = withContext(Dispatchers.IO) {
        val source = File(file.localPath)
        val body = ProgressRequestBody(source, file.type) { written, total ->
            if (total > 0) onProgress?.invoke(written.toDouble() / total)
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, body)
        if (isMultiAttachment) {
            multipart.addFormDataPart("isMultiAttachment", "true")
        } else {
            multipart.addFormDataPart("messageId", messageId ?: Random.nextLong().toString(36))
            multipart.addFormDataPart("ts", nowIso())
            multipart.addFormDataPart("localPath", file.localPath)
            if (!msg.isNullOrEmpty()) multipart.addFormDataPart("msg", msg)
            if (!msg.isNullOrEmpty() && md != null) multipart.addFormDataPart("md", md.toString())
        }

        // RN :331-336 status <200 || >=400 抛 → M1 AuthInterceptor 非 2xx 已抛 ApiException
        // （带 status：5xx/IOException 重试、4xx 直败，与 RN isRetryableError(e) 同判定）
        val json = sdk.postMultipart("rooms.upload/$rid", multipart.build())
        parseUploadResponse(json, isMultiAttachment)
    }

    /**
     * RN SendOrchestrator.sendOneFileJob multi 分支 `sdk.post('multiAttachments', multiBody)`：
     * fileIds 合成 1 条消息；append 重试带 `messageId`（= 已存在的服务端消息 _id）。
     */
    suspend fun multiAttachments(
        sdk: RocketSdk,
        rid: String,
        fileIds: List<String>,
        msg: String? = null,
        md: JsonElement? = null,
        messageId: String? = null,
    ): JsonElement? = sdk.post("multiAttachments", buildJsonObject {
        put("rid", rid)
        put("fileIds", JsonArray(fileIds.map { JsonPrimitive(it) }))
        if (!msg.isNullOrEmpty()) put("msg", msg)
        if (md != null) put("md", md)
        if (messageId != null) put("messageId", messageId)
    })

    /** RN :431 `res?.messageId ?? res?.message?._id`；缺省 null（RN undefined）。 */
    fun multiAttachmentsServerId(res: JsonElement?): String? {
        val obj = res as? JsonObject ?: return null
        return obj.str("messageId") ?: (obj["message"] as? JsonObject)?.str("_id")
    }

    /** RN :339-368 响应解析。 */
    private fun parseUploadResponse(json: JsonElement, isMultiAttachment: Boolean): UploadResult {
        val obj = json as? JsonObject
        val message = obj?.get("message") as? JsonObject
        var fileId = (obj?.get("file") as? JsonObject).str("_id")
            ?: obj.str("_id")
        val messageId = message.str("_id")
        if (fileId == null && message != null) {
            // 单文件场景文件 _id 在 message.file._id 或 message.files[0]._id
            fileId = (message["file"] as? JsonObject).str("_id")
                ?: (message["files"] as? JsonArray)
                    ?.firstOrNull()?.let { it as? JsonObject }?.str("_id")
        }
        if (fileId == null) {
            if (!isMultiAttachment) return UploadResult(fileId = "", messageId = messageId)
            throw ApiException("[upload] response missing fileId")
        }
        return UploadResult(fileId = fileId, messageId = messageId)
    }

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
