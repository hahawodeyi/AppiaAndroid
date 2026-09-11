package cn.appia.im.core.network.ddp

import kotlinx.serialization.json.JsonObject

/**
 * 连接生命周期回调，对齐 TS emitter 上的 `connecting` / `connected` / `ping` / `close` 事件
 * （ddpClient.ts:134/188/289/206）。
 *
 * 这些事件同样会通过 [DdpClient.onStreamData] 以同名 key 分发（RN 版 session.ts 即以
 * `onStreamData('connected', ...)` 方式消费），此接口只是强类型便捷入口，二选一即可。
 */
interface DdpEventListener {
    fun onConnecting() {}

    fun onConnected() {}

    /** 收到服务端 `{msg:"ping"}`（已自动回 pong）。 */
    fun onPing(message: JsonObject) {}

    fun onClose(event: DdpCloseEvent) {}
}
