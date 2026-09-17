package cn.appia.im.core.network.rest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** TS restClient.ts:9 —— i18n key，401 登出后 UI 层据此提示。 */
const val AUTH_SESSION_EXPIRED_ERROR = "auth_session_expired"

/**
 * TS restClient.ts:82 —— 401 时抛出，message 即 i18n key。
 * 继承 IOException：OkHttp 5 会把 interceptor 抛的非 IOException 包成
 * `IOException("canceled due to …")`，网络层失败走 IOException 通道才能原样穿透。
 */
class AuthSessionExpiredException : IOException(AUTH_SESSION_EXPIRED_ERROR)

/**
 * 非 401 的 REST 错误（对应 TS restClient.ts:90 的 Error）。
 * [status] = HTTP 状态码（TS `e.status` 等价通道，isRetryableError 以 5xx 判瞬时；缺失视作不可重试）。
 * [success] = 响应体显式 `success:false`（总纲 §4.3-5 业务拒绝通道：retryPolicy 中先于 status
 * 判定短路——5xx+业务拒绝不重试；null = 响应体无显式标记）。
 */
class ApiException(
    message: String,
    val status: Int? = null,
    val success: Boolean? = null,
) : IOException(message)

/** TS services/auth/orgSwitchInProgress.ts：组织换票期间 401 不触发登出。 */
object OrgSwitchState {
    private val inProgress = AtomicBoolean(false)

    fun begin() {
        inProgress.set(true)
    }

    fun end() {
        inProgress.set(false)
    }

    fun isInProgress(): Boolean = inProgress.get()
}

/**
 * TS authActions.logout() 的缝：401（且非组织切换中）时发事件，M1 由 authStore 订阅后登出。
 * 注意：无 replay——订阅者未挂时事件直接丢弃，M1 authStore 须尽早订阅；
 * extraBufferCapacity 仅兜底「已有订阅者但暂未消费」（慢消费不丢）。
 */
object SessionExpiredBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val events: SharedFlow<Unit> = _events

    fun emit() {
        _events.tryEmit(Unit)
    }
}
