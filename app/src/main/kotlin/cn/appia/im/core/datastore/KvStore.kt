package cn.appia.im.core.datastore

import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 通用 KV 存储，SharedPreferences 接口风格。
 * MMKV 的 native .so 在 Robolectric(JVM) 下无法加载，故面向接口：
 * 单测用内存 fake（KvStoreTest），真实 MMKV 由设备/Maestro 冒烟验证。
 */
interface KvStore {
    fun putString(key: String, value: String)
    fun getString(key: String, defValue: String): String
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putInt(key: String, value: Int)
    fun getInt(key: String, defValue: Int): Int
    fun putLong(key: String, value: Long)
    fun getLong(key: String, defValue: Long): Long

    fun remove(key: String)
    fun contains(key: String): Boolean
    fun clear()
}

class MmkvKvStore(private val mmkv: MMKV) : KvStore {
    // MMKV.encode 返回 Boolean，统一收口为 Unit
    override fun putString(key: String, value: String) { mmkv.encode(key, value) }
    override fun getString(key: String, defValue: String): String =
        mmkv.decodeString(key, defValue) ?: defValue

    override fun putBoolean(key: String, value: Boolean) { mmkv.encode(key, value) }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = mmkv.decodeBool(key, defValue)

    override fun putInt(key: String, value: Int) { mmkv.encode(key, value) }
    override fun getInt(key: String, defValue: Int): Int = mmkv.decodeInt(key, defValue)

    override fun putLong(key: String, value: Long) { mmkv.encode(key, value) }
    override fun getLong(key: String, defValue: Long): Long = mmkv.decodeLong(key, defValue)

    override fun remove(key: String) { mmkv.removeValueForKey(key) }
    override fun contains(key: String): Boolean = mmkv.containsKey(key)
    override fun clear() { mmkv.clear() }
}

@Module
@InstallIn(SingletonComponent::class)
object KvStoreModule {
    /** MMKV.initialize 已在 AppiaApplication.onCreate 提前完成，这里只取默认实例。 */
    @Provides
    @Singleton
    fun provideKvStore(): KvStore = MmkvKvStore(MMKV.defaultMMKV())
}
