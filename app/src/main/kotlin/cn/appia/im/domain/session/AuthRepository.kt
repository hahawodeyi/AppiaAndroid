package cn.appia.im.domain.session

import android.content.Context
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.datastore.MmkvKvStore
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.buildAuthUserFromLogin
import cn.appia.im.core.network.rest.OrgSwitchState
import cn.appia.im.core.push.PushTokenRegistrar
import cn.appia.im.feature.login.AuthApi
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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

/** 应用级作用域兜底（评审 Important-3）：fire-and-forget 协程抛非取消异常只落日志，不崩进程。 */
private val backgroundScopeHandler = CoroutineExceptionHandler { _, e ->
    if (e is CancellationException) throw e
    android.util.Log.w("session", "uncaught coroutine failure in background scope", e)
}

/**
 * 会话编排基座（RN stores/authStore.ts 会话动作的原生等价）。
 * T5 的 onLoginSuccess 回调终点；T10 起承载 logout 完整清单。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val store: AuthSessionStore,
    private val push: PushTokenRegistrar,
    private val kv: KvStore,
    private val orgCache: OrgSessionCache,
    private val dbManager: DatabaseManager,
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
     * 登出完整清单（RN logout authStore.ts:138-169 逐条对照，顺序一致；**无 REST logout 调用**，RN 同）：
     * 组织切换中直接 return（:139-141）→ orgSessionByHost.clearAll（:143）→ 候选缓存清（:144-146）→
     * roomsUpdatedAt 游标清（:148-150）→ realtime teardown（:152）→ store 清（:157）→
     * 注销推送（:159-160，失败吞）→ resetDatabase(prevServer)（:161-165）→ 回落 prelogin 库（finally）。
     * 删库两步在 backgroundScope（IO）执行，不阻塞触发 logout 的主线程。
     * presence/权限/tab/RUM 等归 M5+。
     *
     * @param teardownRealtime RN teardownRealtimeSession 的注入缝（T11 接线传 manager::teardown）。
     * @return false = 组织切换中豁免跳过（RN :139-141），调用方（SessionExpired 收集器）据此不导航。
     */
    fun logout(teardownRealtime: () -> Unit = {}): Boolean {
        if (OrgSwitchState.isInProgress()) return false // RN :139-141
        val prev = store.load()
        val prevUsername = prev?.user?.username.orEmpty()
        val prevServer = prev?.serverUrl?.takeIf { it.isNotEmpty() }

        orgCache.clearAll() // RN :143
        LoginSwitchCandidatesCache(kv).clear(prevUsername) // RN :144-146
        prevServer?.let { RoomsSyncCursor(kv).clear(it) } // RN :148-150 clearRoomsUpdatedAt
        teardownRealtime() // RN :152
        store.clear() // RN :157
        prevServer?.let { server ->
            // RN :159-160 doUnregisterPushToken().catch(() => {})：Registrar 自吞网络失败，这里再兜一层
            backgroundScope.launch { runCatching { push.unregister(server) } }
        }
        // RN :161-165 删库 + .finally 回落占位库：整体下沉 backgroundScope（Dispatchers.IO）——
        // logout 由主线程触发（手动登出按钮 / SessionExpired 收集器），文件删除不落主线程（M2 前置收尾，
        // 总纲 §4.2-1）。删库期间 UI 已回 Auth 栈不触库；两步保持 RN 顺序（删库→回落，finally 语义）。
        backgroundScope.launch {
            prevServer?.let { server ->
                runCatching { dbManager.resetDatabase(dbManager.normalizeServer(server)) } // RN :161-165
            }
            // RN .finally(setActivePreloginDatabase)：无论是否删库都回落占位库
            dbManager.switchDatabase(DatabaseManager.PRELOGIN_NORMALIZED)
        }
        return true
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
    fun provideDatabaseManager(@ApplicationContext context: Context): DatabaseManager = DatabaseManager(context)

    /** T11 装配修复：补 @BackgroundScope 限定（否则图校验 MissingBinding——此前未经 assembleDebug 验证）。 */
    @Provides
    @Singleton
    @BackgroundScope
    fun provideBackgroundScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + backgroundScopeHandler)

    /**
     * T11 DI 收口：全 app 唯一 RocketSdk 实例 = AuthApi 的进程级 sdk（RN 单 sdk 语义——
     * 登录建连与 bootstrap 重连同实例；换成新实例会在登录路径留下一条不设监听的僵尸 DDP 连接）。
     */
    @Provides
    @Singleton
    fun provideRocketSdk(): RocketSdk = AuthApi.sdk

    /**
     * T8↔T9 预检裁定：syncInitial 构造注入 RoomsSyncRepository.sync（bootstrap 内部调用）。
     * repo 绑定的 server 在**调用时**现读会话（组织切换 applySession 已更新 store，
     * 随后的 bootstrap 同步新主体；repo 持有 sdk/db 引用，构建廉价）。
     */
    @Provides
    @Singleton
    fun provideRealtimeSessionManager(
        sdk: RocketSdk,
        dbManager: DatabaseManager,
        store: AuthSessionStore,
        kv: KvStore,
    ): RealtimeSessionManager {
        val syncInitial: suspend () -> Unit = {
            val serverUrl = store.load()?.serverUrl.orEmpty()
            RoomsSyncRepository(sdk, dbManager, kv, serverUrl).sync(RoomsSyncRepository.Mode.BOOTSTRAP)
        }
        return RealtimeSessionManager(sdk, dbManager, syncInitial)
    }

    /** bootstrap 缝带默认 lambda，Dagger 不绑函数类型默认值 → 显式 @Provides（T10）。 */
    @Provides
    @Singleton
    fun provideOrgSwitchCoordinator(
        sdk: RocketSdk,
        manager: RealtimeSessionManager,
        auth: AuthRepository,
        store: AuthSessionStore,
        orgCache: OrgSessionCache,
        dbManager: DatabaseManager,
    ): OrgSwitchCoordinator = OrgSwitchCoordinator(sdk, manager, auth, store, orgCache, dbManager)
}

