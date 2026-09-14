package cn.appia.im.core.push

import android.content.Context
import android.provider.Settings
import android.util.Log
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.datastore.MmkvKvStore
import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.ServerUrl
import cn.appia.im.core.network.rest.ApiException
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Singleton

/** RN pushService.ts:36-37（MMKV 实例 `push-storage` + 键名）。 */
internal const val PUSH_DEVICE_TOKEN_KEY = "push_device_token"

private const val TAG = "push"
private const val APP_NAME = "cn.appia.im" // RN pushService.ts:170

/**
 * 推送 token 注册埋点（M1 最小实现；M6 换阿里云 SDK，deviceId 现为 ANDROID_ID 占位）。
 * 对照 appiaMobile/src/services/notification/pushService.ts：
 * - register :150-175：deviceId → 先存 `push_device_token`（:163，失败不回滚）→ POST push.token
 *   `{value, type:'gcm', appName}`（Android 一律 'gcm'，:169）；带登录会话鉴权头（RN sdk.post 语义）。
 * - unregister :178-188：无存量 token 不发（:179-180）；DELETE `{token}` 不带鉴权头
 *   （RN 调用点在 teardownRealtimeSession 之后，session.ts:689 已 clearRestSession）；成功才移除（:184）。
 * - 两方向失败一律 log 吞掉（:172-174/:185-187），fire-and-forget 由 AuthRepository 编排。
 */
class PushTokenRegistrar(
    private val kv: KvStore,
    private val client: OkHttpClient,
    private val deviceIdProvider: () -> String?,
) {

    /** 登录成功后注册（RN authStore.ts:105 调用点：不 await、不阻塞登录）。 */
    suspend fun register(serverUrl: String, authToken: String, userId: String) {
        val deviceId = resolveDeviceId()
        if (deviceId.isNullOrEmpty()) {
            Log.w(TAG, "No deviceId, skipping registration") // RN :159
            return
        }
        kv.putString(PUSH_DEVICE_TOKEN_KEY, deviceId)
        try {
            wire(
                serverUrl,
                "POST",
                buildJsonObject {
                    put("value", deviceId)
                    put("type", "gcm")
                    put("appName", APP_NAME)
                },
                authToken = authToken,
                authUserId = userId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "registerPushToken failed", e) // RN :173
        }
    }

    /** 登出时注销（RN authStore.ts:159 调用点）。 */
    suspend fun unregister(serverUrl: String) {
        val token = kv.getString(PUSH_DEVICE_TOKEN_KEY, "").takeIf { it.isNotEmpty() } ?: return
        try {
            wire(
                serverUrl,
                "DELETE",
                buildJsonObject { put("token", token) },
                authToken = null, // RN logout 时 REST 会话已 teardown，无鉴权头
                authUserId = null,
            )
            kv.remove(PUSH_DEVICE_TOKEN_KEY) // 成功才移除（RN :184）
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "unregisterPushToken failed", e) // RN :186
        }
    }

    private fun resolveDeviceId(): String? = try {
        deviceIdProvider()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get DeviceID", e) // RN :155
        null
    }

    /** RN rocketRestRequest restClient.ts:35-99 的 push.token 单端点子集（JSON body）。 */
    private fun wire(
        serverUrl: String,
        method: String,
        body: JsonObject,
        authToken: String?,
        authUserId: String?,
    ) {
        val url = "${ServerUrl.normalizeServer(serverUrl)}/api/v1/push.token"
        val request = Request.Builder().url(url)
            .method(method, body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                authToken?.let { header("X-Auth-Token", it) }
                authUserId?.let { header("X-User-Id", it) }
            }
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException("[rocket] REST $method push.token failed: HTTP ${resp.code}")
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    /** RN pushService.ts:36：独立 MMKV 实例 `push-storage`；deviceId 为 ANDROID_ID 占位（M6 换阿里云）。 */
    @Provides
    @Singleton
    fun providePushTokenRegistrar(@ApplicationContext context: Context): PushTokenRegistrar =
        PushTokenRegistrar(
            kv = MmkvKvStore(MMKV.mmkvWithID("push-storage")),
            client = RocketHttp.client,
            deviceIdProvider = {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            },
        )
}
