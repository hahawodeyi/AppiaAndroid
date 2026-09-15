package cn.appia.im

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.rest.SessionExpiredBus
import cn.appia.im.BuildConfig
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.login.AuthApi
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.LoginAreaCodeOption
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import cn.appia.im.feature.login.ui.AuthWebScreen
import cn.appia.im.feature.login.ui.EnterpriseCodeScreen
import cn.appia.im.feature.login.ui.LoginScreen
import cn.appia.im.feature.login.ui.LoginState
import cn.appia.im.feature.login.ui.AreaCodeScreen
import cn.appia.im.feature.login.ui.isLoginNetworkTimeoutError
import cn.appia.im.feature.login.ui.rememberLoginState
import cn.appia.im.feature.login.verifyEnterprise
import cn.appia.im.feature.main.ui.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Auth 图路由（T3 建立导航骨架）：Enterprise → Login → (AreaCode | AuthWeb) → Main。 */
@Serializable
data object EnterpriseCodeRoute

/**
 * RN LoginScreen 路由参数：verify 成功返回的可用服务器列表。
 * nav 内建 NavType 不支持 List<自定义类>，按绑定裁定#4 走 kotlinx JSON 串（type-safe nav 负责转义回环）。
 */
@Serializable
data class LoginRoute(val serversJson: String) {
    fun servers(): List<CompanyServer> =
        runCatching { loginRouteJson.decodeFromString<List<CompanyServer>>(serversJson) }
            .getOrDefault(emptyList())
}

private val loginRouteJson = Json { ignoreUnknownKeys = true }

/** 区号选择页路由参数（RN:189-196 navigate(AreaCodeScreen, {server})）。 */
@Serializable
data class AreaCodeRoute(val server: String)

/** RN AuthWebScreen 路由参数：忘记密码 Web（url 直开）与 CAS SSO（authType='cas' + ssoToken）共用；
 *  `server` 为发起页选中的企业服务器（RN 闭包捕获 selectedUrl 的等价物，CAS 成功回调用）。 */
@Serializable
data class AuthWebRoute(
    val url: String,
    val title: String = "",
    val authType: String? = null,
    val ssoToken: String? = null,
    val server: String = "",
)

@Serializable
data object MainRoute

/** LoginState 构造缝：仅导航流 UI 测试注入 fake deps（预设输入/ic 免触网）；生产恒 null 走默认。 */
private typealias LoginStateFactory =
    (servers: List<CompanyServer>, onLoginSuccess: (LoginResult, String) -> Unit) -> LoginState

/**
 * 导航宿主：默认落 EnterpriseCode（RN AuthStack 首屏）；verify 参数化供 UI 测试注入 fake。
 * `session` 为会话编排（T11：登录持久化/bootstrap/登出/切组织的挂接点）；`startAuthenticated`
 * 供 MainActivity 以同步恢复判定直落 Main（RN RootNavigator.tsx:66-95 首帧即定，无闪屏）。
 */
