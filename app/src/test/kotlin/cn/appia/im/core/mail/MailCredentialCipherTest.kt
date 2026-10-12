package cn.appia.im.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.SignatureException
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource.PSpecified

/**
 * RSA-OAEP 凭证加密互操作（坑 1 钉死）：WebCrypto `RSA-OAEP`+SHA-256 = OAEP-SHA256 + **MGF1-SHA256**。
 * 测试密钥对自产 → SPKI PEM → [MailCredentialCipher.encryptPassword] 加密 → 显式参数私钥解密回读断言；
 * 并以「显式 MGF1-SHA1 解密必须失败」反向钉死 MGF1 摘要（Android 部分 provider 的 provider-default
 * 陷阱——裸 transformation 不 pin spec 时密文与服务器不互认）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MailCredentialCipherTest {

    /** 测试密钥对 + SPKI PEM（与服务器 GET /accounts/credential-public-key 下发同构）。 */
    private fun keyPair(size: Int = 2048): Pair<String, java.security.PrivateKey> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(size) }.generateKeyPair()
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.public.encoded))
            append("\n-----END PUBLIC KEY-----\n")
        }
        return pem to kp.private
    }

    /** 显式 OAEP(SHA-256, MGF1-SHA256) 私钥解密——服务器（WebCrypto 语义）侧的解密等价物。 */
    private fun decryptSha256(ciphertext: ByteArray, privateKey: java.security.PrivateKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSpecified.DEFAULT))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @Test
    fun `roundtrip - encrypt then explicit-OAEP decrypt reads back plaintext`() {
        val (pem, priv) = keyPair()
        val plaintext = "S3cret!-Passw0rd"

        val encrypted = MailCredentialCipher.encryptPassword(plaintext, pem)

        assertEquals(plaintext, decryptSha256(Base64.getDecoder().decode(encrypted), priv))
    }

    @Test
    fun `ciphertext is base64 of key-sized block`() {
        val (pem, _) = keyPair(2048)
        val bytes = Base64.getDecoder().decode(MailCredentialCipher.encryptPassword("x", pem))
        assertEquals(256, bytes.size)
    }

    @Test
    fun `pin - decrypt with MGF1-SHA1 legacy spec must fail (MGF1 digest is SHA-256 not provider default)`() {
        val (pem, priv) = keyPair()
        val encrypted = MailCredentialCipher.encryptPassword("pin-mgf1", pem)

        val legacy = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        legacy.init(Cipher.DECRYPT_MODE, priv, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSpecified.DEFAULT))
        val err = assertThrows(Exception::class.java) {
            legacy.doFinal(Base64.getDecoder().decode(encrypted))
        }
        assertTrue(
            "expected padding failure, got $err",
            err is BadPaddingException || err.cause is BadPaddingException || err is SignatureException,
        )
    }

    @Test
    fun `pin - ciphertexts under MGF1-SHA256 and MGF1-SHA1 specs differ for same plaintext`() {
        val (pem, _) = keyPair()
        val key = MailCredentialCipher.importPublicKey(pem)

        fun encrypt(mgf: MGF1ParameterSpec): ByteArray {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", mgf, PSpecified.DEFAULT))
            return cipher.doFinal("same".toByteArray())
        }
        assertNotEquals(encrypt(MGF1ParameterSpec.SHA256).toList(), encrypt(MGF1ParameterSpec.SHA1).toList())
    }

    @Test
    fun `pem parsing - strips headers, CRLF and embedded whitespace`() {
        val (pem, priv) = keyPair()
        val noisy = pem.replace("\n", "\r\n  ")
        val plaintext = "whitespace-tolerant"

        val fromNoisy = MailCredentialCipher.encryptPassword(plaintext, noisy)
        assertEquals(plaintext, decryptSha256(Base64.getDecoder().decode(fromNoisy), priv))
    }

    @Test
    fun `pem parsing - garbage input rejected`() {
        assertThrows(Exception::class.java) { MailCredentialCipher.importPublicKey("not-a-pem") }
        assertThrows(IllegalArgumentException::class.java) { MailCredentialCipher.importPublicKey("-----BEGIN PUBLIC KEY----- -----END PUBLIC KEY-----") }
    }

    @Test
    fun `payload - email plaintext, only password encrypted`() {
        val (pem, priv) = keyPair()
        val payload = MailCredentialCipher.buildEncryptedCredentialPayload("user@x.com", "pwd123", pem)

        assertEquals("user@x.com", payload.email)
        assertEquals("pwd123", decryptSha256(Base64.getDecoder().decode(payload.encryptedPassword), priv))
    }

    @Test
    fun `cache - same keyVersion fetches once, refetch after invalidate picks up new version`() {
        val calls = AtomicInteger()
        var version = 1
        val cache = MailCredentialKeyCache()
        val fetch = {
            calls.incrementAndGet()
            CredentialPublicKeyResponse(publicKey = "PEM-v${version}", keyVersion = version)
        }

        val first = cache.load(fetch)
        assertEquals(1, first.keyVersion)
        assertEquals("PEM-v1", first.publicKeyPem)
        // 同版本命中缓存——不再 fetch
        assertEquals(first, cache.load(fetch))
        assertEquals(1, calls.get())

        // 换钥失效（绑定被拒路径）→ 重取到 v2 并落账
        cache.invalidate()
        version = 2
        val second = cache.load(fetch)
        assertEquals(2, second.keyVersion)
        assertEquals("PEM-v2", second.publicKeyPem)
        assertEquals(2, calls.get())
        assertEquals(second, cache.load(fetch))
        assertEquals(2, calls.get())
    }

    @Test
    fun `cache - concurrent cold loads share one fetch (in-flight dedup)`() {
        val calls = AtomicInteger()
        val cache = MailCredentialKeyCache()
        val fetch = {
            calls.incrementAndGet()
            Thread.sleep(100) // 拉长 fetch 窗口，让并发调用方都落进锁内同一次取钥
            CredentialPublicKeyResponse(publicKey = "PEM", keyVersion = 7)
        }

        val results = (1..4).map {
            Thread { cache.load(fetch) }.apply { start() }
        }
        results.forEach { it.join(5_000) }

        assertEquals(1, calls.get())
        assertEquals(7, cache.current()?.keyVersion)
    }

    @Test
    fun `cache - failed fetch leaves no poisoned state, next load retries`() {
        val calls = AtomicInteger()
        val cache = MailCredentialKeyCache()
        val fetch = {
            if (calls.incrementAndGet() == 1) throw java.io.IOException("network down")
            CredentialPublicKeyResponse(publicKey = "PEM", keyVersion = 3)
        }

        assertThrows(java.io.IOException::class.java) { cache.load(fetch) }
        assertNull(cache.current())

        assertEquals(3, cache.load(fetch).keyVersion)
        assertEquals(2, calls.get())
    }
}
