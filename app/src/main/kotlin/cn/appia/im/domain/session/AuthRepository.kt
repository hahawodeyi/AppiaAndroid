package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.MmkvKvStore
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.buildAuthUserFromLogin
import cn.appia.im.core.push.PushTokenRegistrar
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** 后台 fire-and-forget（推送注册/注销）用的应用级作用域。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackgroundScope

/**
 * 会话编排基座（RN stores/authStore.ts 会话动作的最小原生等价）。
 * T5 的 onLoginSuccess 回调终点；完整登出清单 T10 编排时并入 OrgSwitchCoordinator/RealtimeSessionManager。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val store: AuthSessionStore,
    private val push: PushTokenRegistrar,
    @BackgroundScope private val backgroundScope: CoroutineScope,
) {

    /**
     * RN login authStore.ts:98-107：三字段落库 + 注册推送（RN :104 注释——不阻塞登录流程）。
     * serverUrl 按 RN 原样存，normalize 在网络边界（RocketSdk/Registrar）做。
     */
    fun login(result: LoginResult, serverUrl: String) {
        store.save(
            AuthSession(
                token = result.authToken,
                user = buildAuthUserFromLogin(result.userId, result.me),
                serverUrl = serverUrl,
            ),
        )
        backgroundScope.launch { push.register(serverUrl, result.authToken, result.userId) }
    }

    /**
     * 启动恢复判定（RN merge isAuthenticated authStore.ts:182-189，同步、不 ping 服务器）。
     * 只读不副作用：RN applyPersistedAuthSession :74-88 的 sdk.hydrateRestSessionFromAuth 与
     * 冷启动重连归 T8/T11 接线时调用（RocketSdk.hydrateRestSession 已就绪）。
     */
    fun restore(): AuthSession? = store.load()

    /**
     * M1 最小登出（RN logout :138-168 的收敛子集）：store 清空 + 注销推送 fire-and-forget。
     * orgSessionByHost.clearAll（RN :143）、realtime teardown、presence/权限/本地库清理等
     * 完整清单 T10 编排时并入。无存量会话时不发注销（Registrar 也无 server 可寻址）。
     */
    fun logout() {
        val prev = store.load()
        store.clear()
        prev?.serverUrl?.takeIf { it.isNotEmpty() }?.let { server ->
            backgroundScope.launch { push.unregister(server) }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    /** RN orgSessionByHost.ts:3：独立 MMKV 实例 `org-session-by-host`。 */
    @Provides
    @Singleton
    fun provideOrgSessionCache(): OrgSessionCache = OrgSessionCache(MmkvKvStore(MMKV.mmkvWithID("org-session-by-host")))

    @Provides
    @Singleton
    fun provideBackgroundScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