@Composable
fun AppiaNavHost(
    verify: suspend (String, String) -> VerifyEnterpriseResponse = ::verifyEnterprise,
    session: SessionBootstrapOrchestrator? = null,
    startAuthenticated: Boolean = false,
    loginState: LoginStateFactory? = null,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // CAS SSO 登录失败要落回登录页弹窗（RN runLogin 的 Alert；导航级状态跨屏传递）。
    // 一次性：LoginScreen 投递即消费（externalAlert 投递即清 + 本地暂存展示），导航往返不重显示
    var loginAlert by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 区号选择回调：type-safe nav 参数不携带 lambda，暂存导航级状态（RN 路由 params.onSelect 等价物）
    var areaCodeSelect by remember { mutableStateOf<((LoginAreaCodeOption) -> Unit)?>(null) }

    fun goMain() = nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
    fun goAuth() = nav.navigate(EnterpriseCodeRoute) { popUpTo(0) { inclusive = true } }

    /** 登录成功统一路径（账密/SMS onLoginSuccess 与 CAS runSsoLogin 汇入）：持久化 → Main。 */
    fun onLoginSuccess(result: LoginResult, server: String) {
        session?.persistLogin(result, server) // RN authStore.login authStore.ts:98-107（store+推送）
        goMain()
    }

    // 会话失效总线（REST 401 / DDP resume 失效）→ 登出 + 回 Auth（RN toast+logout 的 M1 等价：
    // 无 toast 基建直接回落；M5 补 auth_session_expired 提示，key 已备于 rest/ApiError.kt）。
    // 注册先于 NavHost 子级 effect：Main 内 bootstrap 触发的失效事件不丢（总线无 replay）。
    // session == null（纯 Auth 栈 UI 测试路径）整体跳过——与 onLoginSuccess 的 persistLogin 同一保护。
    // logout 仅在真正执行（非组织切换豁免，评审 Important-2）时才导航：切换中总线事件不甩回企业码页。
    LaunchedEffect(session) {
        SessionExpiredBus.events.collect {
            val gateway = session
            if (gateway != null && gateway.logout()) {
                // 与 MainScreen 手动登出双触发是有意的幂等操作（logout/teardown 均幂等）
                goAuth()
            }
        }
    }

    /** CAS 命中 → 与账密/SMS 同走登录成功路径（RN handleCasSsoLogin → runLogin）。 */
    fun runSsoLogin(server: String, ssoToken: String) {
        scope.launch {
            try {
                val result = AuthApi.login(server, LoginCredentials.Cas(ssoToken), AuthApi.LOGIN_TIMEOUT_MS)
                onLoginSuccess(result, server) // popUpTo(0) 清空 Auth 栈（含 AuthWeb），等价 RN goBack + 切 Main
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AuthWebScreen 的 Success 分支已自行 onBack()，此处不再 pop（避免与导航时序竞态误弹栈）；
                // 回到登录页弹告警（RN runLogin catch → Alert on LoginScreen）
                loginAlert = context.t("login_alertfailedtitle") to when {
                    isLoginNetworkTimeoutError(e) -> context.t("login_network_timeout")
                    e.message != null -> e.message!!
                    else -> context.t("login_alertfailedunknown")
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = if (startAuthenticated) MainRoute else EnterpriseCodeRoute) {
        composable<EnterpriseCodeRoute> {
            EnterpriseCodeScreen(verify = verify, onVerified = { servers ->
                // RN:75 navigation.replace：Login 顶掉 EnterpriseCode
                nav.navigate(LoginRoute(loginRouteJson.encodeToString(servers))) {
                    popUpTo<EnterpriseCodeRoute> { inclusive = true }
                }
            })
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            // 测试缝命中时 remember 固定（工厂携带预设输入/ fake deps）；生产走默认 rememberSaveable
            val state = if (loginState != null) {
                remember(route.serversJson) { loginState(route.servers(), ::onLoginSuccess) }
            } else {
                rememberLoginState(route.servers(), ::onLoginSuccess)
            }
            LoginScreen(
                servers = route.servers(),
                onLoginSuccess = ::onLoginSuccess,
                onMissingServers = {
                    nav.navigate(EnterpriseCodeRoute) { popUpTo(0) { inclusive = true } } // RN:147 replace
                },
                onOpenAreaCode = { server, onSelect ->
                    areaCodeSelect = onSelect
                    nav.navigate(AreaCodeRoute(server))
                },
                onOpenAuthWeb = { request ->
                    nav.navigate(
                        AuthWebRoute(
                            url = request.url,
                            title = request.title,
                            authType = request.authType,
                            ssoToken = request.ssoToken,
                            server = request.server,
                        ),
                    )
                },
                externalAlert = loginAlert,
                onConsumeExternalAlert = { loginAlert = null },
                state = state,
            )
        }
        composable<AreaCodeRoute> { entry ->
            AreaCodeScreen(
                server = entry.toRoute<AreaCodeRoute>().server,
                onSelect = { option ->
                    areaCodeSelect?.invoke(option) // RN onSelect(areaCode) 后 goBack
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<AuthWebRoute> { entry ->
            val route = entry.toRoute<AuthWebRoute>()
            AuthWebScreen(
                url = route.url,
                title = route.title,
                authType = route.authType,
                ssoToken = route.ssoToken,
                // T6 接线点：CAS 回调判定命中 → AuthApi.login → 同一成功路径 → Main（失败落回登录页告警）
                onSsoLogin = { runSsoLogin(route.server, it.credentialToken) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<MainRoute> {
            val gateway = session
            if (gateway == null) {
                // 无会话层注入（纯 Auth 栈 UI 测试）；不放企业 servers 明文
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                MainScreen(
                    gateway = gateway,
                    onLogout = { goAuth() }, // 登出 → 回企业码页（RN logout 后回 Auth 首屏）
                )
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionBootstrapOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首帧判定在 setContent 前完成（同步读 MMKV 持久化会话）：有会话直落 Main（RN
        // RootNavigator.tsx:66-95 首帧即定 Auth/Main，无闪屏）；Main 内再异步 bootstrap（RN 同构）
        val startAuthenticated = session.hasRestorableSession()
        setContent {
            AppiaTheme(isDark = isSystemInDarkTheme()) {
                // M2 前置收尾（总纲 §4.2-4）：testTag 以 resource-id 暴露给 UiAutomator/Maestro，
                // 仅 debug 包启用（Maestro appId cn.appia.im.debug）；release 语义树不携带该配置。
                val rootModifier = if (BuildConfig.DEBUG) {
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
                } else {
                    Modifier.fillMaxSize()
                }
                Surface(rootModifier) {
                    AppiaNavHost(session = session, startAuthenticated = startAuthenticated)
                }
            }
        }
    }
}
