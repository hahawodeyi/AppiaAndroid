package cn.appia.im.feature.settings

import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.domain.session.RealtimeSessionManager
import java.io.File

/**
 * 清除本地缓存（M5-T9，RN services/settings/clearLocalCache.ts 逐序对照）：
 * 1. `teardownRealtimeSession()` —— [RealtimeSessionManager.teardown]（generation 递增使在途
 *    bootstrap body 自行中止——teardown 与 re-bootstrap 间的防竞态由 manager 内建，本类复用）
 * 2. `resetServerDatabaseByUrl(serverUrl)` —— [DatabaseManager.resetDatabase]（关库+删文件）
 * 3. `clearUploadsDir()`（best-effort 失败不阻断，RN try/catch 同）
 * 4. `setActiveServerDatabase(serverUrl)` —— [DatabaseManager.switchDatabase]
 * 5. `bootstrapAuthenticatedRealtime(serverUrl, token, {connectAndResume: true})` ——
 *    [RealtimeSessionManager.bootstrap]（teardown 已清 sessionKey → 短路不命中，必跑全量）
 *
 * 未登录（serverUrl/token 缺失）抛 IllegalStateException（RN 'clearLocalCache: not authenticated'）。
 * uploads 目录 = `context.cacheDir/uploads`（PendingAttachments/RoomAnnouncement 上传暂存同位）。
 */
class ClearLocalCache(
    private val store: AuthSessionStore,
    private val dbManager: DatabaseManager,
    private val manager: RealtimeSessionManager,
) {
    /** uploads 暂存目录（RoomScreen/RoomAnnouncementScreen 写入路径同位）；测试缝注入 tempDir。 */
    internal var uploadsDir: File? = null

    suspend fun clear() {
        val session = store.load()
        val serverUrl = session?.serverUrl?.trim()
        val token = session?.token?.trim()
        if (session == null || serverUrl.isNullOrEmpty() || token.isNullOrEmpty()) {
            throw IllegalStateException("clearLocalCache: not authenticated")
        }

        manager.teardown() // 步骤 1：停流+断连（generation 递增中止在途 body）
        dbManager.resetDatabase(dbManager.normalizeServer(serverUrl)) // 步骤 2：删库

        runCatching { uploadsDir?.takeIf { it.exists() }?.deleteRecursively() } // 步骤 3：best-effort

        dbManager.switchDatabase(serverUrl) // 步骤 4：重挂业务库
        manager.bootstrap(serverUrl, token, session.user.id) // 步骤 5：重引导（首包房间同步在 bootstrap 内）
    }
}
