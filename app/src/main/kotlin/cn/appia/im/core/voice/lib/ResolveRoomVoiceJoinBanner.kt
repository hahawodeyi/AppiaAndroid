package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.voiceJson

/**
 * RN lib/voice/resolveRoomVoiceJoinBanner.ts verbatim（房间「加入语音」横幅与 onCallStatus 推导）。
 * countUsersInVoiceChannel 见 IsRoomCallMsgActive.kt（Task 1 先行提供，RN 原文同文件）。
 */

/**
 * resolveRoomVoiceJoinBanner.ts:50-74：群聊房间页「加入语音」横幅。
 * 房间通话进行中且至少一人在频道内即展示；不要求本人在 userStatus，
 * 本机是否已在通话仅影响点击行为，不隐藏横幅。
 */
fun shouldShowRoomVoiceJoinBanner(
    callMsg: VoiceCallMsg?,
    @Suppress("UNUSED_PARAMETER") username: String? = null,
    roomType: String? = null,
    ctx: VoiceCallMsgContext? = null,
    @Suppress("UNUSED_PARAMETER") serverOnCallStatus: Boolean? = null,
): Boolean {
    if (callMsg == null) {
        return false
    }

    val effectiveCallMsg = reconcileStaleLocalRoomCallMsg(callMsg) ?: callMsg

    val rt = roomType ?: resolveVoiceCallRoomType(effectiveCallMsg, ctx)
    if (!isGroupVoiceRoom(rt)) {
        return false
    }

    if (countUsersInVoiceChannel(effectiveCallMsg) == 0) {
        return false
    }

    return isRoomVoiceInProgress(effectiveCallMsg)
}

/** resolveRoomVoiceJoinBanner.ts:76-93：richness 打分（频道人数 ×10 + 状态分 + channelId）。 */
private fun roomCallMsgRichnessScore(callMsg: VoiceCallMsg?): Int {
    val reconciled = reconcileStaleLocalRoomCallMsg(callMsg) ?: callMsg ?: return 0
    val inChannel = countUsersInVoiceChannel(reconciled)
    val status = reconciled.status
    val statusScore = when (status) {
        "talking", "calling" -> 20
        "end", "cancel", "reject" -> -10
        else -> 0
    }
    val channelId = if (!reconciled.recordDataObj?.channelId?.trim().isNullOrEmpty()) 5 else 0
    return inChannel * 10 + statusScore + channelId
}

/** reconciled VoiceCallMsg 编码回 raw JSON 字符串（RN 侧 JSON.stringify 形态）。 */
private fun encodeCallMsg(callMsg: VoiceCallMsg): String =
    voiceJson.encodeToString(cn.appia.im.core.voice.VoiceCallMsg.serializer(), callMsg)

/**
 * resolveRoomVoiceJoinBanner.ts:96-130：DB 与 voice store 的 callMsg 间取信息更完整的一份，减轻 sync 抖动。
 * 简报裁定：store 调用时序形态改为参数显式传入（dbRaw + storeCallMsg 对象），
 * RN 的 JSON.stringify 序列化比对改为对两份解析结果打 richness 分数（同逻辑）。
 */
fun pickRicherRoomCallMsgRaw(dbRaw: String?, storeCallMsg: VoiceCallMsg?): String? {
    val dbParsed = reconcileStaleLocalRoomCallMsg(parseCallMsg(dbRaw))
    val storeParsed = reconcileStaleLocalRoomCallMsg(storeCallMsg)
    if (dbParsed == null) {
        return storeParsed?.let { encodeCallMsg(it) } ?: dbRaw
    }
    if (storeParsed == null) {
        return dbRaw
    }

    val dbScore = roomCallMsgRichnessScore(dbParsed)
    val storeScore = roomCallMsgRichnessScore(storeParsed)
    if (storeScore > dbScore) {
        return encodeCallMsg(storeParsed)
    }
    if (dbScore > storeScore) {
        return dbRaw
    }

    val dbChannelId = dbParsed.recordDataObj?.channelId?.trim()
    val storeChannelId = storeParsed.recordDataObj?.channelId?.trim()
    if (!storeChannelId.isNullOrEmpty() && dbChannelId.isNullOrEmpty()) {
        return encodeCallMsg(storeParsed)
    }
    if (!dbChannelId.isNullOrEmpty() && storeChannelId.isNullOrEmpty()) {
        return dbRaw
    }

    // 人数相同：优先 voice store（通常更接近实时同步）
    return encodeCallMsg(storeParsed)
}

/** resolveRoomVoiceJoinBanner.ts:133-164：推导房间 onCallStatus（不盲信服务端单人 leave 后的 false）。 */
fun resolveRoomVoiceOnCallStatus(
    callMsgRaw: String?,
    username: String,
    roomType: String? = null,
    serverOnCallStatus: Boolean? = null,
    ctx: VoiceCallMsgContext? = null,
): Boolean {
    val parsed = parseCallMsg(callMsgRaw)
    if (parsed == null) {
        return serverOnCallStatus ?: false
    }

    val effective = reconcileStaleLocalRoomCallMsg(parsed) ?: parsed

    if (shouldShowRoomVoiceJoinBanner(effective, username, roomType, ctx)) {
        return true
    }

    if (isRoomVoiceInProgress(effective) && countUsersInVoiceChannel(effective) > 0) {
        return true
    }

    if (serverOnCallStatus == true && isRoomCallMsgActive(effective, ctx)) {
        return true
    }

    return false
}
