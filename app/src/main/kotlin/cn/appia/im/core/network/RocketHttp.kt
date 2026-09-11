package cn.appia.im.core.network

import okhttp3.OkHttpClient

/**
 * 进程级共享 OkHttpClient：连接池/调度器全进程一份，DdpClient 与 RetrofitFactory.create 均注入它。
 * 需要差异化配置（如 per-call 超时）时用 `newBuilder()` 派生——连接池/调度器仍与本单例共享。
 */
object RocketHttp {
    val client: OkHttpClient = OkHttpClient.Builder().build()
}
