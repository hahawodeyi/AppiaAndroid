package cn.appia.im.core.network.rest

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** REST 层组装：base URL {host}/api/v1/ + 鉴权/401 interceptor + kotlinx-serialization。 */
object RetrofitFactory {
    fun create(host: String, authProvider: () -> AuthSession?): Retrofit {
        val baseUrl = host.trimEnd('/') + "/api/v1/" // TS restClient.ts:40 + normalizeLoginHost
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authProvider))
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
