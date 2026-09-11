package cn.appia.im.core.network

import cn.appia.im.core.network.ddp.hostToWs

/**
 * server 串规范化（M1 session 层入口）。
 * RN 四处口径不一（db.ts:41-47 / auth.ts:21 / ddpClient.ts:62-69 / restClient.ts:40），统一取严：
 * 会话/连接用的 host 一律 trim + 去全部尾斜杠；本地库 key 复刻 db.ts 正则语义不动。
 */
object ServerUrl {

    /** 连接用规范化：trim + 去**全部**尾斜杠（`https://a.cn///` → `https://a.cn`）。 */
    fun normalizeServer(url: String): String = url.trim().trimEnd('/')

    /**
     * 本地库文件 key（逐字符复刻 RN db.ts:41-47 `normalizeServerToDbFileBase`）：
     * `(^\w+:|^)\/\/` 去协议、全部 `/` → `.`；例 `https://appia.cn` → `appia.cn`。
     * 空串返回空串（占位库名 `__prelogin__` 的映射归 DatabaseManager）。
     */
    fun normalizeServerToDbKey(url: String): String =
        url.trim()
            .replace(Regex("""(^\w+:|^)//"""), "")
            .replace("/", ".")

    /** WS 地址推导：先规范化再复用 DDP hostToWs（https→wss / http→ws，拼 /websocket）。 */
    fun wsUrl(url: String): String = hostToWs(normalizeServer(url), useSsl = null)
}
