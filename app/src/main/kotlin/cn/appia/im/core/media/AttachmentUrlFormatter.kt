package cn.appia.im.core.media

/**
 * 逐行为移植 appiaMobile/src/utils/formatAttachmentUrl.ts：
 * 附件 URL 补 `rc_uid`/`rc_token` 鉴权参数；Appia 主体（appiaBaseUrl）路径下
 * `/file-upload` → `/file-proxy`（外链代理）；file-upload URL 中的 `#` 剔除（服务端
 * 会把带锚点的路径存进 title_link，原样请求 404）。
 */
object AttachmentUrlFormatter {

    /** RN formatAppiaUrl：裸域名补 https。 */
    fun formatAppiaUrl(appiaBaseUrl: String): String =
        if (appiaBaseUrl.startsWith("http")) appiaBaseUrl else "https://$appiaBaseUrl"

    fun format(
        attachmentUrl: String?,
        userId: String,
        token: String,
        server: String,
        appiaBaseUrl: String? = null,
    ): String {
        if (attachmentUrl == null) return ""

        // Handle # in file-upload URLs
        var url = attachmentUrl
        if (url.contains('#') && url.contains("file-upload")) {
            url = url.replace("#", "")
        }

        // Already has auth params
        if (url.contains("rc_token")) {
            return encodeUri(url)
        }

        if (appiaBaseUrl != null) {
            val attachmentServer = formatAppiaUrl(appiaBaseUrl)
            if (url.startsWith("http")) {
                return encodeUri(
                    "${url}?rc_uid=$userId&rc_token=$token".replace("/file-upload", "/file-proxy"),
                )
            }
            return encodeUri(
                "${attachmentServer}${url}?rc_uid=$userId&rc_token=$token".replace(
                    "/file-upload",
                    "/file-proxy",
                ),
            )
        }

        // Absolute URL
        if (url.startsWith("http")) {
            return encodeUri("${url}?rc_uid=$userId&rc_token=$token")
        }

        // Relative URL
        return encodeUri("${server}${url}?rc_uid=$userId&rc_token=$token")
    }

    /**
     * JS encodeURI 等价：不编码 `A-Za-z0-9 ; , / ? : @ & = + $ - _ . ! ~ * ' ( ) #`，
     * 其余 ASCII 与所有非 ASCII 字符按 UTF-8 字节百分号编码（大写十六进制）。
     * （URLEncoder.encode 表差异大：空格→+、`*` 不编码等，不能替代。）
     */
    internal fun encodeUri(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/?:@&=+\$-_.!~*'()#"
        return buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val ch = b.toInt().toChar()
                if (b.toInt() < 0x80 && safe.indexOf(ch) >= 0) {
                    append(ch)
                } else {
                    append('%')
                    append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
    }
}
