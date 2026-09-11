package cn.appia.im.core.database

import android.content.Context
import androidx.room.Room
import cn.appia.im.core.network.ServerUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 对照 appiaMobile `src/database/db.ts`：以规范化 server 串区分 SQLite 文件，
 * 未选服 / 已登出使用 `__prelogin__` 占位库。
 * 多组织切换的 generation 机制（M1）不在本类范围。
 *
 * 并发约定：库实例可能从 UI/会话层并发请求（组织切换与后台同步并行），
 * 缓存用 ConcurrentHashMap（getOrPut 走 computeIfAbsent，同 key 只建一个实例），
 * active 用 @Volatile 保证跨线程可见；switch 的读-写-用仍由调用方串行化（M1 session 层）。
 */
class DatabaseManager(private val context: Context) {

    private val cache = ConcurrentHashMap<String, AppiaDatabase>()

    /** 当前业务库（对应 db.ts `database.active`），初始为占位库。 */
    @Volatile
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

    /**
     * 登出清理（对照 db.ts `resetServerDatabaseByUrl`）：关闭并删除指定组织的库文件（含 -wal/-shm），
     * 若它是当前业务库则回落占位库。
     */
    fun resetDatabase(normalizedServer: String) {
        val db = cache.remove(normalizedServer) ?: return
        db.close()
        if (active === db) active = databaseFor(PRELOGIN_NORMALIZED)
        val base = context.getDatabasePath(dbNameFor(normalizedServer)).path
        listOf(base, "$base-wal", "$base-shm").forEach { File(it).delete() }
    }

    /** 全量清理：清空全部组织本地数据（慎用，仅限显式全量清理，如测试 teardown）。 */
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
     * 规范化 server 串为库 key：去协议、路径 slash→dot；空串回占位库。
     * 复用 ServerUrl.normalizeServerToDbKey（逐字符复刻 db.ts `normalizeServerToDbFileBase`）。
     */
    fun normalizeServer(server: String): String =
        ServerUrl.normalizeServerToDbKey(server).ifEmpty { PRELOGIN_NORMALIZED }
}
