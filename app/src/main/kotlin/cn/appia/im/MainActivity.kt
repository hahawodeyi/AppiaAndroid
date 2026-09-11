package cn.appia.im

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import cn.appia.im.feature.login.ui.AuthWebScreen
import cn.appia.im.feature.login.ui.EnterpriseCodeScreen
import cn.appia.im.feature.login.verifyEnterprise
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Auth 图路由（T3 建立导航骨架；Main 占位留给 T11）。 */
@Serializable
data object EnterpriseCodeRoute

/**
 * RN LoginScreen 路由参数：verify 成功返回的可用服务器列表。
 * nav 内建 NavType 不支持 List<自定义类>，按绑定裁定#4 走 kotlinx JSON 串（type-safe nav 负责转义回环）。
 */
@Serializable
data class LoginRoute(val serversJson: String) {
    /** T5 消费入口：serversJson → List<CompanyServer>；坏参回落空列表（T5 换真 UI 时按需收紧）。 */
    fun servers(): List<CompanyServer> =
        runCatching { loginRouteJson.decodeFromString<List<CompanyServer>>(serversJson) }
            .getOrDefault(emptyList())
}

private val loginRouteJson = Json { ignoreUnknownKeys = true }

/** RN AuthWebScreen 路由参数：忘记密码 Web（url 直开）与 CAS SSO（authType='cas' + ssoToken）共用。 */
@Serializable
data class AuthWebRoute(
    val url: String,
    val title: String = "",
    val authType: String? = null,
    val ssoToken: String? = null,
)

@Serializable
data object MainRoute

/** 导航宿主：默认落 EnterpriseCode（RN AuthStack 首屏）；verify 参数化供 UI 测试注入 fake。 */
@Composable
fun AppiaNavHost(
    verify: suspend (String, String) -> VerifyEnterpriseResponse = ::verifyEnterprise,
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = EnterpriseCodeRoute) {
        composable<EnterpriseCodeRoute> {
            EnterpriseCodeScreen(verify = verify, onVerified = { servers ->
                // RN:75 navigation.replace：Login 顶掉 EnterpriseCode
                nav.navigate(LoginRoute(loginRouteJson.encodeToString(servers))) {
                    popUpTo<EnterpriseCodeRoute> { inclusive = true }
                }
            })
        }
        composable<LoginRoute> { entry ->
            // T5 LoginScreen 占位：展示收到的 servers 供走查/测试断言
            val route = entry.toRoute<LoginRoute>()
            val servers = route.servers()
            Text(
                LocalContext.current.t("feature_not_implemented") +
                    " servers=${servers.size} ${servers.firstOrNull()?.url.orEmpty()}",
            )
        }
        composable<AuthWebRoute> { entry ->
            val route = entry.toRoute<AuthWebRoute>()
            AuthWebScreen(
                url = route.url,
                title = route.title,
                authType = route.authType,
                ssoToken = route.ssoToken,
                // T5 接线点：CAS 命中 → AuthApi.login(server, creds) 走与账密/SMS 相同成功路径（回调参数化，此处仅壳）
                onSsoLogin = {},
                onBack = { nav.popBackStack() },
            )
        }
        composable<MainRoute> {
            Text(LocalContext.current.t("feature_not_implemented"))
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppiaTheme(isDark = isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppiaNavHost()
                }
            }
        }
    }
}
