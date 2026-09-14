package cn.appia.im.domain.session

import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.datastore.OrgSessionCacheRow
import cn.appia.im.core.network.LoginMe
import cn.appia.im.core.network.LoginMeSettings
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ServerUrl
import cn.appia.im.core.network.rest.OrgSwitchState
import cn.appia.im.feature.login.AuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * RN services/auth/orgSwitchInProgress.ts 的终态载体：M0 的 OrgSwitchState（AtomicBoolean
 * begin/end/isInProgress）。401 豁免已在 M0 接好——AuthInterceptor 读同一 flag，本任务不改其接口。
 */
typealias OrgSwitchFlag = OrgSwitchState

/**
 * RN orgSwitchMutex.ts `withOrgSwitchMutex` 的等价：promise 链 → kotlinx Mutex（公平排队，
 * 串行执行语义一致）；操作包裹在 OrgSwitchFlag begin/end 中（RN :7-15 同），401 豁免随之生效。
 */
object OrgSwitchMutex {
    private val mutex = Mutex()

    suspend fun <T> withOrgSwitchMutex(operation: suspend () -> T): T = mutex.withLock {
        OrgSwitchFlag.begin()
        try {
            operation()
        } finally {
            OrgSwitchFlag.end()
        }
    }
}

/**
 * 多主体切换编排（逐行为移植 appiaMobile auth.ts:150-238 switchToOrgSession +
 * finishOrgSwitchAfterAuth.ts:32-54，绑定裁定#2 的完整序列）：
 *
 * ```
 * withOrgSwitchMutex                          （RN MineMenu 入口在 mutex 内串两段，原生收敛为一）
 *  ├─ 快照当前会话（finishOrgSwitch:13-28 语义，前移到清理之前——回滚要恢复的最小态）
 *  ├─ cleanup：manager.resetForOrgSwitch（generation++ 使在途 bootstrap/同步自行中止；
 *  │   RoomsSync 无独立取消钩子，generation 检查点即其取消等价；订阅标记清零即连接状态占位）
 *  ├─ OrgSessionCache.get(target) 命中 → connectTargetOrgSession（hydrate→prepare→resume，
 *  │   resume 失败且「socket not open」则 connect 后重试一次）；失败 clear(target) 落换票
 *  ├─ 未命中 → AuthApi.switchOrgLoginViaRest（三字段换票，30s 超时，不带鉴权头）→ 连接 → 写缓存
 *  ├─ applySession：AuthRepository.login 等价（store+推送）+ 切库（RN applyOrgSwitchSession）
 *  ├─ manager.bootstrap(target, token, userId)（RN connectAndResume:false——M1 绑定版无条件）
 *  └─ bootstrap 失败 → 回滚快照：恢复旧 store + 旧连接（不 logout，避免掉进企业验证码页）
 * ```
 */
