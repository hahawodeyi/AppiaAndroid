package cn.appia.im.feature.voice

import android.content.Context
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.datastore.MmkvKvStore
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.fetchExternalToken
import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
import cn.appia.im.core.voice.OncallApi
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.VoiceEnv
import cn.appia.im.core.voice.VoiceTokenCache
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.lib.findLoginCandidateForVoiceOrg
import cn.appia.im.core.voice.lib.parseCallMsg
import cn.appia.im.core.voice.lib.pickRicherRoomCallMsgRaw
import cn.appia.im.core.voice.lib.reconcileStaleLocalRoomCallMsg
import cn.appia.im.core.voice.platform.AndroidInCallAudioRoute
import cn.appia.im.core.voice.platform.AndroidVoiceRinger
import cn.appia.im.core.voice.platform.TelecomCallBridge
import cn.appia.im.core.voice.platform.VoiceFloatServiceStarter
import cn.appia.im.core.voice.rtc.VoiceRtcEngine
import cn.appia.im.core.voice.rtc.VoiceRtcImpl
import cn.appia.im.domain.session.BackgroundScope
import cn.appia.im.domain.session.OrgSwitchCoordinator
import cn.appia.im.domain.voice.CallSyncWatcher
import cn.appia.im.domain.voice.VoiceRinger
import cn.appia.im.domain.voice.VoiceSessionContext
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionDeps
import cn.appia.im.domain.voice.VoiceSessionStore
import cn.appia.im.feature.meetingroom.MeetingRoomHandle
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

/**
 * 语音房间装配束（M10-T12；MeetingRoomHandle 先例）。**@Singleton（app 域）**：
 * Controller/Watcher/Telecom 桥跨 Activity 重建存活；VoiceCallHost（根部）与推送分流
 * （PushClickRouter）共用同一实例。
 *
 * - [startPlatformWiring]：phase ∈ {outgoingRinging, connecting, inCall} → FGS 起停
 *   （RN VoiceCallHost/index.tsx:45-49/82-89——副作用归装配层，UI 纯 render）。
 * - [shutdown]：登出收尾（endCall("logout") + watcher.stop + ringer.stop + FGS stop），
 *   AuthRepository.logout 与 mailBadge.reset 同位调用。
 * - [telecom]：来电上报桥（ConnectionService 静态桥 dispatch → controller accept/reject）。
 */
