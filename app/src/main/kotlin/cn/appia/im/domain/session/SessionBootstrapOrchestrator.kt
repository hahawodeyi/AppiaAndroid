package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.network.LoginResult
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话串联编排（T11 DI 收口）：RN authStore.ts:71-89 applyPersistedAuthSession 三段的落地衔接——
 * 首帧判定（hasRestorableSession，MainActivity setContent 前同步调用）→ Main 进入即
 * bootstrap（bootstrapOnMainEntered，等价 RN MainNavigator.tsx:44-51；切库/REST hydrate/DDP
 * 冷启动重连内聚在 RealtimeSessionManager.bootstrap 步骤 1-3，此处不重复做）→
 * 会话失效（SessionExpiredBus）→ 登出（logout，UI 层订阅总线后导航回 Auth）。
 * 同时是占位 MainScreen 的会话面：登出/切组织/候选列表/连接状态（M2 换 RoomList 时收敛）。
 */
@Singleton
class SessionBootstrapOrchestrator @Inject constructor(
    private val store: AuthSessionStore,
    private val auth: AuthRepository,
    private val manager: RealtimeSessionManager,
    private val coordinator: OrgSwitchCoordinator,
    private val orgList: OrgListRepository,
    @BackgroundScope private val scope: CoroutineScope,
) {

    /** bootstrap 缝（T10 coordinator.bootstrap 同款）：单测注入记录器；生产默认 manager.bootstrap。 */
    internal var bootstrapRealtime: suspend (serverUrl: String, token: String, userId: String) -> Unit =
        { serverUrl, token, userId -> manager.bootstrap(serverUrl, token, userId) }

    /** switchOrg 缝（bootstrapRealtime 同款）：导航流测试注入记录器；生产默认 coordinator.switchTo。 */
    internal var switchOrgImpl: suspend (targetServerUrl: String) -> Unit =
        { targetServerUrl -> coordinator.switchTo(targetServerUrl) }

    /**
     * 可恢复会话（RN isAuthenticated authStore.ts:187 语义）：token+serverUrl 任一缺失即视为
     * 未登录（半残持久化不入 Main），不 ping 服务器。
     */
    fun restorableSession(): AuthSession? = store.load()
        ?.takeIf { it.token.isNotEmpty() && it.serverUrl.isNotEmpty() }

    /** 首帧判定：同步读 MMKV（进程内同步操作），MainActivity.setContent 前调用（RN RootNavigator:66-95 无闪屏）。 */
    fun hasRestorableSession(): Boolean = restorableSession() != null

    /** Main 进入即引导（RN MainNavigator:44-51）；异步不阻塞首帧渲染，重复进入由 manager 短路。 */
    fun bootstrapOnMainEntered() {
        val session = restorableSession() ?: return
        scope.launch {
            bootstrapRealtime(session.serverUrl, session.token, session.user.id)
        }
    }

    /** 登录成功持久化（T5 账密/SMS 与 T6 CAS 的 onLoginSuccess 汇入点，RN authStore.login :98-107）。 */
    fun persistLogin(result: LoginResult, serverUrl: String) = auth.login(result, serverUrl)

    /** 登出（登出按钮与 SessionExpired 同路径）；teardown 注入缝传 manager::teardown（T10 指针）。 */
    fun logout() = auth.logout(manager::teardown)

    /** 切组织（MineMenu 入口，T10 coordinator.switchTo）；失败上抛由 UI 告警。 */
    suspend fun switchOrg(targetServerUrl: String) = switchOrgImpl(targetServerUrl)

    /**
     * 切组织候选（RN useLoginSwitchCandidates 数据面）：缓存即时兜底，REST 就绪后网络刷新
     * （冷启动候选请求早于 REST 会话会抛 Not logged in，waitSdkRestLogin 门挡住）；刷新成功回写缓存。
     */
    suspend fun orgCandidates(): List<LoginSwitchCandidate> {
        val session = restorableSession() ?: return emptyList()
        val cached = orgList.readCache(session.user.username, session.serverUrl).orEmpty()
        if (!orgList.waitSdkRestLogin(session.token)) return cached
        val fetched = try {
            orgList.fetchCandidates()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (fetched.isEmpty()) return cached
        orgList.writeCache(session.user.username, fetched)
        return fetched
    }

    /** 实时连接状态（占位 MainScreen 状态文本；M2 会话列表横幅同源，manager 订阅态直通）。 */
    val connectionUp: StateFlow<Boolean> get() = manager.connectionUp
}
