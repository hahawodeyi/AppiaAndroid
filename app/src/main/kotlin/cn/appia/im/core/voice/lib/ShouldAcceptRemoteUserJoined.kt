package cn.appia.im.core.voice.lib

/**
 * RN lib/voice/shouldAcceptRemoteUserJoined.ts verbatim。
 *
 * 决定本端是否接受 Agora `onUserJoined` 事件并进入接通。
 *
 * 对齐旧版 `legacy/.../VoiceChatView/index.tsx` 的 `onUserJoined`：
 * 任何非本端的远端加入即接通，**不**按 `receiverUid` 精确匹配过滤。
 *
 * 历史背景：新版曾在此处加 `isExpectedDirectVoiceRemoteUid` 精确匹配过滤
 * （commit 096dd3a，动机是"避免 onUserOffline 400ms 误触发 endCall"），
 * 但 `onUserOffline` 现已改为 no-op，该过滤的原始动机已不存在；
 * 而精确匹配会在被叫因服务端未回传 `receiverUid` 而用回退 UID 加入时
 * 丢弃其 `onUserJoined`，导致主叫干等到 65s 超时——表现为"打不通"。
 *
 * 频道 channelId 由主叫按时间戳生成、每次通话唯一，不存在跨通话串扰用户，
 * 故移除精确匹配过滤是安全的，且与旧版一致。
 */
fun shouldAcceptRemoteUserJoined(remoteUid: Long, localUid: Long?): Boolean {
    if (localUid != null && remoteUid == localUid) return false
    return true
}
