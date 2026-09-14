package cn.appia.im.domain.session

/**
 * 全局 DDP 流订阅表常量（RN session.ts:389-417 的 topic / eventName 字面量，逐字对齐）。
 * notify-user 的事件名带 uid 前缀（`{uid}/<event>`，RN :398-400）。
 */
object StreamNames {
    // ---- topic（DDP sub 的 name）----
    const val NOTIFY_USER = "stream-notify-user"
    const val NOTIFY_LOGGED = "stream-notify-logged"
    const val ROLES = "stream-roles"
    const val NOTIFY_ALL = "stream-notify-all"

    // ---- eventName（sub.params[0]）----
    const val SUBSCRIPTIONS_CHANGED = "subscriptions-changed"
    const val ROOMS_CHANGED = "rooms-changed"
    const val USER_DATA = "userData"
    const val PERMISSIONS_CHANGED = "permissions-changed"
    const val PUBLIC_SETTINGS_CHANGED = "public-settings-changed"
    const val ROLES_EVENT = "roles"

    /** notify-user 事件名 `{uid}/<event>`（RN session.ts:398-400）。 */
    fun userEvent(userId: String, event: String): String = "$userId/$event"
}
