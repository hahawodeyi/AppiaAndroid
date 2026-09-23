package cn.appia.im.feature.roominfo.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.RoomAnnouncementFile
import cn.appia.im.core.chat.formatAnnouncementMessageForDisplay
import cn.appia.im.core.chat.parseMainAnnouncements
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.media.AttachmentUrlFormatter
import cn.appia.im.core.media.UploadApi
import cn.appia.im.core.messaging.parseMdJson
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomAnnouncementData
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.SaveRoomSettingsParams
import cn.appia.im.core.permissions.PermissionsStore
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.AttachmentNav
import cn.appia.im.feature.chat.ui.InlineEnv
import cn.appia.im.feature.chat.ui.MessageBody
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.roominfo.RoomInfoActions
import cn.appia.im.feature.roominfo.canEditRoomSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

// RN styles.ts 硬编码色
private val ScreenBg = Color(0xFFFAFAFA)
private val CardBorder = Color(0xFFE7E7E7)
private val CardWhite = Color.White
private val TitleBlack = Color(0xFF000000)
private val DeleteRed = Color(0xFFFF1B1B)
private val EditBlue = Color(0xFF1B5BFF)
private val EmptyGray = Color(0xFF86909C)
private val PrimaryBlue = Color(0xFF2878FF)
private val FileNameBlue = Color(0xFF5297FF)
private val UploadBtnBg = Color(0xFFF2F3F5)
private val UploadBtnText = Color(0xFF1D2129)
private val PublishBlue = Color(0xFF1677FF)
private val CancelGray = Color(0xFF86909C)
private val PlaceholderGray = Color(0xFFC9CDD4)

/** RN IMAGE_EXTS（index.tsx:36）。 */
private val IMAGE_EXTS = listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "apng")

/** RN isImageFile（index.tsx:38-41）：fileType ?? fileName ?? fileUrl 含图片后缀。 */
internal fun isAnnouncementImageFile(file: RoomAnnouncementFile): Boolean {
    val probe = file.fileType ?: file.fileName ?: file.fileUrl
    val lower = probe.lowercase()
    return IMAGE_EXTS.any { lower.contains(it) }
}

/** RN formatUpdateTime（index.tsx:43-47）：解析失败原文回退；本地化长格式。 */
internal fun formatAnnouncementUpdateTime(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    return try {
        val fmt = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
        )
        fmt.format(java.util.Date(raw))
    } catch (_: Exception) {
        raw
    }
}

/**
 * 公告屏（RN screens/RoomAnnouncementScreen/index.tsx）：
 * - 列表/编辑双态；进页 `rooms.info` 刷新回写 chats 公告两列（[RoomInfoActions.refreshRoomAnnouncements]，
 *   失败仍展示本地）；
 * - 发布/编辑 `saveRoomSettings {roomAnnouncementData}`（编辑带 _id）、删除 `{_id, type:'delete'}`
 *   （Alert 二钮确认）；编辑门 canEditRoomSettings（T2 组合）；
 * - 文件上传 announcement.bot 专用端点（直聊不可传——RN `roomType === 'd'` 双处门）；
 * - 渲染：md JSON AST 走 M3 MessageBody；纯文本 `formatAnnouncementMessageForDisplay` 剥标签显示；
 *   图片/文件复用 M3 附件链（AttachmentNav 路由由装配处接线）。
 */
