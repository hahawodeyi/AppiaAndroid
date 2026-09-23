package cn.appia.im.feature.contacts.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.QrData
import cn.appia.im.core.network.api.fetchUserQrcodePayload
import cn.appia.im.feature.chat.ui.RoomHeader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 我的二维码名片（RN screens/MyCardScreen/index.tsx 逐结构）：
 * - 数据：qrcode.query 7 级回退（fetchUserQrcodePayload，QrcodeApi）
 * - 渲染：服务端返回的 imgUrl 本身就是二维码图片（data URI / http(s) URL——RN Image 直显），
 *   Android 同义为 BitmapFactory 位图化（data URI 免落盘，绕开 RN iOS 长 data: 落盘补丁；
 *   http(s) 拉取一次位图，展示与保存同源）。**未引入 ZXing 本地生成**：RN 全链路无本地
 *   生成——计划里「ZXing 生成」是对 RN 的误读（报告入册），zxing-core 依赖已移除。
 * - 保存：MediaStore 写 PNG（10+ 免权限；9- 运行时 WRITE_EXTERNAL_STORAGE，拒绝 →
 *   mycard_permissiondenied + 设置页引导——RN index.tsx:115-126 同）
 * - 入口：ProfileScreen 为 M5 域——本任务挂 ChatList 顶栏菜单（M5 迁正，报告说明）
 */

private val CardBg = Color(0xFFEEEFF1)
private val CardWhite = Color.White
private val DividerGray = Color(0xFFE5E6EB)
private val NameDark = Color(0xFF0D0E12)
private val ActionBlue = Color(0xFF2878FF)
private val ActionBlueDisabled = Color(0xFFB4D5FF)

private enum class QrFetchStatus { LOADING, IDLE, ERROR }

/** data:image/...;base64,xxx → 纯 base64（RN extractBase64PayloadFromImageUri 同义）。 */
internal fun extractBase64Payload(imgUrl: String): String? {
    val t = imgUrl.trim()
    val i = t.indexOf("base64,")
    val payload = if (i != -1) t.substring(i + "base64,".length) else t
    return payload.replace(Regex("\\s"), "").takeIf { it.isNotEmpty() }
}

/**
 * imgUrl → 位图：data URI 直接 base64 解码；http(s) 拉取。失败 → null（UI 落错误态）。
 * IO 调用方包 suspend。
 */
