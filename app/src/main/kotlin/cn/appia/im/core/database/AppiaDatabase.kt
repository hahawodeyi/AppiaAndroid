package cn.appia.im.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import cn.appia.im.core.database.dao.ChatDao
import cn.appia.im.core.database.dao.CustomEmojiDao
import cn.appia.im.core.database.dao.MessageDao
import cn.appia.im.core.database.dao.RoomDao
import cn.appia.im.core.database.dao.SettingDao
import cn.appia.im.core.database.dao.SubscriptionDao
import cn.appia.im.core.database.dao.UploadDao
import cn.appia.im.core.database.dao.UserDao
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.database.entity.CustomEmojiEntity
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.database.entity.RoomEntity
import cn.appia.im.core.database.entity.SettingEntity
import cn.appia.im.core.database.entity.SubscriptionEntity
import cn.appia.im.core.database.entity.UploadEntity
import cn.appia.im.core.database.entity.UserEntity

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB appSchema v6，8 表）。
 * Android 侧 Room 版本号从 1 重新计数，与 WatermelonDB 的 6 无关。
 * exportSchema 暂关：M1 引入迁移时再配置 room.schemaLocation。
 */
@Database(
    entities = [
        RoomEntity::class,
        SubscriptionEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        UserEntity::class,
        SettingEntity::class,
        UploadEntity::class,
        CustomEmojiEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppiaDatabase : RoomDatabase() {
    abstract fun roomDao(): RoomDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun settingDao(): SettingDao
    abstract fun uploadDao(): UploadDao
    abstract fun customEmojiDao(): CustomEmojiDao
}
