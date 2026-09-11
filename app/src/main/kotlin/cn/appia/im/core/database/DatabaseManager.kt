package cn.appia.im.core.database

import android.content.Context
import androidx.room.Room

/**
 * 对照 appiaMobile `src/database/db.ts`：以规范化 server 串区分 SQLite 文件，
 * 未选服 / 已登出使用 `__prelogin__` 占位库。
 * 多组织切换的 generation 机制（M1）不在本类范围。
 */
class DatabaseManager(private val context: Context) {

    private val cache = mutableMapOf<String, AppiaDatabase>()

    /** 当前业务库（对应 db.ts `database.active`），初始为占位库。 */
    var active: AppiaDatabase = databaseFor(PRELOGIN_NORMALIZED)
        private set

    /**
     * 取或创建某 normalized server（或占位）对应的库，缓存实例。
     * db 文件名：占位库 `appia_prelogin.db`（与 db.ts 一致），其余 `appia_<normalizedServer>.db`。
     */
    fun databaseFor(normalizedServer: String): AppiaDatabase =
        cache.getOrPut(normalizedServer) {
            Room.databaseBuilder(context, AppiaDatabase::class.java, dbNameFor(normalizedServer)).build()
        }

    /** 对应 db.ts `setActiveServerDatabase`：换服时切换当前业务库（UI 通知由 M1 处理）。 */
    fun switchDatabase(server: String): AppiaDatabase {
        active = databaseFor(normalizeServer(server))
        return active
    }

    /** 登出清理：关闭并清空缓存，删除全部 `appia_*` 库文件（含 -wal/-shm）。 */
    fun resetAll() {
        cache.values.forEach { it.close() }
        cache.clear()
        active = databaseFor(PRELOGIN_NORMALIZED)
        context.getDatabasePath(dbNameFor(PRELOGIN_NORMALIZED)).parentFile
            ?.listFiles { f -> f.name.startsWith("appia_") }
            ?.forEach { it.delete() }
    }

    companion object {
        const val PRELOGIN_NORMALIZED = "__prelogin__"

        /** 未登录占位库文件名（与 db.ts `appia_prelogin` 一致，补 `.db` 后缀）。 */
        const val PRELOGIN_DB_NAME = "appia_prelogin.db"

        fun dbNameFor(normalizedServer: String): String =
            if (normalizedServer == PRELOGIN_NORMALIZED) PRELOGIN_DB_NAME else "appia_$normalizedServer.db"
    }

    /**
     * 规范化 server 串：去协议、路径 slash→dot；空串回占位库。
     * 与 db.ts `normalizeServerToDbFileBase` 相同规则。
     */
    fun normalizeServer(server: String): String {
        val t = server.trim()
        if (t.isEmpty()) return PRELOGIN_NORMALIZED
        return t.replace(Regex("""(^\w+:|^)//"""), "").replace("/", ".")
    }
}
