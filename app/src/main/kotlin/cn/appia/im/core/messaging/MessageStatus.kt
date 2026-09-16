package cn.appia.im.core.messaging

/**
 * 消息发送状态机常量（逐值转录 appiaMobile `src/database/constants/messagesStatus.ts`）：
 * SENT=0 / QUEUED=1 / ERROR=2 / SENDING=3。数据库存数值（messages.status 列，Double?；
 * 服务端消息 status 恒为 null）。
 *
 * **只许 SendOrchestrator 写**：MessageUpsert.update 沿用 prev.status、create 为 null（T6 已钉）。
 */
object MessageStatus {
    const val SENT = 0
    const val QUEUED = 1
    const val ERROR = 2
    const val SENDING = 3
}
