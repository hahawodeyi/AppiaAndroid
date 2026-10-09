package cn.appia.im.feature.settings.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.appia.im.core.i18n.t
import cn.appia.im.core.update.AppReleaseInstaller
import cn.appia.im.core.update.AppReleaseCheckController
import cn.appia.im.core.update.AppReleaseRow
import cn.appia.im.feature.search.interpolate
import cn.appia.im.feature.settings.AppUpdatePromptController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val HOST_TAG = "appUpdate"

/** RN versionDisplay：去 prerelease 段展示（`1.2.3-beta.1` → `1.2.3`）。 */
private fun versionDisplay(v: String): String = v.substringBefore('-').trim()

/**
 * 自更新弹窗（RN components/AppUpdateModal/index.tsx 移植）：
 * - 强制更新：无「暂不升级」且**返回键不可关**（坑 12：`dismissOnBackPress=false` +
 *   onDismissRequest 空操作，RN `onRequestClose={showLater ? onLater : undefined}` :83 等价）。
 * - 下载中：两按钮隐藏只留进度条（RN :108-118）。
 * - 视觉分歧：RN 顶部为 update.png 图幅头图（资源未随 AA 迁移），AA 用主题色头图盒子承载
 *   同文案（标题白字 22sp/版本行 14sp）；其余布局尺寸/按钮口径对齐 styles.ts。
 */
@Composable
fun AppUpdateModal(
    release: AppReleaseRow?,
    downloading: Boolean,
    progress: Float,
    onLater: () -> Unit,
    onUpgrade: (AppReleaseRow) -> Unit,
) {
    val current = release ?: return
    val context = LocalContext.current
    val showLater = !current.is_force_update
    val configuration = LocalConfiguration.current
    // RN :56 containerWidth：平板 0.425 / 手机 0.85 屏宽（isTablet = smallestScreenWidthDp>=600 同源）
    val containerWidth = (configuration.smallestScreenWidthDp * (if (configuration.smallestScreenWidthDp >= 600) 0.425f else 0.85f)).dp
    val downloadPercent = (progress.coerceIn(0f, 1f) * 100).roundToInt()

    Dialog(
        onDismissRequest = { if (showLater) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = showLater, // 坑 12：强制更新拦截返回键
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(shape = RoundedCornerShape(2.dp), color = Color.White) {
            Column(Modifier.width(containerWidth)) {
                // 头图区（RN update.png + 白字标题叠加 :92-98）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .background(Color(0xFF2F6BF6)),
                ) {
                    Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.Center) {
                        Text(
                            context.t("appupdate_overlaydiscover"),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            versionDisplay(current.version) + context.t("appupdate_overlayupgradesuffix"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                // 更新说明（RN ScrollView :100-105；空白文案兜底）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        context.t("appupdate_contentlabel"),
                        color = Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        current.notes.trim().ifEmpty { context.t("appupdate_notesempty") },
                        color = Color.Black,
                        fontSize = 16.sp,
                    )
                }
                // 按钮区（RN :107-144：下载中两按钮隐藏只留进度条）
                Column(Modifier.fillMaxWidth().padding(horizontal = 38.dp)) {
                    if (downloading) {
                        Column(
                            Modifier.fillMaxWidth().height(100.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .testTag("app-update-progress"),
                            )
                            Text(
                                interpolate(
                                    context.t("appupdate_downloading"),
                                    mapOf("percent" to downloadPercent.toString()),
                                ),
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .testTag("app-update-progress-text"),
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Button(
                            onClick = { onUpgrade(current) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("app-update-upgrade"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                        ) {
                            Text(context.t("appupdate_upgrade"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!downloading && showLater) {
                        TextButton(
                            onClick = onLater,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("app-update-later"),
                        ) {
                            Text(context.t("appupdate_later"), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Box(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 弹窗宿主（RN screens/AppUpdatePromptHost.tsx 的 Android 等价，单例挂 Main 路由）：
 * - 挂载清理旧版 APK/临时文件（RN :58-60）；
 * - T6 检查流 → 状态机（RN :77-94；enabled 登录门在 AppReleaseCheckController 内）；
 * - 立即升级 → 下载（进度回调）→ 系统安装器；失败告警（RN :105-127 catch → Alert）。
 * 登出收弹窗（RN :62-66 !enabled → hide）由 Main 路由卸载等价达成。
 * 手动检查入口：[AppUpdatePromptController.showManual]（T9 设置页接线）。
 */
@Composable
fun AppUpdatePromptHost(
    check: AppReleaseCheckController,
    prompt: AppUpdatePromptController,
    installer: AppReleaseInstaller,
    localVersion: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release by check.release.collectAsState()
    var progress by remember { mutableStateOf<Float?>(null) }
    var installFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { installer.cleanupInstalled(localVersion) } } // RN :58-60
    LaunchedEffect(release) { prompt.onReleaseRow(release) } // RN :77-94（静默/强制/新版本门在状态机内）

    val current by prompt.current.collectAsState()
    AppUpdateModal(
        release = current,
        downloading = progress != null,
        progress = progress ?: 0f,
        onLater = prompt::onLater,
        onUpgrade = { rel ->
            scope.launch {
                progress = 0f
                try {
                    installer.downloadAndInstall(rel) { p -> progress = p }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(HOST_TAG, "app release download/install failed", e)
                    installFailed = true
                } finally {
                    progress = null
                }
            }
        },
    )
    if (installFailed) { // RN Alert(t('settings_linkErrorTitle'), t('appUpdate_installFailed'))
        AlertDialog(
            onDismissRequest = { installFailed = false },
            title = { Text(context.t("settings_linkerrortitle")) },
            text = { Text(context.t("appupdate_installfailed")) },
            confirmButton = {
                TextButton(onClick = { installFailed = false }) { Text(context.t("common_close")) }
            },
        )
    }
}
