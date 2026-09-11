package cn.appia.im.core.network.rest

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.ServerUrl
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * REST 层组装：base URL {host}/api/v1/ + 鉴权/401 interceptor + kotlinx-serialization。
 * 共享 RocketHttp 连接池/调度器，仅按需派生 client（newBuilder 不复制池）；
 * timeoutMs 非空时按 call 设置 callTimeout（对照 RN restClient.ts:44-50 per-request 超时，组织切换 30s）。
 */
object RetrofitFactory {
    fun create(host: String, authProvider: () -> AuthSession?, timeoutMs: Long? = null): Retrofit {
        val baseUrl = ServerUrl.normalizeServer(host) + "/api/v1/" // TS restClient.ts:40 + normalizeLoginHost
        val client = RocketHttp.client.newBuilder()
            .addInterceptor(AuthInterceptor(authProvider))
            .apply { timeoutMs?.let { callTimeout(it, TimeUnit.MILLISECONDS) } }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType()),
            )
            .build()
    }
}
