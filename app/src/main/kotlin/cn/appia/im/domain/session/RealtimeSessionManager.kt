package cn.appia.im.domain.session

import android.util.Log
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ddp.DdpClient
import cn.appia.im.core.network.ddp.DdpException
import cn.appia.im.core.network.ddp.DdpMethodError
import cn.appia.im.core.network.ddp.Disposable
import cn.appia.im.core.network.rest.SessionExpiredBus
import cn.appia.im.core.realtime.RealtimeTransportPhase
import cn.appia.im.core.settings.ServerSettingRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 登录态实时会话编排（逐行为移植 appiaMobile/src/services/realtime/session.ts 的 M1 子集）：
 * bootstrap generation 引导 / DDP 重连恢复 / 会话失效识别 / teardown。
 *
 * 状态机（步骤编号 = RN session.ts 行号锚点）：
 * ```
 * bootstrap(server,token[,userId])                 close ──┐（未被 suppress 时）
 *   ├─ sessionKey 短路 / inflight 合并（:538-543）          ├─ needsPostReconnectResume = true
 *   ├─ generation 快照（:549）                             ├─ streamsSubscribed = false
 *   ├─ 1 hydrateRestSession（:556-566，userId 已知才做）    └─ sdk.checkAndReopen()——重连风暴防护在
 *   ├─ 2 switchDatabase（:568-569，REST 落库前）               DdpClient（tryReopen 去重），本层不重复排
 *   ├─ 3 ensureDdpResume（:315-344，!hasDdpUserId 才 resume） connected ──┐
 *   ├─ 4 generation 检查 → 中止（:575-577）                    ├─ scheduleFinalize（Mutex 串行，
 *   ├─ 5 syncInitial（:579-583，失败仅 warn 不阻断）           ▼  等价 finalizeTail promise 链）
 *   ├─ 6 generation 检查 → 中止（:585-587）              runFinalizeOnce（:173-186）:
 *   ├─ 7 presence 占位（:589-592，M5）                     needsResume → resume + 重订阅 6 条全局流；
 *   ├─ 8 sessionKey = key（:594）                          resume 失败且失效文本匹配（:96-105）→
 *   └─ 9 extras fire-and-forget（:596-629，M4/M5 补全）     SessionExpiredBus + 登出回调；失败回滚标记
 * teardown（:672-694 全清单）：取消在途 → 清态 → 停监听 → clearRestSession → disconnect；幂等可重入
 * ```
 * 并发语义（JS 单线程 → Kotlin 映射）：generation 计数用 AtomicLong；bootstrap 注册/合并用 Mutex
 * （RN 的 inflight promise 去重等价）；finalize 串行用 Mutex；handler 注册表与监听句柄表并发安全。
 */
/** 应用级作用域兜底（评审 Important-3）：fire-and-forget 协程抛非取消异常只落日志，不崩进程。 */
private val realtimeScopeHandler = CoroutineExceptionHandler { _, e ->
    if (e is CancellationException) throw e
    Log.w("realtime", "uncaught coroutine failure in RealtimeSessionManager scope", e)
}