internal fun decodeQrBitmap(imgUrl: String): android.graphics.Bitmap? = try {
    when {
        imgUrl.startsWith("data:") ->
            extractBase64Payload(imgUrl)?.let { b64 ->
                runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        imgUrl.startsWith("http://") || imgUrl.startsWith("https://") ->
            OkHttpClient().newCall(Request.Builder().url(imgUrl).build()).execute().use { resp ->
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        else -> null
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
fun MyCardScreen(
    sdk: RocketSdk?,
    enterpriseName: String? = null,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    displayName: String,
    token: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var qr by remember { mutableStateOf<QrData?>(null) }
    var status by remember { mutableStateOf(QrFetchStatus.LOADING) }
    var saving by remember { mutableStateOf(false) }
    var saveAlert by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    // 位图化（展示与保存同源；RN qrDisplayUri effect 的 Android 等价）
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(qr) {
        qrBitmap = qr?.let { withContext(Dispatchers.Default) { decodeQrBitmap(it.imgUrl) } }
    }

    fun loadQr() {
        status = QrFetchStatus.LOADING
        qr = null
        if (sdk == null) {
            status = QrFetchStatus.ERROR
            return
        }
        scope.launch {
            val parsed = try {
                fetchUserQrcodePayload(sdk, currentUserId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            qr = parsed
            status = if (parsed?.imgUrl?.isNotEmpty() == true) QrFetchStatus.IDLE else QrFetchStatus.ERROR
        }
    }

    LaunchedEffect(sdk, currentUserId) { loadQr() }

    fun doSave() {
        if (qrBitmap == null) {
            saving = false
            return
        }
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { saveToAlbum(context, qrBitmap!!) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                saving = false
                if (saved) saveAlert = true
            }
        }
    }

    // Android 9- 运行时写权限（RN PermissionsAndroid.request 同义；10+ MediaStore 免申请）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) doSave() else {
            saving = false
            permissionDenied = true
        }
    }

    fun handleSave() {
        if (qr == null || saving) return
        saving = true
        if (Build.VERSION.SDK_INT < 29) {
            val granted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        doSave()
    }

    Column(Modifier.fillMaxSize().background(CardBg).testTag("qa-mycard")) {
        RoomHeader(title = context.t("mycard_title"), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            // ── 名片卡（RN cardWrapper：头像+名 / 虚线 / 二维码 / 企业名）──
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardWhite),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName.take(1).ifEmpty { "?" },
                            color = Color(0xFF6D6D72),
                            fontSize = 20.sp,
                        )
                        AsyncImage(
                            model = teamAvatarUrl(serverUrl, currentUsername.orEmpty(), currentUserId, token, 104),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        displayName,
                        color = NameDark,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }

                // 虚线分割（RN divider：半圆缺口 + dash line——Compose 无 dash Shape，实线+端点同色圆近似）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(CardBg))
                    Box(Modifier.weight(1f).height(1.dp).background(DividerGray))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(CardBg))
                }

                // 二维码区（RN qrContainer 240×240 圆角边框）
                Box(
                    Modifier
                        .padding(top = 26.dp)
                        .size(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardWhite)
                        .border(1.dp, DividerGray, RoundedCornerShape(8.dp))
                        .testTag("qa-mycard-qr"),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        status == QrFetchStatus.LOADING -> CircularProgressIndicator(color = ActionBlue)
                        status == QrFetchStatus.ERROR || qr == null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(context.t("mycard_qrloadfailed"), color = Color(0xFF8E8E93), fontSize = 13.sp)
                            Text(
                                context.t("mycard_retry"),
                                color = ActionBlue,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clickable { loadQr() }
                                    .testTag("qa-mycard-retry"),
                            )
                        }
                        qrBitmap != null -> Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                        // IDLE 但位图未就绪/解码失败（RN imageDecodeError 态：错误文案+重试）
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(context.t("mycard_qrloadfailed"), color = Color(0xFF8E8E93), fontSize = 13.sp)
                            Text(
                                context.t("mycard_retry"),
                                color = ActionBlue,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clickable { loadQr() }
                                    .testTag("qa-mycard-retry"),
                            )
                        }
                    }
                }

                // 底部企业名（RN companyLine：Enterprise_Name 两侧细线）
                enterpriseName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 20.dp, start = 24.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f).height(0.5.dp).background(DividerGray))
                        Text(
                            name,
                            color = Color(0xFF6D6D72),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Box(Modifier.weight(1f).height(0.5.dp).background(DividerGray))
                    }
                }
            }

            // ── 保存按钮（RN saveButton，loading 态蓝底白字同款）──
            val saveEnabled = status == QrFetchStatus.IDLE && qr != null && qrBitmap != null && !saving
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (saveEnabled) ActionBlue else ActionBlueDisabled)
                    .clickable(enabled = saveEnabled) { handleSave() }
                    .padding(vertical = 13.dp)
                    .testTag("qa-mycard-save"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    context.t("mycard_savetoalbum"),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // 保存成功（RN Alert.alert('', savedSuccess)——单按钮同 common_close）
    if (saveAlert) {
        AlertDialog(
            onDismissRequest = { saveAlert = false },
            text = { Text(context.t("mycard_savedsuccess")) },
            confirmButton = {
                TextButton(onClick = { saveAlert = false }) { Text(context.t("common_close")) }
            },
        )
    }
    // 权限拒绝（RN index.tsx:115-126：cancel + settings 两按钮）
    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            text = { Text(context.t("mycard_permissiondenied")) },
            confirmButton = {
                TextButton(onClick = {
                    permissionDenied = false
                    runCatching { // RN Linking.openSettings
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                }) { Text(context.t("settings_title")) }
            },
            dismissButton = {
                TextButton(onClick = { permissionDenied = false }) {
                    Text(context.t("settings_action_cancel"))
                }
            },
        )
    }
}

/**
 * 保存到相册（RN saveQrImageToCameraRoll 的 MediaStore 等价）：PNG 写
 * Pictures/（10+ RELATIVE_PATH；9- DATA 直写，权限由调用方先申请）。同步 IO，调用方包线程。
 */
internal fun saveToAlbum(context: Context, bitmap: android.graphics.Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "mycard_qr_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        } else {
            @Suppress("DEPRECATION")
            put(
                MediaStore.Images.Media.DATA,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    .resolve("mycard_qr_${System.currentTimeMillis()}.png").absolutePath,
            )
        }
    }
    return try {
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) return false
        } ?: return false
        true
    } catch (_: Exception) {
        false
    }
}
