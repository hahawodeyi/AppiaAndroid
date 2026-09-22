package cn.appia.im.feature.roominfo

import java.util.concurrent.ConcurrentHashMap

/**
 * 自退待删标记（RN roomAccessLoss.ts pendingSelfLeaveRids :11,24-34）。
 * mark → leave 成功后 subscription removed 事件到来时据「self」提示（消费归 T10
 * roomAccessLoss 收口——本文件只留标记读写，不做栈清理）。
 */
private val pendingSelfLeaveRids: MutableSet<String> = ConcurrentHashMap.newKeySet()

fun markPendingSelfLeave(rid: String) {
    pendingSelfLeaveRids.add(rid)
}

fun clearPendingSelfLeaveForRid(rid: String) {
    pendingSelfLeaveRids.remove(rid)
}

fun isPendingSelfLeave(rid: String): Boolean = rid in pendingSelfLeaveRids
