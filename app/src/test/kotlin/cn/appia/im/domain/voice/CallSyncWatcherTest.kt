package cn.appia.im.domain.voice

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.rtc.InCallAudioRoute
import cn.appia.im.core.voice.rtc.VoiceRtcApi
import cn.appia.im.core.voice.rtc.VoiceRtcEngine
import cn.appia.im.core.voice.rtc.VoiceRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * brief Step 1（RN useRoomListChats.ts:57-71 + useRoomChatByRid.ts:29-35 双源合一的
 * 全表 observe 形态）：callMsg 落库变化 → controller.syncCallMsgFromRoom 收到
 * rid+raw+onCallStatus（resolveRoomVoiceOnCallStatus 推导,不盲信服务端 false）；
 * 同值重发不重复触发（distinctUntilChanged）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallSyncWatcherTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val testScheduler = TestCoroutineScheduler()
    private val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))
    private val engineScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    private val fakeRtc = FakeRtc()
    private val fakeRoute = FakeRoute()
    private lateinit var deps: FakeDeps
    private lateinit var controller: VoiceSessionController

    private var username: String? = "bob"

    private lateinit var watcher: CallSyncWatcher

    @Before
    fun setUp() {
        deps = FakeDeps(
            rtc = VoiceRtcEngine(fakeRtc, fakeRoute, engineScope, delayMs = 0),
            timers = VoiceCallTimers(testScope),
            store = VoiceSessionStore(),
        )
        controller = VoiceSessionController(deps, testScope)
        watcher = CallSyncWatcher(
            scope = testScope,
            dbProvider = { db },
            controller = controller,
            usernameProvider = { username },
        )
    }

    @After
    fun tearDown() {
        watcher.stop()
        testScope.cancel()
        engineScope.cancel()
        db.close()
    }

    private fun state() = controller.store.state.value

    /** Room 发射跨线程（query executor）——轮询等待条件成立。 */
    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                assertTrue("awaitTrue timeout", cond())
                return
            }
            Thread.sleep(10)
        }
    }

    private fun chatRow(id: String, callMsg: String? = null, onCallStatus: Boolean? = null, t: String = "d") =
        ChatEntity(
            _id = id,
            f = false,
            t = t,
            ts = 0.0,
            ls = 0.0,
            name = "n",
            fname = "n",
            rid = id,
            open = true,
            alert = false,
            unread = 0.0,
            user_mentions = 0.0,
            group_mentions = 0.0,
            room_updated_at = 0.0,
            ro = false,
            archived = false,
            auto_translate_language = "en",
            team_id = "",
            callMsg = callMsg,
            onCallStatus = onCallStatus,
        )

    private fun insert(row: ChatEntity) = runBlocking { db.chatDao().insert(row) }

    /** alice → bob 直聊来电（alice 已 in 频道）。 */
    private val activeCallRaw = buildJsonObject {
        put("status", "calling")
        put("roomId", "r1")
        put("roomType", "d")
        put("org", "bitmain")
        put("recordData", buildJsonObject {
            put("exist", "true")
            put("recordId", "rec-1")
            put("channelId", "ch-1")
            put("initiator", "alice")
        })
        put("userStatus", buildJsonArray {
            add(buildJsonObject { put("username", "alice"); put("status", "in") })
            add(buildJsonObject { put("username", "bob"); put("status", "out") })
        })
    }.toString()

    /** 同记录结束态（exist=false + status=end）。 */
    private val endedCallRaw = buildJsonObject {
        put("status", "end")
        put("roomId", "r1")
        put("roomType", "d")
        put("recordData", buildJsonObject {
            put("exist", "false")
            put("recordId", "rec-1")
        })
        put("userStatus", buildJsonArray {
            add(buildJsonObject { put("username", "alice"); put("status", "out") })
            add(buildJsonObject { put("username", "bob"); put("status", "out") })
        })
    }.toString()

    @Test
    fun `chat callMsg row drives controller sync and presents incoming`() {
        watcher.watch("srv")

        // 服务端 onCallStatus=false 但 alice 已 in 频道（resolveRoomVoiceOnCallStatus 不盲信 false → true）
        insert(chatRow("r1", activeCallRaw, onCallStatus = false))

        awaitTrue { state().phase == VoicePhase.IncomingRinging }
        assertEquals("r1", state().rid)
        assertEquals("rec-1", state().recordId)
        assertEquals("ch-1", state().channelId)
        assertEquals(1, deps.ringerStarts)
    }

    @Test
    fun `server onCallStatus false with nobody in channel suppresses incoming`() {
        watcher.watch("srv")

        // alice 未 in 频道 + 服务端 false → onCallStatus 推导 false → 段 7 不呈现
        // （但 sync 仍到达：段 4 落 callMsg/rid）
        val nobodyInChannel = buildJsonObject {
            put("status", "calling")
            put("roomId", "r1")
            put("roomType", "d")
            put("recordData", buildJsonObject {
                put("exist", "true")
                put("recordId", "rec-1")
                put("channelId", "ch-1")
                put("initiator", "alice")
            })
            put("userStatus", buildJsonArray {
                add(buildJsonObject { put("username", "alice"); put("status", "out") })
                add(buildJsonObject { put("username", "bob"); put("status", "out") })
            })
        }.toString()
        insert(chatRow("r1", nobodyInChannel, onCallStatus = false))

        awaitTrue { state().rid == "r1" } // 段 4 已写入 = sync 到达
        assertEquals(VoicePhase.Idle, state().phase) // 段 7 被抑制
        assertEquals(0, deps.ringerStarts)
        assertNotNull(state().callMsg)
    }

    @Test
    fun `identical re-emission is dropped by distinctUntilChanged`() {
        watcher.watch("srv")

        insert(chatRow("r1", activeCallRaw))
        awaitTrue { state().phase == VoicePhase.IncomingRinging }

        // 落库变化（active → end）→ 段 8 复位
        insert(chatRow("r1", endedCallRaw))
        awaitTrue { state().phase == VoicePhase.Idle && deps.ringerStops >= 1 }
        assertNull(state().callMsg)
        assertNull(state().rid)

        // 同值重发（REPLACE 同内容仍触发 invalidation）→ distinctUntilChanged 丢弃；
        // 若未被丢弃,段 4 会把 ended callMsg/rid 写回 store
        insert(chatRow("r1", endedCallRaw))
        Thread.sleep(400)

        assertNull("identical re-emission must be dropped", state().callMsg)
        assertNull(state().rid)
    }

    @Test
    fun `no username skips sync until username appears`() {
        username = null
        watcher.watch("srv")

        insert(chatRow("r1", activeCallRaw))
        Thread.sleep(300)
        assertEquals(VoicePhase.Idle, state().phase)
        assertNull(state().rid)

        // 登录后下一次表发射（插 r2 无 callMsg 行也触发全表 emit）补送 r1
        username = "bob"
        insert(chatRow("r2"))
        awaitTrue { state().phase == VoicePhase.IncomingRinging }
        assertEquals("r1", state().rid)
    }

    @Test
    fun `stop halts forwarding`() {
        watcher.watch("srv")
        insert(chatRow("r1", activeCallRaw))
        awaitTrue { state().phase == VoicePhase.IncomingRinging }
        insert(chatRow("r1", endedCallRaw))
        awaitTrue { state().phase == VoicePhase.Idle }

        watcher.stop()
        insert(chatRow("r1", activeCallRaw))
        Thread.sleep(300)
        assertNull("stopped watcher must not forward", state().rid)
        assertEquals(VoicePhase.Idle, state().phase)
    }

    // ── fixtures（沿用 domain/voice 既有测试的 FakeDeps/FakeRtc 形态） ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore(),
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()
        var contextOrNull: VoiceSessionContext? = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-b", username = "bob", userDisplayName = "Bob",
        )
        var ringerStarts = 0
        var ringerStops = 0

        override suspend fun fetchAgoraToken(
            ctx: OncallContext,
            channelName: String,
            uid: Long,
        ): OncallResponse = okResponse()

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse = okResponse()

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse =
            okResponse()

        override suspend fun join(
            ctx: OncallContext,
            body: cn.appia.im.core.voice.JoinBody,
        ): OncallResponse = okResponse()

        override suspend fun reject(
            ctx: OncallContext,
            recordId: String,
            username: String,
            notificationOrg: String?,
        ): OncallResponse = okResponse()

        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse =
            okResponse()

        override suspend fun addUsers(
            ctx: OncallContext,
            body: cn.appia.im.core.voice.AddUsersBody,
        ): OncallResponse = okResponse()

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse =
            okResponse()

        override suspend fun getNotify(
            ctx: OncallContext,
            org: String,
            recordId: String,
            username: String,
            recordOrg: String?,
        ): OncallResponse {
            calls += "getNotify:$recordId"
            return okResponse()
        }

        override fun sessionContext(): VoiceSessionContext? = contextOrNull

        override val meetingPhaseProvider: () -> String? = { null }

        override fun roomCallMsgForRid(rid: String): cn.appia.im.core.voice.VoiceCallMsg? = null

        override fun clearRoomCallMsg(rid: String) {}

        override fun onTokenExpired() {}

        override suspend fun switchToVoiceOrg(org: String): Boolean = true

        override val ringer: VoiceRinger = object : VoiceRinger {
            override fun start() {
                ringerStarts += 1
            }

            override fun stop() {
                ringerStops += 1
            }
        }

        override val deviceIdProvider: () -> String? = { null }

        override fun roomCallMsgRawForRid(rid: String): String? = null

        override fun roomOnCallStatusForRid(rid: String): Boolean? = null
    }

    private class FakeRtc : VoiceRtcApi {
        private var onEvent: ((VoiceRtcEvent) -> Unit)? = null

        override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {
            this.onEvent = onEvent
        }

        override fun joinChannel(
            token: String?,
            channelId: String,
            uid: Long,
            encKey: String?,
            muted: Boolean,
            speakerOn: Boolean,
        ): Int = 0

        override fun leaveChannel(): Int = 0

        override fun muteLocalAudioStream(muted: Boolean): Int = 0

        override fun setEnableSpeakerphone(enabled: Boolean): Int = 0

        override fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int = 0

        override fun startAudioMixing(path: String, loop: Boolean): Int = 0

        override fun stopAudioMixing(): Int = 0

        override fun adjustAudioMixingVolume(volume: Int): Int = 0

        override fun destroy() {}
    }

    private class FakeRoute : InCallAudioRoute {
        override fun start() {}
        override fun stop() {}
        override fun setSpeakerphoneOn(enabled: Boolean) {}
    }
}

private fun okResponse(
    code: String = "0",
    data: JsonObject? = null,
): OncallResponse = OncallResponse(code = JsonPrimitive(code), data = data, success = true)
