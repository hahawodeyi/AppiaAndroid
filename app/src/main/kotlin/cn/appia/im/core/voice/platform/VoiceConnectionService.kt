package cn.appia.im.core.voice.platform

import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Telecom 自管 ConnectionService（M10-T9 GREENFIELD——RN 无对应物）。
 *
 * 系统 bind（BIND_TELECOM_CONNECTION_SERVICE）后由 Telecom 回调 [onCreateIncomingConnection]
 * （真机：[TelecomCallBridge.reportIncomingCall] 的 addNewIncomingCall 触发；Robolectric：
 * shadow 的 MANUAL/ALLOW_ALL 模式模拟）。Connection 侧：
 * - setConnectionProperties(PROPERTY_SELF_MANAGED) + setAudioModeIsVoip(true)：官方自管模板
 *   （自管呼叫不进系统 InCallUI，仅锁屏/状态栏露出 + 系统铃声）。
 * - onAnswer/onReject/onDisconnect → [TelecomCallBridge] 静态桥投递到 reportIncomingCall
 *   注册的 listener（callId 从 ConnectionRequest.extras 解——嵌套 EXTRA_INCOMING_CALL_EXTRAS
 *   优先，顶层兜底——shadow 拷贝整 bundle、真机 Telecom 只透传嵌套）。
 * - **主动呼出不上报**（spec §0 裁定）：onCreateOutgoingConnection 返回 null（系统不会
 *   对自管 account 发起 placeCall，此覆盖仅防御性）。
 */
class VoiceConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val callId = request.callId()
        // callId 解析失败（extras 缺失/空串）→ 官方模板 createCanceledConnection，
        // 不注册 listener/connection（"" 键会污染注册表并闩住 reportedCallId）
        if (callId.isBlank()) return Connection.createCanceledConnection()
        val connection = VoiceConnection(callId)
        TelecomCallBridge.registerConnection(callId, connection)
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? = null // 主动呼出不上报 Telecom（spec §0 裁定）

    private fun ConnectionRequest?.callId(): String {
        val nested = this?.extras
            ?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        return nested?.getString(TelecomCallBridge.KEY_CALL_ID)
            ?: this?.extras?.getString(TelecomCallBridge.KEY_CALL_ID).orEmpty()
    }
}

/**
 * 自管来电 Connection：状态迁移全经 [TelecomCallBridge] 静态桥（callId 锚定）。
 */
private class VoiceConnection(private val callId: String) : Connection() {

    init {
        // setConnectionProperties API 25+（MeetingFloatWindow.kt:148 门控先例）；API<25 跳过
        // （装饰性标记，连接本体状态迁移不受影响）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) setConnectionProperties(PROPERTY_SELF_MANAGED)
        setAudioModeIsVoip(true)
        setRinging()
    }

    override fun onAnswer() {
        TelecomCallBridge.dispatchAnswer(callId)
    }

    override fun onReject() {
        TelecomCallBridge.dispatchReject(callId)
        // 与 onDisconnect 路径对称：清 listener/connection 注册 + reported 旗标
        // （否则 reject 闩住 reportedCallId 连坐后续来电，泄漏注册表条目）
        TelecomCallBridge.connectionDisconnected(callId)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        TelecomCallBridge.connectionDisconnected(callId)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }
}
