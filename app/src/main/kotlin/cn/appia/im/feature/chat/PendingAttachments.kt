package cn.appia.im.feature.chat

import android.content.ContentResolver
import android.net.Uri
import cn.appia.im.core.media.LocalFileInput
import cn.appia.im.core.messaging.randomMessageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * RN types/pendingAttachment.ts：待发送附件的 prepare 状态（preparing→ready | prepare-failed；
 * unavailable 供持久化恢复校验，本任务选择链不产生）。
 */
enum class PrepareStatus { PREPARING, READY, PREPARE_FAILED, UNAVAILABLE }

/** RN PendingAttachment：附件条 UI 行（附件条缩略/删除的源数据）。 */
data class PendingAttachment(
    val id: String,
    val name: String,
    val type: String,
    val size: Long? = null,
    val sourceUri: String,
    val localPath: String? = null,
    val source: String, // photo | file | server
    val prepareStatus: PrepareStatus,
    /** 服务端 fileId（编辑回填水合行直用，replace 不重传；T12）。 */
    val fileId: String? = null,
)

/** 选择器产出（RN AttachmentResult asset/file 的展平形态）。 */
data class SelectedSource(
    val source: String, // photo | file
    val name: String,
    val type: String,
    val size: Long? = null,
    val uri: String,
)

/** RN usePendingAttachments add 返回：来源数 vs 容量截断后接受数（附件条超限提示数据源）。 */
data class AddResult(val sourceCount: Int, val acceptedCount: Int)

/**
 * RN upload.ts sanitizeUploadFileName / buildUniqueUploadStorageName 纯函数转录：
 * 非法字符→_、首字符 . 前补 _；存储名 `<ts36>-<rand36>-<base 截 120>.<小写 ext|bin>`。
 */
object UploadFileNamer {

    fun sanitize(name: String): String {
        val trimmed = name.trim().ifEmpty { "file" }
        val safe = trimmed.replace(Regex("[/\\\\?%*:|\"<>\\u0000-\\u001f]"), "_")
        return if (safe.startsWith(".")) "_$safe" else safe
    }

    fun uniqueStorageName(name: String, ts36: String, rand36: String): String {
        val safeName = sanitize(name)
        val extension = if (safeName.contains('.')) safeName.substringAfterLast('.').lowercase() else "bin"
        val extensionIndex = safeName.lastIndexOf('.')
        val rawBase = if (extensionIndex > 0) safeName.substring(0, extensionIndex) else safeName
        val base = rawBase.take(120).ifEmpty { "file" }
        return "$ts36-$rand36-$base.$extension"
    }
}

/**
 * RN usePendingAttachments.ts 的类形态（rid 维度一个实例，RoomScreen remember(rid) 持有）：
 * - [add]：容量截断（上限 [MAX_COUNT]）→ preparing 行立即可见 → 后台拷贝至
 *   `cacheDir/uploads/<ts>-<rand>-<name>`（RN DocumentsDirectoryPath 的 Android 等价：cacheDir，
 *   随应用卸载/存储清理回收，不进用户相册目录）→ ready / prepare-failed；
 * - generation 守卫：clear 后迟到的拷贝不回写（RN generationRef 同义）；
 * - [readyFiles]：仅 ready 项，供 enqueueFileMessage（Orchestrator 只接 localPath）。
 */
class PendingAttachments(
    private val uploadsDir: File,
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val maxCount: Int = MAX_COUNT,
    private val newId: () -> String = ::randomMessageId,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val random36: () -> String = { Random.nextLong().toString(36).take(10).padEnd(10, '0') },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _items = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val items: StateFlow<List<PendingAttachment>> = _items.asStateFlow()

    @Volatile
    private var generation = 0

    /** RN add :102-153。同步返回（拷贝后台进行，行先以 preparing 可见）。 */
    fun add(sources: List<SelectedSource>): AddResult {
        val capacity = maxOf(maxCount - _items.value.size, 0)
        val accepted = sources.take(capacity)
        if (accepted.isEmpty()) return AddResult(sourceCount = sources.size, acceptedCount = 0)

        val gen = generation
        val added = accepted.map { src ->
            PendingAttachment(
                id = newId(),
                name = src.name,
                type = src.type,
                size = src.size,
                sourceUri = src.uri,
                source = src.source,
                prepareStatus = PrepareStatus.PREPARING,
            )
        }
        _items.value = _items.value + added

        accepted.forEachIndexed { index, src ->
            scope.launch(ioDispatcher) {
                val copied = runCatching { copyToUploadsDir(src.uri, src.name) }.getOrNull()
                if (gen != generation) return@launch
                _items.update { list ->
                    list.map { item ->
                        if (item.id == added[index].id) {
                            item.copy(
                                localPath = copied,
                                prepareStatus = if (copied != null) PrepareStatus.READY else PrepareStatus.PREPARE_FAILED,
                            )
                        } else {
                            item
                        }
                    }
                }
            }
        }
        return AddResult(sourceCount = sources.size, acceptedCount = accepted.size)
    }

    /** RN remove :155-160。 */
    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    /** RN clear :185-188：代数推进，在途拷贝作废。 */
    fun clear() {
        generation += 1
        _items.value = emptyList()
    }

    /**
     * 整组替换（RN replacePendingAttachments 的 Android 等价；T12 编辑回填/退出编辑恢复）：
     * 代数推进作废在途拷贝，行直接就位（服务端水合行已 READY；恢复 stash 原样）。
     */
    fun hydrate(items: List<PendingAttachment>) {
        generation += 1
        _items.value = items
    }

    /** RN readyFiles :190-193：仅 ready 项转 LocalFileInput。 */
    val readyFiles: List<LocalFileInput>
        get() = _items.value
            .filter { it.prepareStatus == PrepareStatus.READY && it.localPath != null }
            .map { LocalFileInput(name = it.name, type = it.type, size = it.size, localPath = it.localPath!!) }

    val isPreparing: Boolean get() = _items.value.any { it.prepareStatus == PrepareStatus.PREPARING }

    val prepareFailedCount: Int get() = _items.value.count { it.prepareStatus == PrepareStatus.PREPARE_FAILED }

    /** RN copyToUploadsDir :100-116（content:// 直读；唯一名避开冲突，RN 存在性检查随之不必要）。 */
    private fun copyToUploadsDir(uri: String, name: String): String {
        if (!uploadsDir.exists()) uploadsDir.mkdirs()
        val dest = File(uploadsDir, UploadFileNamer.uniqueStorageName(name, clockMs().toString(36), random36()))
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("openInputStream returned null: $uri")
        return dest.absolutePath
    }

    companion object {
        /** RN PENDING_ATTACHMENTS_MAX_COUNT = 100。 */
        const val MAX_COUNT = 100
    }
}
