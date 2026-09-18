package cn.appia.im.feature.chat

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.media.LocalFileInput
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RecallApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 编辑提交结果（RN handleSendFiles editing 分支的 {saved, uploadedFiles} 返回形态）。 */
data class EditSubmitResult(val saved: Boolean, val uploadedCount: Int = 0)

/**
 * 编辑消息提交（RN RoomScreen handleSend/handleSendFiles editing 分支）：
 * - 无附件变更：**绕过 SendOrchestrator** 直 DDP `updateMessage {rid,_id,msg,md}`
 *   （RN messages.ts:95-106；T11 的 [RecallApi.editMessage] 即该端点）；
 * - 附件变更（编辑态附件条非空）：全部文件按 UI 序上传（多附件模式）→
 *   REST `multiAttachments.replace {messageId,rid,fileIds,msg,md}` 整包覆盖。
 */
class MessageEditController(private val sdk: RocketSdk) {

    /**
     * [fileIds] 为 null 走 updateMessage（纯文本编辑，服务端不动附件）；
     * 非空（可空数组）走 multiAttachments.replace 整包覆盖（RN :700-736 同参数）。
     */
    suspend fun submit(
        rid: String,
        messageId: String,
        msg: String,
        md: JsonElement?,
        fileIds: List<String>? = null,
    ): EditSubmitResult {
        if (fileIds == null) {
            RecallApi.editMessage(sdk, rid, messageId, msg, md)
            return EditSubmitResult(saved = true)
        }
        UploadReplaceApi.replaceMultiAttachments(
            sdk = sdk,
            messageId = messageId,
            rid = rid,
            fileIds = fileIds,
            msg = msg,
            md = md,
        )
        return EditSubmitResult(saved = true)
    }
}

/** REST `multiAttachments.replace`（RN messages.ts replaceMultiAttachments :109-124 逐字段）。 */
object UploadReplaceApi {
    suspend fun replaceMultiAttachments(
        sdk: RocketSdk,
        messageId: String,
        rid: String,
        fileIds: List<String>,
        msg: String,
        md: JsonElement? = null,
    ): JsonElement = sdk.post("multiAttachments.replace", buildJsonObject {
        put("messageId", messageId)
        put("rid", rid)
        put("fileIds", JsonArray(fileIds.map(::JsonPrimitive)))
        put("msg", msg)
        if (md != null) put("md", md)
    })
}

/**
 * RN orderedAttachmentFileIds.ts buildOrderedAttachmentFileIds 逐条：fileId 已有直接用；
 * 否则须 ready + localPath 才上传；任一失败短路返回 [OrderedFileIdsResult.Failed]。
 */
sealed interface OrderedFileIdsResult {
    data class Ok(val fileIds: List<String>, val uploadedCount: Int) : OrderedFileIdsResult
    data class Failed(val failedItemId: String, val uploadedCount: Int) : OrderedFileIdsResult
}

suspend fun buildOrderedFileIds(
    items: List<PendingAttachment>,
    upload: suspend (LocalFileInput) -> String,
): OrderedFileIdsResult {
    val fileIds = mutableListOf<String>()
    var uploadedCount = 0
    for (item in items) {
        item.fileId?.let { fileIds += it; continue }
        if (item.prepareStatus != PrepareStatus.READY || item.localPath == null) {
            return OrderedFileIdsResult.Failed(failedItemId = item.id, uploadedCount = uploadedCount)
        }
        val fileId = runCatching {
            upload(LocalFileInput(name = item.name, type = item.type, size = item.size, localPath = item.localPath!!))
        }.getOrNull() ?: return OrderedFileIdsResult.Failed(failedItemId = item.id, uploadedCount = uploadedCount)
        fileIds += fileId
        uploadedCount++
    }
    return OrderedFileIdsResult.Ok(fileIds, uploadedCount)
}

/**
 * 编辑回填附件水合（RN serverMessageToEditableAttachments 的 files 列子集）：服务端 files
 * JSON `[{_id,name,type,size}]` → 带 fileId 的附件条行（source=server，无本地路径——replace
 * 时 fileId 直用不重传）。Android 现状不含 localAttachments 本地兜底形态（本地未落库即编辑的
 * 窗口不可达，发送链 SENDING 态拦编辑入口——RN 同为理论态）。
 */
fun serverMessageToEditableFiles(message: MessageEntity): List<PendingAttachment> {
    if (message.files.isNullOrEmpty()) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(message.files.orEmpty()) as? JsonArray }
        .getOrNull() ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        val fileId = str("_id") ?: return@mapNotNull null
        PendingAttachment(
            id = fileId,
            name = str("name") ?: "file",
            type = str("type") ?: "application/octet-stream",
            size = (o["size"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong(),
            sourceUri = "",
            source = "server",
            prepareStatus = PrepareStatus.READY,
            fileId = fileId,
        )
    }
}