@Composable
fun RoomAnnouncementScreen(
    rid: String,
    roomType: String,
    chat: ChatEntity?,
    currentUserId: String?,
    globalRoles: List<String>,
    serverUrl: String,
    token: String?,
    sdk: RocketSdk?,
    actions: RoomInfoActions?,
    onBack: () -> Unit,
    onAttachmentNav: (AttachmentNav) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDirect = roomType == "d"

    val permissions by PermissionsStore.permissions.collectAsState()
    val canEdit = remember(chat, permissions, globalRoles, isDirect) {
        !isDirect && canEditRoomSettings(chat, globalRoles, permissions)
    }

    // RN useEffect :70-83：进页 rooms.info 刷新（失败静默，仍展示本地已有）
    LaunchedEffect(rid) {
        actions?.refreshRoomAnnouncements(rid)
    }

    val announcements = remember(chat?.announcement, chat?.announcements) {
        parseMainAnnouncements(chat?.announcement, chat?.announcements)
    }

    var mode by remember { mutableStateOf("list") } // list | edit
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftMessage by remember { mutableStateOf("") }
    var draftFiles by remember { mutableStateOf<List<RoomAnnouncementFile>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<RoomAnnouncement?>(null) }

    val isDraftEmpty = draftMessage.isBlank() && draftFiles.isEmpty()

    fun cancelEdit() {
        mode = "list"
        editingId = null
        draftMessage = ""
        draftFiles = emptyList()
    }

    // RN handlePublish :109-128：编辑带 _id；空草稿提示不请求
    fun handlePublish() {
        if (isDraftEmpty) {
            alert = context.t("announcement_empty_toast")
            return
        }
        if (sdk == null) return
        saving = true
        scope.launch {
            try {
                RoomSettingsApi.postSaveRoomSettings(
                    sdk, rid,
                    SaveRoomSettingsParams(
                        roomAnnouncementData = RoomAnnouncementData(
                            id = editingId,
                            message = draftMessage,
                            // RN :117-119 无条件发 files: draftFiles（编辑清空附件须发 [] 覆盖）
                            files = draftFiles.map { f ->
                                cn.appia.im.core.network.api.RoomAnnouncementFile(f.fileName, f.fileUrl, f.fileType)
                            },
                        ),
                    ),
                )
                cancelEdit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alert = e.message ?: e.toString()
            } finally {
                saving = false
            }
        }
    }

    // RN handleDelete :130-155：Alert 确认后 {_id, type:'delete'}
    fun handleDelete(item: RoomAnnouncement) {
        if (item.id == null || sdk == null) return
        saving = true
        scope.launch {
            try {
                RoomSettingsApi.postSaveRoomSettings(
                    sdk, rid,
                    SaveRoomSettingsParams(
                        roomAnnouncementData = RoomAnnouncementData(id = item.id, type = "delete"),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alert = e.message ?: e.toString()
            } finally {
                saving = false
            }
        }
    }

    fun openFile(file: RoomAnnouncementFile) {
        val kind = announcementAttachmentKind(file)
        if (kind == AnnouncementKind.IMAGE || kind == AnnouncementKind.VIDEO) {
            val url = AttachmentUrlFormatter.format(
                file.fileUrl, currentUserId.orEmpty(), token.orEmpty(), serverUrl,
            )
            onAttachmentNav(AttachmentNav.Media(url, file.fileName, isAudio = false))
            return
        }
        onAttachmentNav(
            AttachmentNav.Doc(
                cn.appia.im.feature.chat.ui.buildDocPreviewParamsFromFileLink(
                    title = file.fileName,
                    fileLink = file.fileUrl,
                    fileUrl = null,
                    userId = currentUserId.orEmpty(),
                    token = token.orEmpty(),
                    server = serverUrl,
                ),
            ),
        )
    }

    fun uploadPicked(uri: Uri, fileName: String, mimeType: String, size: Long?) {
        if (sdk == null) return
        uploading = true
        scope.launch {
            try {
                if (size == 0L) throw IllegalStateException("empty file")
                val localPath = copyToUploads(context, uri, fileName)
                val fileUrl = UploadApi.uploadAnnouncementBot(sdk, localPath, fileName, mimeType)
                val fileType = fileName.split('.').lastOrNull()?.trim()
                draftFiles = draftFiles + RoomAnnouncementFile(fileName, fileUrl, fileType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                alert = context.t("announcement_upload_failed")
            } finally {
                uploading = false
            }
        }
    }

    val busy = saving || uploading

    Column(Modifier.fillMaxSize().background(ScreenBg)) {
        RoomHeader(
            title = context.t("announcement"),
            onBack = if (mode == "edit") ({ cancelEdit() }) else onBack,
            headerAction = if (mode == "edit" && canEdit) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).height(16.dp).width(16.dp))
                        Text(
                            context.t("announcement_publish"),
                            color = PublishBlue.copy(alpha = if (busy || isDraftEmpty) 0.4f else 1f),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .clickable(enabled = !busy && !isDraftEmpty, onClick = { handlePublish() })
                                .testTag("qa-announcement-publish"),
                        )
                    }
                }
            } else {
                null
            },
        )

        when {
            mode == "edit" -> AnnouncementEditPanel(
                draftMessage = draftMessage,
                onDraftMessage = { draftMessage = it },
                draftFiles = draftFiles,
                onRemoveFile = { index -> draftFiles = draftFiles.filterIndexed { i, _ -> i != index } },
                onOpenFile = { openFile(it) },
                canUpload = canEdit && !isDirect, // RN :378 直聊双门
                uploading = uploading,
                onPickFile = { uri, name, mime, size -> uploadPicked(uri, name, mime, size) },
                context = context,
            )

            announcements.isEmpty() -> AnnouncementEmpty(
                canEdit = canEdit,
                onAdd = {
                    editingId = null
                    draftMessage = ""
                    draftFiles = emptyList()
                    mode = "edit"
                },
                context = context,
            )

            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("qa-announcement-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(announcements, key = { i, item -> item.id ?: "ann-$i" }) { _, item ->
                    AnnouncementCard(
                        item = item,
                        canEdit = canEdit,
                        serverUrl = serverUrl,
                        currentUserId = currentUserId,
                        token = token,
                        onDelete = { deleteTarget = item },
                        onEdit = {
                            editingId = item.id
                            draftMessage = item.message.orEmpty()
                            draftFiles = item.files
                            mode = "edit"
                        },
                        onOpenFile = { openFile(it) },
                        onAttachmentNav = onAttachmentNav,
                        context = context,
                    )
                }
                item {
                    if (canEdit) {
                        Box(
                            Modifier
                                .padding(start = 30.dp, end = 30.dp, top = 20.dp, bottom = 20.dp)
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(PrimaryBlue)
                                .clickable {
                                    editingId = null
                                    draftMessage = ""
                                    draftFiles = emptyList()
                                    mode = "edit"
                                }
                                .testTag("qa-announcement-add-list"),
                            contentAlignment = Alignment.Center,
                        ) { AnnouncementBtnText(context.t("announcement_new")) }
                    }
                }
            }
        }
    }

    // 删除确认（RN Alert.alert 两钮）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.t("announcement_delete_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    handleDelete(target)
                }) { Text(context.t("announcement_delete"), color = DeleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(context.t("cancel")) }
            },
        )
    }

    alert?.let { msg ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(context.t("error_title")) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(context.t("common_close")) }
            },
        )
    }
}