class OrgSwitchCoordinator(
    private val sdk: RocketSdk,
    private val manager: RealtimeSessionManager,
    private val auth: AuthRepository,
    private val store: AuthSessionStore,
    private val orgCache: OrgSessionCache,
    private val dbManager: DatabaseManager,
    /** bootstrap 缝：默认 manager.bootstrap；测试注入抛错验证回滚路径。 */
    private val bootstrap: suspend (serverUrl: String, token: String, userId: String) -> Unit =
        { server, token, userId -> manager.bootstrap(server, token, userId) },
) {

    /** RN MineMenuScreen:96-137：整个切换在 mutex + flag 内；期间 401 不触发登出、logout 直接 return。 */
    suspend fun switchTo(targetServerUrl: String) = OrgSwitchMutex.withOrgSwitchMutex {
        val target = ServerUrl.normalizeServer(targetServerUrl)
        val snapshot = store.load()
            ?: throw IllegalStateException("[orgSwitch] no active session to switch from")
        val prev = ServerUrl.normalizeServer(snapshot.serverUrl)

        // cleanup（RN runClientStateCleanupBeforeOrgSwitch 的会话层等价）
        manager.resetForOrgSwitch()

        // 缓存命中路径（RN :185-207）
        var result: LoginResult? = null
        orgCache.get(target)?.let { cached ->
            result = try {
                connectTargetOrgSession(target, cached.token, cached.userId)
                buildCachedResult(cached)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                orgCache.clear(target) // RN :206：缓存路径失败 → 清行 → 落换票
                null
            }
        }

        // 换票路径（RN :209-231）
        if (result == null) {
            val d = AuthApi.switchOrgLoginViaRest(
                targetHost = target,
                userId = snapshot.user.id,
                userToken = snapshot.token,
                url = prev,
            )
            connectTargetOrgSession(target, d.authToken, d.userId)
            orgCache.set(
                target,
                OrgSessionCacheRow(
                    token = d.authToken,
                    userId = d.userId,
                    username = d.me?.username.orEmpty(),
                    name = d.me?.name,
                ),
            )
            result = d
        }

        // applySession + bootstrap + 回滚（RN finishOrgSwitchAfterAuth:32-54）
        val session = requireNotNull(result)
        try {
            applySession(target, session)
            bootstrap(target, session.authToken, session.userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rollback(snapshot)
            throw e
        }
    }

    // ---- 内部 ----

    /**
     * RN connectTargetOrgSession auth.ts:148-168：hydrate REST 会话 → prepareSocketConnection
     * （停监听→disconnect→initialize→wire→connect）→ resume；resume 失败且错误文本含
     * 「socket not open」→ connect 后重试一次，其余上抛。
     */
    private suspend fun connectTargetOrgSession(target: String, token: String, userId: String) {
        sdk.hydrateRestSession(target, token, userId)
        manager.prepareSocketConnection(target)
        try {
            sdk.resume(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains(SOCKET_NOT_OPEN) != true) throw e
            sdk.connect()
            sdk.resume(token)
        }
    }

    /** RN :191-198 缓存命中 payload：me.settings.preferences 恒空对象；name 缺省回落 username。 */
    private fun buildCachedResult(cached: OrgSessionCacheRow): LoginResult = LoginResult(
        authToken = cached.token,
        userId = sdk.currentUserId() ?: cached.userId, // RN :192 sdk.current?.userId ?? cached.userId
        me = LoginMe(
            username = cached.username,
            name = cached.name ?: cached.username.takeIf { it.isNotEmpty() },
            settings = LoginMeSettings(JsonObject(emptyMap())),
        ),
    )

    /** RN applyOrgSwitchSession：更新 auth（store+推送 fire-and-forget）并立即切换活跃本地库。 */
    private fun applySession(serverUrl: String, result: LoginResult) {
        auth.login(result, serverUrl)
        dbManager.switchDatabase(serverUrl)
    }

    /**
     * RN finishOrgSwitchAfterAuth:41-50 的回滚：恢复旧 store（applyOrgSwitchSession(previous)）
     * 并恢复旧连接（hydrate + 断开目标 socket 回连旧主体——绑定裁定#2「恢复旧 store/旧连接」）。
     * 回滚是尽力而为：各步 runCatching，原始终因错误向上抛；绝不 logout（RN 同）。
     */
    private suspend fun rollback(previous: AuthSession) {
        runCatching {
            applySession(
                previous.serverUrl,
                LoginResult(
                    authToken = previous.token,
                    userId = previous.user.id,
                    me = LoginMe(
                        username = previous.user.username,
                        name = previous.user.name,
                        settings = LoginMeSettings(previous.user.preferences ?: JsonObject(emptyMap())),
                    ),
                ),
            )
        }
        runCatching {
            connectTargetOrgSession(
                ServerUrl.normalizeServer(previous.serverUrl),
                previous.token,
                previous.user.id,
            )
        }
    }

    companion object {
        /** DdpClient 发送失败的既有错误文本（RN e.message.includes('socket not open')）。 */
        private const val SOCKET_NOT_OPEN = "socket not open"
    }
}
