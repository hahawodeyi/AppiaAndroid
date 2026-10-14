package cn.appia.im.core.voice.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Telecom 自管来电桥（M10-T9 GREENFIELD——RN 无对应物；AA 原生新增）。
 *
 * 职责（spec §0 裁定：**主动呼出不上报 Telecom**，仅来电上报 + 接通 setActive + 挂断
 * setDisconnected）：
 * - [reportIncomingCall]：注册 answer/reject listener 后 `TelecomManager.addNewIncomingCall`
 *   （自管 PhoneAccount，extras 带 [TelecomManager.EXTRA_INCOMING_CALL_EXTRAS] 嵌套 bundle）。
 *   失败（未注册 account / 系统 SecurityException）一律 false 降级，**不抛**——上层回退
 *   in-app 铃声/通知路径。
 * - [setActive]/[setDisconnected]：接通/挂断时同步 Connection 状态。
 * - [hasActiveIncomingReport]：来电已成功上报 Telecom 的全局查询（Task 12 装配可用；
 *   SELF_MANAGED 系统侧零铃声——铃声归属见 [AndroidVoiceRinger] KDoc，ringer 不再据此去重）。
 *
 * 静态桥（companion listener registry keyed by callId）：[VoiceConnectionService] 的
 * onAnswer/onReject 回调经 [dispatchAnswer]/[dispatchReject] 投递到 reportIncomingCall
 * 注册的 listener（Task 12 装配接 Controller）。Robolectric 测试只打桩桥逻辑，不触达
 * 真实 Telecom bind。
 */
class TelecomCallBridge(
    private val context: Context,
    private val telecomManager: TelecomManager,
) {

    /**
     * 上报来电。返回 false = 降级（未授权/未注册 phone account/系统拒绝），调用方转
     * in-app 路径，不抛异常。
     */
    fun reportIncomingCall(
        callId: String,
        callerName: String,
        onAnswer: () -> Unit,
        onReject: () -> Unit,
    ): Boolean {
        // 自管 account 无需用户 opt-in：注册即视为可用（未注册 → addNewIncomingCall 必
        // SecurityException，此处前置省一次系统调用）
        if (!hasAccountPermission()) return false
        listeners[callId] = Listener(onAnswer, onReject)
        // callId/callerName 双落：顶层供 Robolectric shadow（整 bundle 拷贝进
        // ConnectionRequest.extras），EXTRA_INCOMING_CALL_EXTRAS 嵌套供真机 Telecom 解包
        val callExtras = Bundle().apply {
            putString(KEY_CALL_ID, callId)
            putString(KEY_CALLER_NAME, callerName)
        }
        val extras = Bundle().apply {
            putAll(callExtras)
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras)
        }
        return try {
            telecomManager.addNewIncomingCall(phoneAccountHandle(context), extras)
            reportedCallId = callId
            true
        } catch (e: Exception) {
            Log.w(TAG, "addNewIncomingCall denied, fall back to in-app path", e)
            listeners.remove(callId)
            false
        }
    }

    /** 接通（Controller accept 后同步系统侧状态；未知 callId no-op）。 */
    fun setActive(callId: String) {
        connections[callId]?.setActive()
    }

    /** 挂断/拒绝收尾：同步 Connection Disconnected、清 listener 与上报旗标。 */
    fun setDisconnected(callId: String) {
        connections.remove(callId)?.apply {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        listeners.remove(callId)
        if (reportedCallId == callId) reportedCallId = null
    }

    /** 自管 phone account 是否已注册可用（未注册 → 上报必失败）。 */
    fun hasAccountPermission(): Boolean = try {
        telecomManager.getPhoneAccount(phoneAccountHandle(context)) != null
    } catch (e: Exception) {
        Log.w(TAG, "getPhoneAccount failed", e)
        false
    }

    /** 引导用户到系统「电话帐户」设置页（ACTION_CHANGE_PHONE_ACCOUNTS Settings intent）。 */
    fun requestAccountPermission(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
        }.onFailure { Log.w(TAG, "open phone account settings failed", it) }
    }

    private class Listener(val onAnswer: () -> Unit, val onReject: () -> Unit)

    companion object {
        private const val TAG = "TelecomCallBridge"
        const val KEY_CALL_ID = "appia_voice_call_id"
        const val KEY_CALLER_NAME = "appia_voice_caller_name"

        /** listener registry（ConnectionService 回调投递目标）keyed by callId。 */
        private val listeners = ConcurrentHashMap<String, Listener>()

        /** onCreateIncomingConnection 建立的 Connection（setActive/setDisconnected 操作对象）。 */
        private val connections = ConcurrentHashMap<String, Connection>()

        /** 最近一次成功上报 Telecom 的 callId（null = 无活动上报；Task 12 装配查询面）。 */
        @Volatile
        private var reportedCallId: String? = null

        fun hasActiveIncomingReport(): Boolean = reportedCallId != null

        /**
         * 自管 PhoneAccount 注册（Application.onCreate 调，调用方自裹 runCatching 防启动崩溃；
         * 重复注册同 handle 幂等覆盖）。
         */
        fun ensurePhoneAccountRegistered(context: Context) {
            val tm = context.getSystemService(TelecomManager::class.java) ?: return
            tm.registerPhoneAccount(
                // 官方自管模板：Builder + CAPABILITY_SELF_MANAGED（无公开 PROPERTY_SELF_MANAGED
                // 构造——brief 所指 6 参构造已 hidden，Builder 为 SDK 公开面）
                PhoneAccount.Builder(phoneAccountHandle(context), "Appia")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build(),
            )
        }

        internal fun phoneAccountHandle(context: Context): PhoneAccountHandle =
            PhoneAccountHandle(
                ComponentName(context, VoiceConnectionService::class.java),
                "appia_voice",
            )

        // ── 静态桥：ConnectionService 回调入口（VoiceConnection 调用） ──────────────

        internal fun dispatchAnswer(callId: String) {
            listeners[callId]?.onAnswer?.invoke()
        }

        internal fun dispatchReject(callId: String) {
            listeners[callId]?.onReject?.invoke()
        }

        internal fun registerConnection(callId: String, connection: Connection) {
            connections[callId] = connection
        }

        /** 系统 UI 侧挂断（onDisconnect）：仅收尾注册表，无 listener 槽位（brief 未定义）。 */
        internal fun connectionDisconnected(callId: String) {
            connections.remove(callId)
            listeners.remove(callId)
            if (reportedCallId == callId) reportedCallId = null
        }

        /** 测试隔离：清空静态桥状态。 */
        fun clearIncomingReported() {
            listeners.clear()
            connections.clear()
            reportedCallId = null
        }
    }
}