// ── 子组件 ──

/** RN primaryBtnText：白 16。 */
@Composable
private fun AnnouncementBtnText(text: String) {
    Text(text, color = Color.White, fontSize = 16.sp)
}

/** RN renderEmpty :407-416。 */
@Composable
private fun AnnouncementEmpty(canEdit: Boolean, onAdd: () -> Unit, context: Context) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(context.t("announcement_empty"), color = EmptyGray, fontSize = 16.sp)
        if (canEdit) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(PrimaryBlue)
                    .clickable(onClick = onAdd)
                    .testTag("qa-announcement-add"),
                contentAlignment = Alignment.Center,
            ) { AnnouncementBtnText(context.t("announcement_add")) }
        }
    }
}

/** RN renderEdit :352-389：多行输入 + 草稿文件行（×删）+ 上传双钮（直聊隐藏）。 */
@Composable
private fun AnnouncementEditPanel(
    draftMessage: String,
    onDraftMessage: (String) -> Unit,
    draftFiles: List<RoomAnnouncementFile>,
    onRemoveFile: (Int) -> Unit,
    onOpenFile: (RoomAnnouncementFile) -> Unit,
    canUpload: Boolean,
    uploading: Boolean,
    onPickFile: (uri: Uri, name: String, mime: String, size: Long?) -> Unit,
    context: Context,
) {
    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val (name, mime, size) = queryUriMeta(context, uri, "application/octet-stream")
            onPickFile(uri, name, mime, size)
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val (name, mime, size) = queryUriMeta(context, uri, "image/jpeg")
            onPickFile(uri, name, mime, size)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CardWhite)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BasicTextField(
            value = draftMessage,
            onValueChange = onDraftMessage,
            textStyle = TextStyle(color = TitleBlack, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .testTag("qa-announcement-input"),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    inner()
                    if (draftMessage.isEmpty()) {
                        Text(context.t("announcement_placeholder"), color = PlaceholderGray, fontSize = 16.sp)
                    }
                }
            },
        )
        draftFiles.forEachIndexed { index, file ->
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().testTag("qa-announcement-draft-file-$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    file.fileName,
                    color = FileNameBlue,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenFile(file) },
                )
                Text(
                    "×",
                    color = CancelGray,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(4.dp)
                        .padding(start = 8.dp)
                        .clickable { onRemoveFile(index) },
                )
            }
        }
        if (canUpload) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                UploadBtn(context.t("announcement_upload_file"), uploading, "qa-announcement-upload-file") {
                    runCatching { docLauncher.launch(arrayOf("*/*")) }
                }
                UploadBtn(context.t("announcement_upload_photo"), uploading, "qa-announcement-upload-photo") {
                    runCatching {
                        photoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadBtn(label: String, uploading: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = UploadBtnText,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(UploadBtnBg)
            .clickable(enabled = !uploading, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
    )
}

/** RN renderAnnouncementCard :329-347：标题（发布人+时间）/正文/文件/操作行。 */
@Composable
private fun AnnouncementCard(
    item: RoomAnnouncement,
    canEdit: Boolean,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onOpenFile: (RoomAnnouncementFile) -> Unit,
    onAttachmentNav: (AttachmentNav) -> Unit,
    context: Context,
) {
    val env = remember(serverUrl) { InlineEnv(baseUrl = serverUrl) }
    Column(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(CardWhite)
            .padding(10.dp),
    ) {
        // RN renderCardHeader :260-267：u.name ?? u.username；announcement_published_by[_time]
        val username = (item.u?.get("name") as? JsonPrimitive)?.content
            ?: (item.u?.get("username") as? JsonPrimitive)?.content
        val time = formatAnnouncementUpdateTime(item.updateTime)
        val header = if (time.isNotEmpty()) {
            context.t("announcement_published_by_time")
                .replace("{{username}}", username.orEmpty())
                .replace("{{time}}", time)
        } else {
            context.t("announcement_published_by").replace("{{username}}", username.orEmpty())
        }
        Text(header, color = TitleBlack, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        // RN renderMessageBody :269-279：md JSON → Markdown；纯文本剥标签
        item.message?.takeIf { it.isNotEmpty() }?.let { message ->
            val md = remember(message) { parseMdJson(message) }
            if (md != null && md.blocks.isNotEmpty()) {
                MessageBody(root = md, env = env)
            } else {
                val plain = remember(message) {
                    formatAnnouncementMessageForDisplay(message)
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace("&nbsp;", " ")
                        .replace(Regex("<[^>]+>"), "")
                }
                if (plain.isNotEmpty()) Text(plain, color = TitleBlack, fontSize = 16.sp)
            }
        }

        // 图片行（复用 M3 网格语义：单图 200×150 / 多图 90 格）+ 文件卡
        val pictures = item.files.filter(::isAnnouncementImageFile)
        val otherFiles = item.files.filterNot(::isAnnouncementImageFile)
        if (pictures.isNotEmpty()) {
            AnnouncementImageRow(pictures, serverUrl, currentUserId, token, onOpenFile)
        }
        otherFiles.forEach { file -> AnnouncementFileCard(file, onOpenFile) }

        if (canEdit) {
            // RN cardActions：顶部分隔线 + 删/编两钮（marginHorizontal -10 的出血由父 padding 抵消）
            Column(Modifier.padding(top = 12.dp)) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(CardBorder))
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(onClick = onDelete)
                            .testTag("qa-announcement-delete"),
                        contentAlignment = Alignment.Center,
                    ) { Text(context.t("announcement_delete"), color = DeleteRed, fontSize = 14.sp) }
                    Box(Modifier.width(0.5.dp).fillMaxSize().background(CardBorder))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(onClick = onEdit)
                            .testTag("qa-announcement-edit"),
                        contentAlignment = Alignment.Center,
                    ) { Text(context.t("announcement_edit"), color = EditBlue, fontSize = 14.sp) }
                }
            }
        }
    }
}

