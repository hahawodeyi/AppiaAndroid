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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
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
            gateway.logout()
            onLogout()
        }, modifier = Modifier.fillMaxWidth()) {
            Text(context.t("profile_logout"))
        }
    }

    if (showOrgSheet) {
        // 候选即取即显：缓存即时兜底 + REST 就绪后刷新（RN useLoginSwitchCandidates）
        LaunchedEffect(Unit) { candidates = gateway.orgCandidates() }
        OrgSwitchSheetHost(
            gateway = gateway,
            candidates = candidates,
            currentServerUrl = session?.serverUrl.orEmpty(),
            onSwitched = { session = gateway.restorableSession() },
            onFailed = { message ->
                // RN MineMenuScreen 切换失败 Alert；M1 复用验证失败 key，M2 换 MineMenu 专用文案
                alert = context.t("enterprise_verifyFailedTitle") to message
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

/** OrgSwitchSheet 挂载与 switchTo 编排（T10 接线点落地；失败告警、成功刷新主体信息）。 */
@Composable
private fun OrgSwitchSheetHost(
    gateway: SessionBootstrapOrchestrator,
    candidates: List<LoginSwitchCandidate>,
    currentServerUrl: String,
    onSwitched: () -> Unit,
    onFailed: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    OrgSwitchSheet(
        candidates = candidates,
        currentServerUrl = currentServerUrl,
        onSwitch = { candidate ->
            onDismiss()
            scope.launch {
                try {
                    gateway.switchOrg(candidate.appiaUrl)
                    onSwitched()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailed(e.message ?: context.t("enterprise_verifyFailedUnknown"))
                }
            }
        },
        onDismiss = onDismiss,
    )
}
