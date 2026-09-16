package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.realtime.ColdStartReconnectGrace
import cn.appia.im.core.realtime.RealtimeTransportPhase
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话串联编排（T11 DI 收口）：RN authStore.ts:71-89 applyPersistedAuthSession 三段的落地衔接——
 * 首帧判定（hasRestorableSession，MainActivity setContent 前同步调用）→ Main 进入即
 * bootstrap（bootstrapOnMainEntered，等价 RN MainNavigator.tsx:44-51；切库/REST hydrate/DDP
 * 冷启动重连内聚在 RealtimeSessionManager.bootstrap 步骤 1-3，此处不重复做）→
 * 会话失效（SessionExpiredBus）→ 登出（logout，UI 层订阅总线后导航回 Auth）。
 * 同时是 ChatListScreen 的会话面：登出/切组织/候选两段式/连接状态/手动重连（T11 主屏装配）。
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
        // RN authStore.ts:82 applyPersistedAuthSession：恢复会话即开冷启动 4s 横幅宽限
        //（markBegin 进程内一次；登录后进入 Main 的误开窗口内通常无本地房间，行为等价）
        ColdStartReconnectGrace.markColdStartReconnectBegin()
        scope.launch {
            bootstrapRealtime(session.serverUrl, session.token, session.user.id)
        }
    }

    /** 登录成功持久化（T5 账密/SMS 与 T6 CAS 的 onLoginSuccess 汇入点，RN authStore.login :98-107）。 */
    fun persistLogin(result: LoginResult, serverUrl: String) = auth.login(result, serverUrl)

    /**
     * 登出（登出按钮与 SessionExpired 同路径）；teardown 注入缝传 manager::teardown（T10 指针）。
     * 返回 false = 组织切换中豁免跳过（评审 Important-2）：总线收集器据此不导航。
     */
    fun logout(): Boolean = auth.logout(manager::teardown)

    /** 切组织（MineMenu 入口，T10 coordinator.switchTo）；失败上抛由 UI 告警。 */
    suspend fun switchOrg(targetServerUrl: String) = switchOrgImpl(targetServerUrl)

    /**
     * 切组织候选两段式状态（RN useLoginSwitchCandidates/MineMenu 数据面）：null = 本次刷新尚未给出值。
     * 段1（同步）：refreshOrgCandidates 触发即发缓存值（可 null，等价 RN useState 初始即读缓存）；
     * 段2（后台）：REST 会话就绪后拉取，成功回写缓存并发新值，失败/超时静默保持段1（RN catch{} 同）。
     */
    private val _orgCandidates = MutableStateFlow<List<LoginSwitchCandidate>?>(null)
    val orgCandidates: StateFlow<List<LoginSwitchCandidate>?> = _orgCandidates.asStateFlow()

    /**
     * 开「我的企业」弹层时触发（RN useLoginSwitchCandidates effect）。**不阻塞 UI**：M1 的
     * 「等 waitSdkRestLogin（≤15s）才显示列表」阻塞语义废除——UI collect orgCandidates 即时拿到
     * 段1 缓存值；就绪门保留在后台协程（RN 同：冷启动候选请求早于 REST 会话会抛 Not logged in）。
     */
    fun refreshOrgCandidates() {
        val session = restorableSession() ?: return
        _orgCandidates.value = orgList.readCache(session.user.username, session.serverUrl) // 段1：缓存即时
        scope.launch {
            if (!orgList.waitSdkRestLogin(session.token)) return@launch // 后台等待，超时放弃（RN :41-43 同）
            val fetched = try {
                orgList.fetchCandidates()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // 失败保持段1 缓存值（RN catch{} 吞掉）
            }
            if (fetched.isEmpty()) return@launch
            orgList.writeCache(session.user.username, fetched)
            _orgCandidates.value = fetched // 段2：REST 到后刷新
        }
    }

    /** 实时连接状态（T11 会话列表横幅同源，manager 订阅态直通）。 */
    val connectionUp: StateFlow<Boolean> get() = manager.connectionUp

    /** 传输层三态（M2 T5 连接横幅数据源；T11 ChatListScreen 挂 ConnectionBanner 时收集）。 */
    val phase: StateFlow<RealtimeTransportPhase> get() = manager.phase

    /**
     * 横幅「重试」的手动重连（RN requestManualRealtimeReconnect；T11 ChatListScreen 接线）：
     * 直通 bootstrap 同一 manager 单例，token 缺失时 no-op。
     */
    fun requestManualReconnect() = manager.requestManualReconnect()
}
