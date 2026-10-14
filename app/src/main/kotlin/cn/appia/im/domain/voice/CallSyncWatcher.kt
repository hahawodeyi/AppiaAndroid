package cn.appia.im.domain.voice

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.voice.lib.resolveRoomVoiceOnCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 房间 callMsg 三源同步观察器（RN useRoomListChats.ts:57-71 + useRoomChatByRid.ts:29-35
 * 双源合一——AA 用全表 observe 统一：Room invalidation 表级触发，单房行变化天然驱动重发射；
 * 同 rid 多次 emit 由 Controller 的 syncCallMsgFromRoom 幂等裁定链消化，watcher 不去重语义）。
 *
 * 每行：callMsg 非空且 rid 变化 → controller.syncCallMsgFromRoom(rid, raw, onCallStatus)
 * （onCallStatus = resolveRoomVoiceOnCallStatus 推导，不盲信服务端单人 leave 后的 false）。
 *
 * username 缺失（未登录）时整批跳过——RN 侧由 activeDbMatchesAuth 门控；
 * 登录后下一次表发射补送（换库/同步必触发 invalidation）。
 */
class CallSyncWatcher(
    private val scope: CoroutineScope,
    private val dbProvider: (serverUrl: String) -> AppiaDatabase,
    private val controller: VoiceSessionController,
    private val usernameProvider: () -> String?,
) {

    private var job: Job? = null

    /** 订阅指定服务器的业务库（登录/换服后调用；重复调用先停旧订阅）。 */
    fun watch(serverUrl: String) {
        stop()
        job = scope.launch {
            dbProvider(serverUrl).chatDao().observe()
                .distinctUntilChanged()
                .collect { rows -> forward(rows) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** RN useRoomListChats.ts:57-71 apply 内的 syncVoiceCallMsgFromRoom 循环。 */
    private fun forward(rows: List<cn.appia.im.core.database.entity.ChatEntity>) {
        val username = usernameProvider()?.trim().orEmpty()
        if (username.isEmpty()) return
        for (row in rows) {
            val raw = row.callMsg ?: continue
            val rid = row.rid.trim().ifEmpty { row._id.trim() }
            if (rid.isEmpty()) continue
            controller.syncCallMsgFromRoom(
                rid = rid,
                callMsgRaw = raw,
                onCallStatus = resolveRoomVoiceOnCallStatus(
                    callMsgRaw = raw,
                    username = username,
                    roomType = row.t,
                    serverOnCallStatus = row.onCallStatus,
                ),
            )
        }
    }
}
