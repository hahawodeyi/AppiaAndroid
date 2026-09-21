package cn.appia.im.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import coil3.compose.AsyncImage

/**
 * content:// → [SelectedSource]（PhotoPicker 与 OpenDocument 共用通道）：
 * OpenableColumns 取名/大小、contentResolver 取 mime；缺省回落 RN extractFileInfoList 默认值
 * （photo→image/jpeg + `photo_<ts>.jpg`；file→application/octet-stream + `file`）。无名条目跳过。
 */
fun querySelectedSources(context: Context, uris: List<Uri>, source: String): List<SelectedSource> {
    val resolver = context.contentResolver
    return uris.mapNotNull { uri ->
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val resolvedName = name ?: return@mapNotNull null
        SelectedSource(
            source = source,
            name = resolvedName,
            type = resolver.getType(uri)
                ?: if (source == SOURCE_PHOTO) "image/jpeg" else "application/octet-stream",
            size = size,
            uri = uri.toString(),
        )
    }
}

const val SOURCE_PHOTO = "photo"
const val SOURCE_FILE = "file"

/** RN ChatInputBar :715-718：容量截断（sourceCount > acceptedCount）→ chatinput_attachmentlimit toast。 */
private fun addWithLimitToast(pending: PendingAttachments, sources: List<SelectedSource>, context: Context) {
    val result = pending.add(sources)
    if (result.sourceCount > result.acceptedCount) {
        Toast.makeText(context, context.t("chatinput_attachmentlimit"), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 输入区 + 入口（RN AttachmentPanel 的最小入口形态，T11 组装完整面板）：
 * PhotoPicker 多选（图片/视频 mixed，RN launchImageLibrary mediaType mixed selectionLimit 0 对齐）
 * + OpenDocument 多选（RN DocumentPicker allFiles allowMultiSelection 对齐）；结果统一经
 * [querySelectedSources] → pending.add（容量截断 + 拷贝）。
 * maxItems 取 100（MediaStore.getPickImagesMaxLimit() 现行恒为 100，且该 API 带 R 扩展版本注解
 * ——不直接调用，规避 minSdk 24 的 NewApi；个别异常设备由 launch 处的 runCatching 兜底不崩）。
 */
@Composable
fun RoomAttachmentButton(pending: PendingAttachments) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PendingAttachments.MAX_COUNT),
    ) { uris ->
        if (uris.isNotEmpty()) addWithLimitToast(pending, querySelectedSources(context, uris, SOURCE_PHOTO), context)
    }
    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) addWithLimitToast(pending, querySelectedSources(context, uris, SOURCE_FILE), context)
    }

    Box {
        Text(
            "+",
            color = colors.tintColor,
            fontSize = 22.sp,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(40.dp)
                .clickable { menuOpen = true }
                .testTag("qa-room-attachment"),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.testTag("qa-room-attachment-panel"),
        ) {
            DropdownMenuItem(
                text = { Text(context.t("chatinput_photo")) },
                onClick = {
                    menuOpen = false
                    runCatching {
                        photoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    }
                },
                modifier = Modifier.testTag("qa-room-attachment-photo"),
            )
            DropdownMenuItem(
                text = { Text(context.t("chatinput_file")) },
                onClick = {
                    menuOpen = false
                    runCatching { docLauncher.launch(arrayOf("*/*")) }
                },
                modifier = Modifier.testTag("qa-room-attachment-file"),
            )
        }
    }
}

/**
 * 附件条（RN ChatInputBar SelectedAttachmentList 等价）：缩略（图片走 Coil）/名称/准备态/删除；
 * 挂 RoomScreen 输入区上方（T11 组装完整形态）。
 */
@Composable
fun SelectedAttachmentList(items: List<PendingAttachment>, onRemove: (String) -> Unit) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("qa-room-attachments"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.chatComponentBackground),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.type.startsWith("image/")) {
                    AsyncImage(
                        model = item.sourceUri,
                        contentDescription = item.name,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Text(
                    item.name,
                    color = colors.bodyText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .widthIn(max = 96.dp),
                )
                when (item.prepareStatus) {
                    PrepareStatus.PREPARING -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(12.dp),
                        strokeWidth = 2.dp,
                    )
                    PrepareStatus.PREPARE_FAILED -> Text(
                        context.t("chatinput_attachmentpreparefailed"),
                        color = colors.auxiliaryText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    else -> Unit
                }
                Text(
                    "×",
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clickable { onRemove(item.id) }
                        .testTag("qa-attachment-remove-${item.id}"),
                )
            }
        }
    }
}
