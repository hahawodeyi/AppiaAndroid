package cn.appia.im.feature.main.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.i18n.t
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.org.ui.OrgSwitchSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 占位 MainScreen（T11；M2 换 RoomList）：显示已登录主体/用户名 + 连接状态文本（M2 横幅同源）+
 * 我的企业（OrgSwitchSheet）与登出入口。进屏即 bootstrap（RN MainNavigator.tsx:44-51，异步不阻塞首帧）。
 * 文案复用既有 i18n key（服务器/用户名/在线/离线/我的企业/退出登录），M2 横幅再补专用 key。
 */
@Composable
fun MainScreen(
    gateway: SessionBootstrapOrchestrator,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    // 全屏级作用域：切组织点击路径必须挂这里——弹层关闭即离组合，局部 scope 会随组合销毁
    // 把进行中的 switchTo 自我取消（评审 Critical 修复；登出/失效总线同为全屏级生命周期）
    val scope = rememberCoroutineScope()

    // RN MainNavigator:44-51 进 Main 即 bootstrap（恢复会话/登录成功共用此入口）
    LaunchedEffect(Unit) { gateway.bootstrapOnMainEntered() }

    var session by remember { mutableStateOf(gateway.restorableSession()) }
    val connectionUp by gateway.connectionUp.collectAsState()
    var showOrgSheet by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<LoginSwitchCandidate>>(emptyList()) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text(session?.user?.name?.takeIf { it.isNotEmpty() } ?: session?.user?.username.orEmpty(),
            fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(context.t("memberprofile_online").takeIf { connectionUp } ?: context.t("memberprofile_offline"),
            fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(context.t("login_username"), fontSize = 12.sp)
            Text(session?.user?.username.orEmpty(), fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Text(context.t("settings_version_server"), fontSize = 12.sp)
            Text(session?.serverUrl.orEmpty(), fontSize = 15.sp)
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = { showOrgSheet = true }, modifier = Modifier.fillMaxWidth()) {
            Text(context.t("drawer_myenterprise"))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            // 与总线登出（SessionExpired 收集器）双触发是有意的幂等操作：
            // AuthRepository.logout 切换中直接 return、二次调用无副作用（RN :139-141 同）
            gateway.logout()
            onLogout()
        }, modifier = Modifier.fillMaxWidth()) {
            Text(context.t("profile_logout"))
        }
    }

    if (showOrgSheet) {
        // 候选加载：等 waitSdkRestLogin（≤15s）后 REST 刷新、失败/超时回缓存——**非** RN 的
        // 「缓存即时显示+异步刷新」两段式；离线时弹层最长 15s 空列表（ponytail：占位屏接受，
        // 开弹层时 bootstrap 通常已完成 hydrate 即刻命中；M2 换 MineMenu 改缓存先行、刷新后到更新）
        LaunchedEffect(Unit) { candidates = gateway.orgCandidates() }
        OrgSwitchSheet(
            candidates = candidates,
            currentServerUrl = session?.serverUrl.orEmpty(),
            onSwitch = { candidate ->
                showOrgSheet = false
                // launch 挂 MainScreen 的 scope（见上）：跨弹层关闭继续执行到 onSwitched/Alert
                scope.launch {
                    try {
                        gateway.switchOrg(candidate.appiaUrl)
                        session = gateway.restorableSession() // 切换成功刷新主体信息
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // RN MineMenuScreen 切换失败 Alert；M1 复用验证失败 key，M2 换 MineMenu 专用文案
                        alert = context.t("enterprise_verifyFailedTitle") to
                            (e.message ?: context.t("enterprise_verifyFailedUnknown"))
                    }
                }
            },
            onDismiss = { showOrgSheet = false },
        )
    }

    alert?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text(context.t("common_close")) } },
        )
    }
}
