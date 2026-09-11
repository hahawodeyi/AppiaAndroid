package cn.appia.im.feature.login.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.appia.im.BuildConfig
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.DEFAULT_VERIFY_ENV_HOST
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import cn.appia.im.feature.login.verifyEnterprise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG_CODE_INPUT = "enterprise_code_input"
private const val TAG_ENV_HOST_INPUT = "enterprise_env_host_input"

/**
 * 企业码验证页（对照 RN EnterpriseCodeScreen）。
 * `verify`/`onVerified` 参数化：UI 测试注入 fake 不触网；导航串联在 MainActivity 的 NavHost。
 */
@Composable
fun EnterpriseCodeScreen(
    onVerified: (List<CompanyServer>) -> Unit,
    verify: suspend (String, String) -> VerifyEnterpriseResponse = ::verifyEnterprise,
) {
    val context = LocalContext.current
    val t: (String) -> String = { context.t(it) }
    var code by rememberSaveable { mutableStateOf("") }
    var envHost by rememberSaveable { mutableStateOf(DEFAULT_VERIFY_ENV_HOST) }
    var showEnvHost by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (loading) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            // RN:57-60 Alert(login_alertMissingTitle, enterprise_missingCode)
            alert = t("login_alertMissingTitle") to t("enterprise_missingCode")
            return
        }
        // 绑定裁定#3：envHost 空回落 DEFAULT（RN:61-64 为 missingHost alert，Android 按裁定收敛）
        val host = envHost.trim().ifEmpty { DEFAULT_VERIFY_ENV_HOST }
        // loading 提到 launch 前：同帧双击时第二击已能看到守卫（Minor 竞态修复）
        loading = true
        scope.launch {
            try {
                val resp = verify(host, trimmed)
                if (resp.pass) {
                    onVerified(resp.servers.orEmpty()) // RN:75 navigation.replace(LoginScreen, servers)
                } else {
                    // RN:78 失败分支：msg 缺省回落 verifyFailedUnknown
                    alert = t("enterprise_verifyFailedTitle") to (resp.msg ?: t("enterprise_verifyFailedUnknown"))
                }
            } catch (e: CancellationException) {
                throw e // 协程取消不是校验失败（Minor 统一修复：不吞 CancellationException）
            } catch (_: Exception) {
                alert = t("enterprise_verifyFailedTitle") to t("enterprise_verifyFailedUnknown")
            } finally {
                loading = false
            }
        }
    }

    AuthBrandedLayout(
        title = t("enterprise_welcome"),
        footer = {
            // RN:174-178 底部版本行
            VersionLine(t("enterprise_versionLine").replace("{{version}}", BuildConfig.VERSION_NAME))
        },
    ) {
        // RN:95-106 长按 label 开发后门（__DEV__ 等价 BuildConfig.DEBUG）；切换不改已输 code
        Text(
            text = t("enterprise_codeLabel"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    if (BuildConfig.DEBUG) {
                        showEnvHost = !showEnvHost
                        if (!showEnvHost) envHost = DEFAULT_VERIFY_ENV_HOST // RN:100-103 隐藏时重置
                    }
                })
            },
        )
        if (showEnvHost) {
            Text(t("enterprise_envHostLabel"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = envHost,
                onValueChange = { envHost = it },
                modifier = Modifier.fillMaxWidth().testTag(TAG_ENV_HOST_INPUT),
                singleLine = true,
                enabled = !loading,
            )
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().testTag(TAG_CODE_INPUT),
            singleLine = true,
            enabled = !loading,
            placeholder = { Text(t("enterprise_codePlaceholder")) },
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = { submit() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(t("enterprise_next"))
            }
        }
    }

    alert?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(t("common_close")) }
            },
        )
    }
}
