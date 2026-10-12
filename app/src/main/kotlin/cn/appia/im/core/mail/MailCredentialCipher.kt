package cn.appia.im.core.mail

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource.PSpecified

/**
 * 凭证 RSA-OAEP 加密传输（逐行为对照 appiaMobile/src/services/mail/mailCredentialTransport.ts）。
 *
 * 坑 1（本文件存在的理由）：WebCrypto `RSA-OAEP` + importKey(hash: SHA-256) = OAEP-SHA256 + **MGF1-SHA256**。
 * Android 裸 `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` 在部分 provider 下 MGF1 默认 SHA-1，
 * 与服务器（WebCrypto 语义）密文不互认——必须显式 [OAEP_PARAMETER_SPEC]。
 * 坑 2 改进：公钥缓存按 keyVersion 记账（RN 模块级缓存永不刷新、keyVersion 被忽略）；
 * 失效入口 [invalidate]（绑定被服务端以换钥拒绝时重取），in-flight 去重同 RN promise 复用。
 */
object MailCredentialCipher {

    /** WebCrypto RSA-OAEP-SHA256 语义的显式参数（MGF1 必须同为 SHA-256——坑 1）。 */
    private val OAEP_PARAMETER_SPEC =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSpecified.DEFAULT)

    /** mailCredentialTransport.ts:31-37：剥 PEM 头尾与全部空白 → base64 解码 → X.509/SPKI 导入。 */
    fun importPublicKey(publicKeyPem: String): PublicKey {
        val base64 = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        require(base64.isNotEmpty()) { "empty mail credential public key PEM" }
        val der = Base64.decode(base64, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    /** mailCredentialTransport.ts:39-51：OAEP-SHA256 + MGF1-SHA256 加密 → base64（RN ArrayBuffer→base64 同口径）。 */
    fun encryptPassword(plaintext: String, publicKeyPem: String): String {
        val key = importPublicKey(publicKeyPem)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_PARAMETER_SPEC)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** mailCredentialTransport.ts:72-80：只加密密码，email 明文随包。 */
    fun buildEncryptedCredentialPayload(
        email: String,
        password: String,
        publicKeyPem: String,
    ): EncryptedCredentialPayload =
        EncryptedCredentialPayload(email = email, encryptedPassword = encryptPassword(password, publicKeyPem))
}

/**
 * 公钥缓存（mailCredentialTransport.ts:16-70 的 keyVersion 记账版）。
 * load 命中缓存不发请求；并发冷启动共享一次 fetch（RN in-flight promise 去重同义）；
 * fetch 失败不落缓存、不残留 in-flight（TS :64-66 catch 复位同义）——下次 load 可重试。
 */
class MailCredentialKeyCache {

    /** 按服务端 keyVersion 记账的缓存条目（坑 2 改进：换钥后版本可见，不再无感失效）。 */
    data class CachedKey(val keyVersion: Int, val publicKeyPem: String)

    private val lock = Any()
    private val byVersion = mutableMapOf<Int, CachedKey>()

    fun current(): CachedKey? = synchronized(lock) { byVersion.values.maxByOrNull { it.keyVersion } }

    /**
     * 取当前公钥：缓存命中直接返回（不 fetch）；未命中同步 fetch 并按 keyVersion 落账。
     * fetch 在锁内执行——并发调用方等待同一次结果，等价 RN 的共享 in-flight promise。
     */
    fun load(fetch: () -> CredentialPublicKeyResponse): CachedKey =
        synchronized(lock) {
            current()?.let { return it }
            val info = fetch()
            val entry = CachedKey(keyVersion = info.keyVersion, publicKeyPem = info.publicKey)
            byVersion[entry.keyVersion] = entry
            entry
        }

    /** 换钥失效：全清（下次 load 重取新版本）；RN 无此入口（坑 2 缺陷），绑定被拒时调用。 */
    fun invalidate() = synchronized(lock) { byVersion.clear() }
}