/** 图片行：M3 MessageImageGrid 同款 90 方格（多图）/ 单图 Crop 适配（无尺寸信息用 200×150）。 */
@Composable
private fun AnnouncementImageRow(
    pictures: List<RoomAnnouncementFile>,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenFile: (RoomAnnouncementFile) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pictures.forEach { img ->
            val url = AttachmentUrlFormatter.format(
                img.fileUrl, currentUserId.orEmpty(), token.orEmpty(), serverUrl,
            )
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = img.fileName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .let { m -> if (pictures.size >= 2) m.height(90.dp).width(90.dp) else m.height(150.dp).width(200.dp) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE5E5E5))
                    .clickable { onOpenFile(img) },
            )
        }
    }
}

/** 文件卡：M3 MessageFileNode 同款（类型方块 + 名称）。 */
@Composable
private fun AnnouncementFileCard(file: RoomAnnouncementFile, onOpenFile: (RoomAnnouncementFile) -> Unit) {
    val fileInfo = cn.appia.im.feature.chat.ui.getFileInfo(file.fileName)
    Row(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpenFile(file) }
            .padding(10.dp)
            .testTag("qa-announcement-file"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(40.dp).width(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFC7DADD)),
            contentAlignment = Alignment.Center,
        ) {
            Text(fileInfo.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            file.fileName,
            Modifier.padding(start = 10.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 纯函数辅助（AnnouncementKind + uploads 拷贝）──

internal enum class AnnouncementKind { IMAGE, VIDEO, FILE }

/** RN getAnnouncementAttachmentKind（announcementAttachmentKind.ts）：ext 依次 fileType→fileName→fileUrl。 */
internal fun announcementAttachmentKind(file: RoomAnnouncementFile): AnnouncementKind {
    val extFrom = { value: String ->
        value.substringBefore("?").substringBefore("#").split('.').last().trim().lowercase()
    }
    val ext = extFrom(file.fileType.orEmpty()).ifEmpty { extFrom(file.fileName) }
        .ifEmpty { extFrom(file.fileUrl) }
    return when (ext) {
        in setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "psd", "svg", "tiff", "heic", "apng") ->
            AnnouncementKind.IMAGE
        in setOf("mp4", "mp3", "avi", "wmv", "mpg", "mpeg", "mov", "rm", "ram", "swf", "flv", "wma", "rmvb", "mkv", "webm") ->
            AnnouncementKind.VIDEO
        else -> AnnouncementKind.FILE
    }
}

/** content:// → cacheDir/uploads（RN copyToUploadsDir 的 Android 等价，PendingAttachments 同构）。 */
internal fun copyToUploads(context: Context, uri: Uri, name: String): String {
    val uploadsDir = java.io.File(context.cacheDir, "uploads")
    if (!uploadsDir.exists()) uploadsDir.mkdirs()
    val dest = java.io.File(uploadsDir, "${System.currentTimeMillis().toString(36)}-$name")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: throw java.io.IOException("openInputStream returned null: $uri")
    return dest.absolutePath
}

/** content:// 元信息（AttachmentSelector.querySelectedSources 同构；无名回退 file/photo_<ts>）。 */
internal fun queryUriMeta(context: Context, uri: Uri, fallbackMime: String): Triple<String, String, Long?> {
    val resolver = context.contentResolver
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    }.getOrNull()?.use { cursor ->
        if (cursor.moveToFirst()) {
            val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni)
            val si = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si)
        }
    }
    val resolvedName = name ?: if (fallbackMime.startsWith("image/")) {
        "photo_${System.currentTimeMillis()}.jpg"
    } else {
        "file"
    }
    return Triple(resolvedName, resolver.getType(uri) ?: fallbackMime, size)
}