class RealtimeSessionManager(
    private val sdk: RocketSdk,
    private val dbManager: DatabaseManager,
    /** 初始 REST 会话同步（T9 RoomsSyncRepository.sync；T11 串联注入）。失败仅 warn 不阻断（RN :579-583）。 */
    private val syncInitial: suspend () -> Unit,
    /**
     * 全局角色刷新（M5-T2，RN :625 syncCurrentUserRoles({force:true})）：bootstrap extras 最后一步，
     * 失败仅 warn 不阻断（RN :626-628 try/catch warn）。注入 RoleRefresher::refresh。
     */
    private val refreshUserRoles: suspend (force: Boolean) -> Unit = {},
    /** DDP 会话失效识别后的登出路径回调（RN :340 authStore.logout 的注入等价；T11 接 AuthRepository）。 */
    private val onSessionExpired: () -> Unit = {},
    /** App 级单例作用域（绑定裁定：SupervisorJob + Dispatchers.IO；单例无需 close）。 */
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + realtimeScopeHandler),
) {

    // ---- 状态（RN session.ts:41-72 模块级字段的实例等价）----

    private val stateLock = Any()
    private val bootstrapMutex = Mutex()

    /** RN :43 bootstrap 短路 key：`serverUrl\0token`。 */
    @Volatile
    private var sessionKey: String? = null

    @Volatile
    private var inflightKey: String? = null

    private var inflightJob: Deferred<Unit>? = null

    /** RN :47 换主体时递增，作废进行中的 bootstrap（避免过期 subscribe 失败触发回滚/登出）。 */
    private val bootstrapGeneration = AtomicLong(0)

    /** RN :55 换服 / teardown 时忽略 close，避免误标「断线」。 */
    @Volatile
    private var suppressTransportCloseUi = false

    /** RN :60 重连后需重新 resume + 全局订阅（DdpClient.tryReopen 只重建传输层）。 */
    @Volatile
    private var needsPostReconnectResume = false

    /**
     * RN :63 全局 DDP 流是否已成功 subscribe；断线 / teardown 清零。
     * StateFlow 承载（写路径原子）：T11 连接状态占位文本 / M2 横幅经 [connectionUp] 同源订阅。
     */
    private val connectionState = MutableStateFlow(false)

    private var authenticatedStreamsSubscribed: Boolean
        get() = connectionState.value
        set(value) { connectionState.value = value }

    /**
     * 传输层三态（M2 T5 横幅数据源，RN useRealtimeConnectionStore(s => s.phase) 等价；
     * realtimeConnectionStore 初始 'connected' 同口径）。变迁点全部对齐 RN session.ts：
     * 'connecting' 事件 :199-205（仅非 connected 才降级）、close :221（用 connecting 避免误报
     * 未连接）、finalize 成功 :180 / 失败 :184、markRealtimeServerDisconnected :79-85、
     * 手动重连 :661/:668、teardown :680。
     */
    private val phaseState = MutableStateFlow(RealtimeTransportPhase.CONNECTED)

    /** bootstrap 时点参数（RN 从 authStore 现读的等价；teardown 清空防已拆会话被复活）。 */
    @Volatile
    private var currentServerUrl: String? = null

    @Volatile
    private var currentToken: String? = null

    @Volatile
    private var lastUserId: String? = null

    /** 事件分发注册表（绑定裁定：本任务只分发记录，业务处理 M2/M5 接）；未注册 topic 默认 no-op。 */
    private val streamHandlers = ConcurrentHashMap<String, (JsonElement) -> Unit>()

    /** RN :42 streamStops：全部 Disposable 登记，stop 时统一摘除（防泄漏）。 */
    private val streamStops = ArrayList<Disposable>()

    @Volatile
    private var wiredBaseHandlers = false

    /** RN :165-171 finalizeTail promise 链的 Mutex 等价（重连恢复串行执行）。 */
    private val finalizeMutex = Mutex()
    private val finalizeJobs = CopyOnWriteArrayList<Job>()

    /** RN :66 ensureStreamsInflight 去重的 Mutex 等价。 */
    private val streamsMutex = Mutex()

    /**
     * 重连收尾挂点（M2 T6）：全局流重订成功后依次执行（RN resumeStreamsAfterSocketUp :156-163 尾部的
     * resubscribeAllActiveRoomStreams）。任一 tail 抛异常按 finalize 失败处理（phase 落 disconnected、
     * needsPostReconnectResume 置回，等下一轮 connected 重试）。
     */
    private val reconnectTails = CopyOnWriteArrayList<suspend () -> Unit>()

    /** teardown 挂点（M2 T6）：清房间流活跃表 / notify-user 待 flush 队列（RN :676-677 teardown 清单）。 */
    private val teardownHooks = CopyOnWriteArrayList<() -> Unit>()

    /** bootstrap extras fire-and-forget 句柄：teardown 取消（授权顺手项，防 M5 前幽灵同步）。 */
    @Volatile
    private var extrasJob: Job? = null

    /** wire 时的 DdpClient 实例（评审 Important-3）：hydrate 换服会重建实例，不同则必须 re-wire。 */
    @Volatile
    private var wiredClient: DdpClient? = null

    // ---- 公共 API ----

    /**
     * 登录态就绪后引导实时会话（RN bootstrapAuthenticatedRealtime session.ts:533-638，逐步骤对齐）。
     * 同 key 已完成 → 短路；同 key 在途 → 合并等待（:538-543）。DDP 未就绪不阻断（REST 仍可同步，
     * RN 走 disconnected 标记由房间页 focus 重试）；REST 同步失败仅 warn（:579-583）。
     * userId 等价 RN authStore.user?.id 守卫（:559）：非空才 hydrate REST 会话（server 变化会重建
     * DdpClient，由随后 resume 重连）。
     */
    suspend fun bootstrap(serverUrl: String, token: String, userId: String? = null) {
        val key = "$serverUrl\u0000$token"
        bootstrapMutex.withLock {
            if (sessionKey == key) return
            // 同 key 在途 → 合并等待（RN :540-542 await inflightPromise）。注册表清理挂在 body
            // 自身完成上（下方 invokeOnCompletion）：等待者被取消不清注册，故本分支可达且必须保留——
            // 否则孤儿 body 与后续同 key bootstrap 双跑（评审 Important-1 回归钉）。
            val running = inflightJob
            if (inflightKey == key && running != null) {
                try {
                    running.await() // Deferred：body 失败重抛给所有等待者（评审 Important-4，RN 同）
                } catch (e: CancellationException) {
                    // body 被 teardown 取消而本协程仍活跃 → 等价 RN 的静默返回；本协程自身取消 → 上抛
                    if (!currentCoroutineContext().isActive) throw e
                }
                return
            }

            val generationAtStart = bootstrapGeneration.get()
            val deferred = scope.async { runBootstrap(serverUrl, token, userId, key, generationAtStart) }
            inflightKey = key
            inflightJob = deferred
            // 清理与 body 生命周期绑定（评审 Important-1）：body 完成（含失败/取消）即清注册，
            // 等待者只是旁观者；仅清仍属于本次的（teardown 抢先清过则跳过）
            deferred.invokeOnCompletion {
                synchronized(stateLock) {
                    if (inflightJob === deferred) {
                        inflightKey = null
                        inflightJob = null
                    }
                }
            }
            try {
                deferred.await()
            } catch (e: CancellationException) {
                // body 被 teardown 取消而本协程仍活跃 → 静默返回；本协程自身取消 → 上抛
                if (!currentCoroutineContext().isActive) throw e
            }
        }
    }

    /**
     * 多主体切换前清空 bootstrap 短路状态（RN resetBootstrapSessionStateForOrgSwitch :644-651）：
     * generation 递增使在途 body 在步骤 4/6 检查处自行中止，随后对新主体的 bootstrap 必跑。
     * 换服断连由 prepareSocketConnection 的 sdk.disconnect 负责，此处不重复 teardown（RN 同）。
     */
    fun resetForOrgSwitch() {
        synchronized(stateLock) {
            // 递增与清理同锁（评审 Important-2）：与 body 的「检查+赋值」原子段互斥，交错必有序
            bootstrapGeneration.incrementAndGet()
            sessionKey = null
            inflightKey = null
            inflightJob = null // RN 置 null 不取消：在途 body 靠 generation 检查中止
        }
        authenticatedStreamsSubscribed = false
    }

    /**
     * RN prepareSocketConnection session.ts:262-276：停监听 → disconnect → initialize → 挂监听 → connect。
     * **收敛点（T4 预检裁定）**：AuthApi.login 未来复用此编排替换裸 initialize+connect（当前行为等价不必改）。
     * 不切库（RN :267-271 登录页 remount 语义）——换库由 bootstrap 步骤 2 负责。
     */
    suspend fun prepareSocketConnection(serverUrl: String) {
        suppressTransportCloseUi = true
        needsPostReconnectResume = false
        stopAllStreamListeners()
        sdk.disconnect()
        sdk.initialize(serverUrl)
        currentServerUrl = serverUrl
        suppressTransportCloseUi = false
        wireBaseHandlers()
        sdk.connect()
    }

    /**
     * RN teardownRealtimeSession session.ts:672-694 全清单等价：清 sessionKey/inflight、停 finalize、
     * 复位重连/订阅标记、停监听、clearRestSession、disconnect。幂等；teardown 后可重新 bootstrap。
     * - 在途 bootstrap 取消（原生加固，RN 的 JS promise 做不到）：防其后的 sdk.resume 复活已断开的会话
     * - 全局流不做显式 unsub（RN 同）：disconnect 即服务端退订，且 unsub 会隐式重连
     * - 房间流活跃表与 notify 持久化队列经 [teardownHooks] 清空（M2 T6 接线，RN :676-677）
     * - 不删库不登出（那是 T10）
     */
    fun teardown() {
        val inflight: Job?
        synchronized(stateLock) {
            // generation 一并递增（评审 Important-2）：在途 body 的「检查+赋值」已原子化，
            // teardown 靠递增使其后任何步骤 6 检查必不过，清理总能胜出不被写回
            bootstrapGeneration.incrementAndGet()
            sessionKey = null
            inflightKey = null
            inflight = inflightJob
            inflightJob = null
        }
        val pendingFinalize = finalizeJobs.toList()
        finalizeJobs.clear()
        inflight?.cancel()
        pendingFinalize.forEach { it.cancel() }
        extrasJob?.cancel()
        extrasJob = null

        suppressTransportCloseUi = true
        needsPostReconnectResume = false
        currentToken = null
        lastUserId = null
        // M2 T6 挂点：清活跃房间流表 / notify-user 待 flush 队列（RN :676-677 clearNotifyUserPersistenceQueue
        // + :683 unsubscribeAllRoomStreams 的本地态等价；网络退订不阻塞 teardown，disconnect 即服务端退订）
        teardownHooks.forEach { runCatching { it() } }
        stopAllStreamListeners()
        sdk.clearRestSession()
        sdk.disconnect()
        suppressTransportCloseUi = false
        // RN :680：teardown 落 connected（登出后不显示横幅；RN 同款口径）
        phaseState.value = RealtimeTransportPhase.CONNECTED
    }

    /**
     * 注册/替换某全局流 topic 的事件处理器（M2/M5 业务接入点）；传 null 移除。
     * 未注册 topic 到达即丢弃（默认 no-op）。
     *
     * 线程契约：handler 在 DDP IO 线程被调，业务方自行 hop 主线程（M2 起强制）。
     */
    fun setStreamHandler(topic: String, handler: ((JsonElement) -> Unit)?) {
        if (handler == null) streamHandlers.remove(topic) else streamHandlers[topic] = handler
    }

    /** 注册重连收尾 tail（M2 T6 房间流重订）：见 [reconnectTails]。 */
    fun addReconnectTail(tail: suspend () -> Unit) {
        reconnectTails.add(tail)
    }

    /** 注册 teardown 挂点（M2 T6）：teardown 内、停监听/断连之前同步执行（RN :676-677 同位）。 */
    fun addTeardownHook(hook: () -> Unit) {
        teardownHooks.add(hook)
    }

    // ---- bootstrap 主体 ----

    /** RN :551-629 的 inflight body；generationAtStart 快照下的每步中止检查见各锚点。 */
    private suspend fun runBootstrap(
        serverUrl: String,
        token: String,
        userId: String?,
        key: String,
        generationAtStart: Long,
    ) {
        if (bootstrapGeneration.get() != generationAtStart) return // RN :552-554

        currentServerUrl = serverUrl
        currentToken = token
        lastUserId = userId

        // 步骤 1：hydrate REST 会话（RN :556-566）
        if (userId != null) {
            sdk.hydrateRestSession(serverUrl, token, userId)
        }

        // 步骤 2：切库（RN :568-569 setActiveServerDatabase：幂等，且必须在 REST 落库前）
        dbManager.switchDatabase(serverUrl)

        // 步骤 3：DDP resume（RN startColdStartRealtimeConnect :282-313 → ensureDdpResumeForStreams）
        val ddpReady = ensureDdpResumeForStreams()

        // 步骤 4：generation 检查（RN :575-577）
        if (bootstrapGeneration.get() != generationAtStart) return

        if (ddpReady) {
            // 订阅全局流：RN :300-307 fire-and-forget，失败标记 disconnected（房间页 focus 可重试）。
            // 传入 body 的 generation 快照（评审 Important-1）：reset/teardown 不取消已发射协程，
            // 迟到的订阅协程在 ensure 内按 generation 中止，不 resume 旧 token、不清新会话订阅态
            scope.launch { ensureAuthenticatedStreamSubscriptions(generationAtStart) }
        }

        // 步骤 5：初始 REST 会话同步——失败仅 warn 不阻断（RN :579-583）
        try {
            syncInitial()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "initial room sync skipped or failed", e)
        }

        // 步骤 6：generation 检查（RN :585-587）
        if (bootstrapGeneration.get() != generationAtStart) return

        // 步骤 7：presence 预取（RN :589-592 requestUserPresence(selfId)——M5-T3 接入；
        // 2s 防抖内首次 flush，username/uid 均有效）
        lastUserId?.takeIf { it.isNotEmpty() }?.let { cn.appia.im.domain.presence.PresenceBatcher.requestUserPresence(it) }

        // 步骤 8：sessionKey 落定（RN :594）。检查+赋值与 reset/teardown 的清理同锁原子化
        // （评审 Important-2）：交错时必有一方整体先行，不会把已清的 sessionKey 写回
        synchronized(stateLock) {
            if (bootstrapGeneration.get() != generationAtStart) return
            sessionKey = key
        }
        // 步骤 9：后台 fire-and-forget 占位（RN :596-629，M5 补全）
        launchBootstrapExtras(generationAtStart)
    }

    /**
     * RN :596-629 后台 fire-and-forget（public settings / emojis / permissions / user roles）。
     * 句柄登记（授权顺手项）：teardown 取消，防 M5 前的幽灵同步残留。
     * T13：custom emojis 步实现（RN session.ts:609 syncCustomEmojis → emoji-custom.list →
     * DAO 整表替换；RoomScreen → MessageRow → InlineEnv / buildEditContent 查表消费）。
     * M4-T2：permissions 步实现（RN session.ts:617 syncPermissionsFromServer → permissions.listAll →
     * PermissionsStore；消费侧 hasRoomPermission/canEditRoomSettings 走 store + 默认映射兜底）。
     * M5-T2：user roles 步实现（RN session.ts:625 syncCurrentUserRoles({force:true}) → RoleRefresher）。
     */
    private fun launchBootstrapExtras(generationAtStart: Long) {
        extrasJob = scope.launch {
            for (step in listOf("public settings", "custom emojis", "permissions", "user roles")) {
                if (bootstrapGeneration.get() != generationAtStart) return@launch
                when (step) {
                    "public settings" -> syncPublicSettings()
                    "custom emojis" -> syncCustomEmojis()
                    "permissions" -> syncPermissions()
                    "user roles" -> try {
                        refreshUserRoles(true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "user roles sync skipped or failed", e)
                    }
                }
            }
        }
    }

    /**
     * RN session.ts:600-604 syncPublicSettingsFromRegistry（M5-T1；失败仅 warn 不阻断，RN 同）。
     * settings 行写入当前 active 库（切库先于 extras，RN 同序）。
     */
    private suspend fun syncPublicSettings() {
        try {
            cn.appia.im.core.network.api.SettingsPublicApi.syncPublicSettings(
                sdk,
                upsertAll = { rows -> dbManager.active.settingDao().upsertAll(rows) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "public settings sync skipped or failed", e)
        }
    }

    /**
     * `stream-notify-all` public-settings-changed 帧消费（RN publicSettingsStream.ts
     * handlePublicSettingsChangedMessage 逐行）：eventName 含 `public-settings-changed` →
     * args[1] = `{_id, value}` → prepare → 单条 upsert。未注册 id / 坏帧静默丢弃（RN 同）。
     *
     * 接入：SessionModule 经 [setStreamHandler] 注册（permissions 先例）；handler 在 DDP IO
     * 线程被调，upsert 挂起 → 入 scope 异步落库（RN `.catch(() => undefined)` 同义）。
     */
    fun handlePublicSettingsChanged(ddpMessage: JsonElement) {
        try {
            val fields = (ddpMessage as? JsonObject)?.get("fields") as? JsonObject ?: return
            val eventName = fields.str("eventName") ?: return
            if (!PUBLIC_SETTINGS_CHANGED.containsMatchIn(eventName)) return
            val args = fields["args"] as? JsonArray ?: return
            if (args.size < 2) return
            val payload = args[1] as? JsonObject ?: return
            val id = payload.str("_id") ?: return
            val prepared = ServerSettingRegistry.prepareSettingEntity(id, payload["value"]) ?: return
            scope.launch {
                runCatching { dbManager.active.settingDao().upsert(prepared) }
                    .onFailure { Log.w(TAG, "[realtime] upsert public setting failed id=$id", it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[realtime] public-settings-changed handler", e)
        }
    }

    /** RN session.ts:617 syncPermissionsFromServer（M4-T2；失败仅 warn 不阻断，RN :618-620 同）。 */
    private suspend fun syncPermissions() {
        try {
            cn.appia.im.core.network.api.PermissionsApi.syncPermissions(sdk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "permissions sync skipped or failed", e)
        }
    }

    /**
     * `stream-user-presence` 帧消费（RN session.ts:236-246 逐行）：[PresenceStreamParser.parse]
     * 索引映射 → [PresenceStore.setUserStatus]。坏帧静默丢弃（RN 同）。
     *
     * 接入：SessionModule 经 [setStreamHandler] 注册（permissions/public-settings 先例）；
     * 订阅本身由 PresenceBatcher flush 内 subscribeRaw 增量 added（RN batchRequestPresence :47，
     * **不在 subscribeAuthenticatedStreams 六条全局流内**——按需订阅）。
     */
    fun handleStreamUserPresence(ddpMessage: JsonElement) {
        val parsed = cn.appia.im.domain.presence.PresenceStreamParser.parse(ddpMessage) ?: return
        cn.appia.im.domain.presence.PresenceStore.setUserStatus(
            parsed.userId,
            cn.appia.im.domain.presence.PresenceStore.ActiveUserEntry(parsed.status, parsed.statusText),
        )
    }

    /** RN syncCustomEmojis（services/emoji/syncCustomEmojis.ts）：GET emoji-custom.list →
     * 整表替换（setCustomEmojis 语义；总纲 §4.4-2）——服务端删的表情不永驻。
     * 单次事务 replaceAll：observe() 不闪断（见 DAO KDoc）。
     */
    private suspend fun syncCustomEmojis() {
        try {
            val res = sdk.get("emoji-custom.list") as? JsonObject ?: return
            if (res["success"]?.jsonPrimitive?.booleanOrNull != true) return
            val update = ((res["emojis"] as? JsonObject)?.get("update") as? JsonArray) ?: return
            val dao = dbManager.active.customEmojiDao()
            val entities = update.mapNotNull { el ->
                val emoji = el as? JsonObject ?: return@mapNotNull null
                val name = emoji["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val extension = emoji["extension"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val aliases = (emoji["aliases"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(it) }
                cn.appia.im.core.database.entity.CustomEmojiEntity(
                    name = name,
                    aliases = aliases,
                    extension = extension,
                    // $date 为 ms epoch；RN Watermelon 存原 number，本表列语义取秒（整表替换无需幂等）
                    _updated_at = (emoji["_updatedAt"]?.let { (it as? JsonObject)?.get("\$date")?.jsonPrimitive?.doubleOrNull }
                        ?: System.currentTimeMillis().toDouble()) / 1000.0,
                )
            }
            dao.replaceAll(entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync custom emojis failed", e) // RN console.error 不阻断
        }
    }

    // ---- DDP resume 与订阅 ----

    /**
     * RN ensureDdpResumeForStreams session.ts:315-344：监听未挂先 prepareSocketConnection，否则
     * connect；!hasDdpUserId 才 resume。失败 warn；失效文本匹配 → SessionExpiredBus + 登出回调
     * （RN :338-341 的 toast + logout：toast 归 UI 层，经 SessionExpiredBus）。
     */
    private suspend fun ensureDdpResumeForStreams(): Boolean {
        val server = currentServerUrl
        val token = currentToken
        if (server.isNullOrEmpty() || token.isNullOrEmpty()) return false // RN :318-322 auth 守卫
        return try {
            // wired 判定带 DdpClient 实例同一性（评审 Important-3）：hydrate 换服重建实例后
            // 旧监听全部失效，实例不同必须走 prepareSocketConnection 重新 wire
            if (!wiredBaseHandlers || sdk.ddp !== wiredClient) {
                prepareSocketConnection(server)
            } else {
                sdk.connect()
            }
            if (!sdk.hasDdpUserId()) {
                sdk.resume(token)
            }
            sdk.hasDdpUserId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ensure DDP resume for streams failed", e)
            if (isSessionInvalidatedResumeError(e)) {
                SessionExpiredBus.emit()
                onSessionExpired()
            }
            false
        }
    }

    /**
     * RN ensureAuthenticatedStreamSubscriptions session.ts:349-384：已成功订阅且 DDP 活着 → 短路；
     * 否则保证 resume 后重订阅。并发经 streamsMutex 串行（RN inflight promise 去重等价）。
     *
     * [generationAtStart]（评审 Important-1）：调用方快照的 bootstrap generation。进入锁后与每次
     * 落态前复查——reset/teardown 递增后，迟到的协程不订阅、不 resume 旧 token、不写订阅态
     * （与 bootstrap body 步骤 4/6 的中止检查同模式）。
     */
    private suspend fun ensureAuthenticatedStreamSubscriptions(generationAtStart: Long): Boolean =
        streamsMutex.withLock {
            if (bootstrapGeneration.get() != generationAtStart) return@withLock false
            if (authenticatedStreamsSubscribed) {
                if (sdk.hasDdpUserId() && sdk.ddp?.isTransportOpen() == true) return@withLock true
                authenticatedStreamsSubscribed = false
            }
            if (!ensureDdpResumeForStreams()) {
                if (bootstrapGeneration.get() == generationAtStart) markDisconnected()
                return@withLock false
            }
            try {
                subscribeAuthenticatedStreams()
                if (bootstrapGeneration.get() != generationAtStart) return@withLock false
                authenticatedStreamsSubscribed = true
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "subscribe streams failed", e)
                if (bootstrapGeneration.get() == generationAtStart) markDisconnected()
                false
            }
        }

    /**
     * RN subscribeAuthenticatedStreams session.ts:389-417：全局流最小集，并发订阅
     * （Promise.all 等价）。uid 缺失时跳过 notify-user 三条（RN :402-404 warn）。
     */
    private suspend fun subscribeAuthenticatedStreams() {
        val ddp = sdk.ddp ?: throw DdpException("[realtime] ddp not initialized")
        val uid = ddp.userId ?: lastUserId
        coroutineScope {
            if (uid != null) {
                async { ddp.subscribe(StreamNames.NOTIFY_USER, StreamNames.userEvent(uid, StreamNames.SUBSCRIPTIONS_CHANGED)) }
                async { ddp.subscribe(StreamNames.NOTIFY_USER, StreamNames.userEvent(uid, StreamNames.ROOMS_CHANGED)) }
                async { ddp.subscribe(StreamNames.NOTIFY_USER, StreamNames.userEvent(uid, StreamNames.USER_DATA)) }
            } else {
                Log.w(TAG, "missing userId; skip stream-notify-user subscriptions")
            }
            async { ddp.subscribe(StreamNames.NOTIFY_LOGGED, StreamNames.PERMISSIONS_CHANGED) }
            async { ddp.subscribe(StreamNames.ROLES, StreamNames.ROLES_EVENT) }
            async { ddp.subscribe(StreamNames.NOTIFY_ALL, StreamNames.PUBLIC_SETTINGS_CHANGED) }
        }
    }

    /** RN markRealtimeServerDisconnected :79-85：订阅状态清零 + phase 落 disconnected（横幅立现）。 */
    private fun markDisconnected() {
        authenticatedStreamsSubscribed = false
        phaseState.value = RealtimeTransportPhase.DISCONNECTED
    }

    // ---- 重连恢复 ----

    /** RN scheduleFinalize :167-171：finalizeTail promise 链的等价——串行执行每轮收尾。 */
    private fun scheduleFinalize() {
        val job = scope.launch {
            finalizeMutex.withLock { runFinalizeOnce() }
        }
        finalizeJobs.add(job)
        job.invokeOnCompletion { finalizeJobs.remove(job) }
    }

    /**
     * RN runFinalizeOnce session.ts:173-186：needsPostReconnectResume 时 resumeStreamsAfterSocketUp
     * （强制重订阅；不 ready 视为失败）；失败回滚标记等下一轮 connected。
     */
    private suspend fun runFinalizeOnce() {
        val runResume = needsPostReconnectResume
        needsPostReconnectResume = false
        try {
            if (runResume) {
                authenticatedStreamsSubscribed = false // RN resumeStreamsAfterSocketUp :156-163
                if (!ensureAuthenticatedStreamSubscriptions(bootstrapGeneration.get())) {
                    throw DdpException("[realtime] DDP session not ready after reconnect")
                }
                // 重连收尾 tail（T6）：房间流重订（RN :162 await resubscribeAllActiveRoomStreams）；
                // 抛错 → 下方 catch 置回 needsPostReconnectResume，等下一轮 connected 重试
                reconnectTails.forEach { tail -> tail() }
            }
            phaseState.value = RealtimeTransportPhase.CONNECTED // RN :180
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "post-reconnect resume failed", e)
            needsPostReconnectResume = true
            phaseState.value = RealtimeTransportPhase.DISCONNECTED // RN :184
        }
    }

    // ---- 传输层监听（RN wireLegacyStreamHandlers :192-260）----

    private fun wireBaseHandlers() {
        // 实例同一性守卫（评审 Important-3）：同一 client 已挂过监听才是真 wired
        if (wiredBaseHandlers && sdk.ddp === wiredClient) return
        val ddp = sdk.ddp ?: return
        wiredBaseHandlers = true
        wiredClient = ddp
        synchronized(streamStops) {
            // RN :199-205：connecting 只在非 connected 时降级（首连不打断「已连」初值）
            streamStops.add(
                ddp.onStreamData("connecting") {
                    if (phaseState.value != RealtimeTransportPhase.CONNECTED) {
                        phaseState.value = RealtimeTransportPhase.CONNECTING
                    }
                },
            )
            // RN :206-213：connected → 串行 finalize（resume + 重订阅；phase 在 finalize 内落定）
            streamStops.add(ddp.onStreamData("connected") { scheduleFinalize() })
            // RN :214-226：close → 标记待恢复 + 立即建连以区分断线与会话失效；
            // 重连风暴防护在 DdpClient（connectInflight 合并 + tryReopen 去重），本层不重复排
            streamStops.add(
                ddp.onStreamData("close") {
                    if (!suppressTransportCloseUi) {
                        needsPostReconnectResume = true
                        authenticatedStreamsSubscribed = false
                        // RN :221：用 connecting 避免自动重连期间误报「未连接服务器」
                        phaseState.value = RealtimeTransportPhase.CONNECTING
                        sdk.checkAndReopenTransport()
                    }
                },
            )
            // RN :197 'connecting'（phase UI）与 :228 'users'（devLog）不影响语义，M1 不挂
            for (topic in listOf(
                StreamNames.NOTIFY_USER,
                StreamNames.NOTIFY_LOGGED,
                StreamNames.ROLES,
                StreamNames.NOTIFY_ALL,
                // M5-T3：presence 增量流监听（RN :237 wireLegacyStreamHandlers 同位）；
                // 订阅由 PresenceBatcher 按需 subscribeRaw，此处仅帧分发
                StreamNames.USER_PRESENCE,
            )) {
                streamStops.add(
                    ddp.onStreamData(topic) { msg ->
                        // handler 分发兜底（评审 Important-3）：handler 在 DDP IO 线程裸调，
                        // 抛异常不能崩进程；DdpClient.emit 的 runCatching 静默吞，这里带 topic 落日志
                        runCatching { streamHandlers[topic]?.invoke(msg) }
                            .onFailure { Log.w(TAG, "stream handler failed (topic=$topic)", it) }
                    },
                )
            }
        }
    }

    /** RN stopAllStreamListeners :134-145：全量摘除 Disposable，复位 wired/订阅状态（幂等）。 */
    private fun stopAllStreamListeners() {
        synchronized(streamStops) {
            streamStops.forEach { runCatching { it.stop() } }
            streamStops.clear()
        }
        wiredBaseHandlers = false
        wiredClient = null
        authenticatedStreamsSubscribed = false
    }

    // ---- 会话失效识别（RN :96-105）----

    /**
     * RN isSessionInvalidatedResumeError：取错误文本首个字符串型候选（DdpMethodError 的
     * reason → message → 异常 message），匹配英文原文（大小写不敏感）。
     */
    internal fun isSessionInvalidatedResumeError(error: Throwable): Boolean {
        val text = when (error) {
            is DdpMethodError -> {
                val obj = error.error as? JsonObject
                obj.textField("reason") ?: obj.textField("message") ?: error.message
            }
            else -> error.message
        }
        return text != null &&
            (LOGGED_OUT_BY_SERVER.containsMatchIn(text) || SESSION_HAS_EXPIRED.containsMatchIn(text))
    }

    private fun JsonObject?.textField(key: String): String? = when (val value = this?.get(key)) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> null
    }

    /** JSON 字段取串（PermissionsStore.str 同口径；handler 帧解析用）。 */
    private fun JsonObject.str(key: String): String? = textField(key)

    // ---- 测试观测 ----

    /** 连接状态（占位 UI/M2 横幅同源）：true = 全局流已订阅且未被断线/teardown 复位。 */
    val connectionUp: StateFlow<Boolean> get() = connectionState

    /** 传输层三态（M2 T5 连接横幅数据源；RN realtimeConnectionStore.phase 等价）。 */
    val phase: StateFlow<RealtimeTransportPhase> get() = phaseState

    /**
     * 会话列表横幅「重试」的手动重连入口（RN requestManualRealtimeReconnect session.ts:656-670）：
     * token 缺失（未 bootstrap/已 teardown）直接 no-op（:659 守卫）；否则标记待恢复 + 同步置
     * connecting + 清订阅态，立即 `sdk.connect()`（RN `await sdk.connect()` 同帧；DdpClient.connect
     * 自带 isTransportOpen 短路与 connectInflight 合并，并取消未触发的 reopen 定时器——与既有重连
     * 风暴防护互不叠加）。connect 后显式 scheduleFinalize（RN :663 await scheduleFinalize；传输
     * 已开时没有 'connected' 事件可依赖 finalize），失败落 disconnected（RN :668）。
     */
    fun requestManualReconnect() {
        if (currentToken == null) return
        needsPostReconnectResume = true
        phaseState.value = RealtimeTransportPhase.CONNECTING
        authenticatedStreamsSubscribed = false
        scope.launch {
            try {
                sdk.connect()
                scheduleFinalize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "manual reconnect failed", e)
                phaseState.value = RealtimeTransportPhase.DISCONNECTED
            }
        }
    }

    internal val sessionKeyForTest: String? get() = sessionKey

    internal val generationForTest: Long get() = bootstrapGeneration.get()

    internal val streamsSubscribedForTest: Boolean get() = authenticatedStreamsSubscribed

    companion object {
        private const val TAG = "realtime"

        // RN publicSettingsStream.ts:14 /public-settings-changed/ 原文匹配
        private val PUBLIC_SETTINGS_CHANGED = Regex("public-settings-changed")

        // RN session.ts:103 原文（直引号撇号），大小写不敏感
        private val LOGGED_OUT_BY_SERVER = Regex("you've been logged out by the server", RegexOption.IGNORE_CASE)
        private val SESSION_HAS_EXPIRED = Regex("your session has expired", RegexOption.IGNORE_CASE)
    }
}