class VoiceRoomHandle(
    val controller: VoiceSessionController,
    val watcher: CallSyncWatcher,
    /** 推送语音意图分流（queue 与 deps 同源——dismissed/记录可见性一致，Task 10 handoff ①）。 */
    val pushIngress: cn.appia.im.domain.voice.VoicePushIngress,
    /** 跨组织来电发现（progress.md 归属裁定归本任务；idle 相位前台轮询）。 */
    val crossOrgNotifier: cn.appia.im.feature.voice.ui.VoiceCrossOrgNotifier,
    /** deps 同源（shutdown 的 ringer stop 走 controller 同一 ringer 实例）。 */
    private val deps: VoiceSessionDeps,
    val telecom: TelecomCallBridge? = null,
    /** app 级 scope（onSessionActive 的 drain/watch 挂载宿）。 */
    private val scope: CoroutineScope? = null,
    /** FGS 启停缝（测试注入记录器；生产 = VoiceFloatServiceStarter）。 */
    private val fgsStarter: () -> Unit = {},
    private val fgsStopper: () -> Unit = {},
) {

    /** RN ACTIVE_CALL_PHASES（index.tsx:45-49）——FGS 驱动相位集。 */
    private val activePhases = setOf(
        cn.appia.im.core.voice.VoicePhase.OutgoingRinging,
        cn.appia.im.core.voice.VoicePhase.Connecting,
        cn.appia.im.core.voice.VoicePhase.InCall,
    )

    /**
     * phase → FGS + Telecom 接线（App 级 scope 挂一次；重复调用幂等——旧 job 先取消）。
     * RN :82-89 useEffect：active 相位 startForegroundService，否则 stop。
     * M-5（终审）：IncomingRinging + recordId → telecom.reportIncomingCall（recordId 去重）；
     * Connecting/InCall → setActive；其余相位 → setDisconnected。hasAccountPermission false
     * → 静默跳过（降级 in-app 横幅）。Telecom onAnswer 的 mic 门在 [TelecomAnswerGate]——
     * 锁屏（activity null）未授权时判拒挂断，绝不在后台等权限弹窗。
     */
    fun startPlatformWiring(scope: CoroutineScope) {
        scope.launch {
            var lastReportedCallId: String? = null
            controller.store.state.collect { state ->
                if (activePhases.contains(state.phase)) fgsStarter() else fgsStopper()

                val telecom = telecom ?: return@collect
                val recordId = state.recordId
                when {
                    state.phase == cn.appia.im.core.voice.VoicePhase.IncomingRinging && !recordId.isNullOrEmpty() -> {
                        if (recordId != lastReportedCallId && telecom.hasAccountPermission()) {
                            val accepted = telecom.reportIncomingCall(
                                callId = recordId,
                                callerName = resolveTelecomCallerLabel(state),
                                onAnswer = {
                                    if (cn.appia.im.feature.voice.VoiceMicGate.canAnswerWithoutPrompt()) {
                                        controller.acceptIncoming()
                                    } else {
                                        controller.rejectIncoming()
                                    }
                                },
                                onReject = { controller.rejectIncoming() },
                            )
                            if (accepted) lastReportedCallId = recordId
                        }
                    }
                    state.phase == cn.appia.im.core.voice.VoicePhase.Connecting ||
                        state.phase == cn.appia.im.core.voice.VoicePhase.InCall -> {
                        if (recordId != null) telecom.setActive(recordId)
                    }
                    else -> {
                        val done = recordId ?: lastReportedCallId
                        if (done != null) telecom.setDisconnected(done)
                        lastReportedCallId = null
                    }
                }
            }
        }
    }

    /** Telecom 上报的来电显示名（RN 无对应——横幅 stable label 同源口径）。 */
    private fun resolveTelecomCallerLabel(state: cn.appia.im.domain.voice.VoiceSessionState): String =
        cn.appia.im.core.voice.lib.resolveIncomingCallerIdentity(state.callMsg).displayName
            .ifEmpty { state.callMsg?.roomId.orEmpty() }

    /**
     * 登出收尾（brief Step 3 + 终审 H-1/L-4/针脚 6）：endCall("logout")（活跃通话走完整
     * 清算 → idle；idle/ending 端内 no-op）+ watcher.stop + ringer.stop + FGS stop +
     * pushIngress 队列清 + crossOrg 横幅收。
     *
     * **不调 controller.shutdown()**（终审 H-1）：rtc.release 是终态（released 恒真、
     * scope.cancel），与 @Singleton 常驻 handle 冲突——登出重登后语音将永久哑火。登出后
     * sessionContext() 返回 null 已天然挡住新会话，引擎无需释放。
     */
    fun shutdown() {
        controller.endCall("logout")
        watcher.stop()
        controller.store.update { it.copy(uiMinimized = false) }
        deps.ringer.stop()
        fgsStopper()
        pushIngress.clearQueued()
        crossOrgNotifier.dismissBanner()
    }

    /**
     * 推送分流接线（brief Step 3「推送分流接线」）：MainActivity 挂载后调用——
     * shared receiver 的 onVoiceOncall 可变缝 ← ingress.handle。幂等（覆盖写）。
     */
    fun wirePushIngress() {
        cn.appia.im.core.push.PushClickRouter.shared.onVoiceOncall =
            { rid, voice, host -> pushIngress.handle(rid, voice, host) }
    }

    /**
     * 登录/换服后冲刷积压语音意图（RN MainNavigator.tsx:63 `void drainPendingVoicePushIntents()`
     * 的 Main 进入 + AppState-active 两触发点收敛——AA 的 drain 内部自验登录，未登录直返）。
     * watcher.watch 同位（RN 双源 observe 在登录即挂，AA 换服重挂由调用方 serverUrl 驱动）。
     */
    fun onSessionActive(serverUrl: String) {
        watcher.watch(serverUrl)
        scope?.launch { runCatching { pushIngress.drainPending() } }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object VoiceRoomModule {

    @Provides
    @Singleton
    fun provideVoiceRoomHandle(
        @ApplicationContext context: Context,
        sdk: RocketSdk,
        store: AuthSessionStore,
        dbManager: DatabaseManager,
        meetingRoom: MeetingRoomHandle,
        orgSwitch: OrgSwitchCoordinator,
        @BackgroundScope scope: CoroutineScope,
    ): VoiceRoomHandle {
        // oncall 网关（独立 OkHttp——RocketSdk 之外的独立网关域，OncallApi KDoc 裁定）
        val oncall = OncallApi(baseUrlProvider = { "" })

        // RN voiceTokenCache.fetchToken = getVoiceExternalToken（REST GET users.externalToken，
        // X-User-Id/X-Auth-Token 头经 sdk.get 携带）。安全口径（ExternalTokenApi KDoc）：
        // 响应体含换得凭证，不得打印/埋点——失败仅走 null 上抛链。
        // Task 4 Info(4) 陷阱：missing success 按 RN 接受（voiceTokenCache.ts:23 只拒显式 false）。
        val tokenCache = VoiceTokenCache(
            fetchToken = { serverUrl, userId, authToken ->
                fetchExternalToken(sdk, serverUrl, "voice")
                    .takeIf { it.success != false }
                    ?.token
            },
        )

        /** 逐请求现读（组织切换后取新值，RN getVoiceSessionContext 同义）。 */
        fun currentContext(): VoiceSessionContext? {
            val session = store.load() ?: return null
            if (session.token.isEmpty() || session.serverUrl.isEmpty()) return null
            // Enterprise_ID 公共设置行（RN usePublicSettingBoolean 同源 DB）
            val enterpriseId = runCatching {
                kotlinx.coroutines.runBlocking {
                    dbManager.active.settingDao().getById("Enterprise_ID")?.value_as_string
                }
            }.getOrNull()
            // 业务 token：缓存命中同步返；未命中同步取。Controller 全部调用点在协程内
            //（scope = Dispatchers.IO），runBlocking 占当前 IO 协程，等价 RN await。
            val businessToken = try {
                runBlocking { tokenCache.getToken(session.user.id, session.token, session.serverUrl) }
            } catch (e: Exception) {
                return null
            }
            return VoiceSessionContext(
                org = VoiceEnv.resolveVoiceOrg(enterpriseId),
                businessToken = businessToken,
                apiHost = VoiceEnv.shengwangApiHost(session.serverUrl),
                userId = session.user.id,
                username = session.user.username,
                userDisplayName = session.user.name.orEmpty(),
            )
        }

        val deps = object : VoiceSessionDeps {
            override val rtc: VoiceRtcEngine =
                VoiceRtcEngine(VoiceRtcImpl(context), AndroidInCallAudioRoute(context), scope)
            override val timers: VoiceCallTimers = VoiceCallTimers(scope)
            override val store: VoiceSessionStore = VoiceSessionStore()
            override val ringer: VoiceRinger = AndroidVoiceRinger(context)

            override fun sessionContext(): VoiceSessionContext? = currentContext()
            override val meetingPhaseProvider: () -> String? =
                { meetingRoom.machine.phase.name.lowercase() }
            override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()

            override fun roomCallMsgForRid(rid: String): VoiceCallMsg? {
                val row = kotlinx.coroutines.runBlocking {
                    runCatching { dbManager.active.chatDao().getByRid(rid) }.getOrNull()
                }
                val raw = pickRicherRoomCallMsgRaw(row?.callMsg, null) ?: return null
                val parsed = parseCallMsg(raw)
                return reconcileStaleLocalRoomCallMsg(parsed) ?: parsed
            }

            override fun clearRoomCallMsg(rid: String) {
                kotlinx.coroutines.runBlocking {
                    val dao = dbManager.active.chatDao()
                    val row = runCatching { dao.getByRid(rid) }.getOrNull() ?: return@runBlocking
                    if (row.callMsg != null) {
                        runCatching { dao.update(row.copy(callMsg = null, onCallStatus = null)) }
                    }
                }
            }

            override fun onTokenExpired() = tokenCache.invalidate()

            /** Task 8 handoff ④：mic 权限门（RN :1070/:1334/:1806 三处 store 内置门）。 */
            override suspend fun requestMicPermission(): Boolean =
                cn.appia.im.feature.voice.VoiceMicGate.request()

            override suspend fun switchToVoiceOrg(org: String): Boolean {
                val session = store.load() ?: return false
                val candidates = LoginSwitchCandidatesCache(MmkvKvStore(MMKV.defaultMMKV()))
                    .read(session.user.username, session.serverUrl).orEmpty()
                val candidate = findLoginCandidateForVoiceOrg(candidates, org) ?: return false
                return try {
                    orgSwitch.switchTo(candidate.appiaUrl)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override val deviceIdProvider: () -> String? = {
                runCatching {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID,
                    )
                }.getOrNull()
            }

            override fun roomCallMsgRawForRid(rid: String): String? = kotlinx.coroutines.runBlocking {
                runCatching { dbManager.active.chatDao().getByRid(rid) }.getOrNull()?.callMsg
            }

            override fun roomOnCallStatusForRid(rid: String): Boolean? = kotlinx.coroutines.runBlocking {
                runCatching { dbManager.active.chatDao().getByRid(rid) }.getOrNull()?.onCallStatus
            }

            override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse =
                oncall.fetchAgoraToken(ctx, channelName, uid)

            override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse =
                oncall.informInit(ctx, body)

            override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse =
                oncall.updateRecordStatus(ctx, recordId, status)

            override suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse =
                oncall.join(ctx, body)

            override suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String?): OncallResponse =
                oncall.reject(ctx, recordId, username, notificationOrg)

            override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse =
                oncall.leave(ctx, recordId, username)

            override suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse =
                oncall.addUsers(ctx, body)

            override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse =
                oncall.getRecordStatusV2(ctx, recordId, recordOrg)

            override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse =
                oncall.getNotify(ctx, org, recordId, username, recordOrg)
        }

        val controller = VoiceSessionController(deps, scope)
        val watcher = CallSyncWatcher(scope, { server -> dbManager.databaseFor(dbManager.normalizeServer(server)) }, controller) {
            store.load()?.user?.username
        }
        // 推送分流（Task 10 handoff ①：queue/deps 与 Controller 同实例——dismissed 同源）
        val pushIngress = cn.appia.im.domain.voice.VoicePushIngress(
            controller = controller,
            sessionContextProvider = { currentContext() },
            queue = cn.appia.im.domain.voice.VoicePushQueue(),
            deps = deps,
        )
        // 跨组织来电发现（RN :46-77 轮询——getCurrentRecordV2 data 行解析 + notifyHost2）
        val crossOrgNotifier = cn.appia.im.feature.voice.ui.VoiceCrossOrgNotifier(
            controller = controller,
            deps = deps,
            scope = scope,
            fetchCurrentRecords = { ctx, username ->
                val res = oncall.getCurrentRecordV2(ctx.oncallContext(), username)
                val arr = (res.data as? kotlinx.serialization.json.JsonArray) ?: return@VoiceCrossOrgNotifier emptyList()
                arr.mapNotNull { el ->
                    val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    fun s(name: String) = (o[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    val org = s("org") ?: return@mapNotNull null
                    val recordId = s("recordId") ?: return@mapNotNull null
                    cn.appia.im.feature.voice.ui.VoiceCrossOrgNotifier.CrossOrgRecord(
                        org = org, recordId = recordId,
                        status = s("status"), initiatorUsername = s("initiatorUsername"), initiatorName = s("initiatorName"),
                    )
                }
            },
            notifyHost = { ctx, recordOrg, recordId, username ->
                oncall.notifyHost2(ctx.oncallContext(), recordOrg, recordId, username)
            },
        )
        val handle = VoiceRoomHandle(
            controller = controller,
            watcher = watcher,
            pushIngress = pushIngress,
            crossOrgNotifier = crossOrgNotifier,
            deps = deps,
            telecom = TelecomCallBridge(context, context.getSystemService(android.telecom.TelecomManager::class.java)),
            scope = scope,
            fgsStarter = { VoiceFloatServiceStarter.start(context, "") },
            fgsStopper = { VoiceFloatServiceStarter.stop(context) },
        )
        handle.startPlatformWiring(scope)
        return handle
    }
}
