package oneechan.host.platform

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Cipher names Android accepts and the JDK doesn't (en.mangago asks for AES/CBC/ZEROBYTEPADDING). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AndroidCiphersTest {
    private val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    private val ivSpec = IvParameterSpec(ByteArray(16) { (15 - it).toByte() })

    @BeforeAll
    fun install() {
        AndroidCiphers.install()
        AndroidCiphers.install() // idempotent
    }

    private fun run(
        transformation: String,
        mode: Int,
        data: ByteArray,
        provider: String? = null,
    ): ByteArray {
        val cipher = if (provider == null) Cipher.getInstance(transformation) else Cipher.getInstance(transformation, provider)
        if (transformation.contains("/ECB/")) cipher.init(mode, key) else cipher.init(mode, key, ivSpec)
        return cipher.doFinal(data)
    }

    @Test
    fun `PKCS7 padding is the JDK's PKCS5 padding`() {
        val plain = "chapter list".encodeToByteArray()
        for (mode in listOf("CBC", "ECB")) {
            val expected = run("AES/$mode/PKCS5Padding", Cipher.ENCRYPT_MODE, plain)
            assertContentEquals(expected, run("AES/$mode/PKCS7Padding", Cipher.ENCRYPT_MODE, plain))
            assertContentEquals(expected, run("AES/$mode/PKCS7PADDING", Cipher.ENCRYPT_MODE, plain), "names are case-insensitive")
            assertContentEquals(plain, run("AES/$mode/PKCS7Padding", Cipher.DECRYPT_MODE, expected))
        }
    }

    @Test
    fun `zero byte padding fills with zeros and strips them like BouncyCastle`() {
        val plain = "hello".encodeToByteArray()
        val encrypted = run("AES/CBC/ZEROBYTEPADDING", Cipher.ENCRYPT_MODE, plain)
        assertContentEquals(run("AES/CBC/NoPadding", Cipher.ENCRYPT_MODE, plain.copyOf(16)), encrypted)
        assertContentEquals(plain, run("AES/CBC/ZeroBytePadding", Cipher.DECRYPT_MODE, encrypted))

        // Block-aligned input gets a whole block of zeros (BouncyCastle's PaddedBufferedBlockCipher).
        val aligned = ByteArray(16) { 'a'.code.toByte() }
        assertEquals(32, run("AES/ECB/ZeroBytePadding", Cipher.ENCRYPT_MODE, aligned).size)

        // Only the last block's zeros are padding.
        val zerosInside = "abc".encodeToByteArray() + ByteArray(13) + ByteArray(16)
        val decrypted = run("AES/CBC/ZeroBytePadding", Cipher.DECRYPT_MODE, run("AES/CBC/NoPadding", Cipher.ENCRYPT_MODE, zerosInside))
        assertContentEquals(zerosInside.copyOf(16), decrypted)
    }

    @Test
    fun `streaming updates give the same result`() {
        val plain = ByteArray(40) { it.toByte() }
        for (transformation in listOf("AES/CBC/ZeroBytePadding", "AES/CBC/PKCS7Padding")) {
            val cipher = Cipher.getInstance(transformation).apply { init(Cipher.ENCRYPT_MODE, key, ivSpec) }
            val streamed = (cipher.update(plain, 0, 7) ?: ByteArray(0)) + (cipher.update(plain, 7, 20) ?: ByteArray(0)) + cipher.doFinal(plain, 27, 13)
            assertContentEquals(run(transformation, Cipher.ENCRYPT_MODE, plain), streamed, transformation)
        }
    }

    @Test
    fun `android code can name the provider`() {
        val plain = "x".encodeToByteArray()
        assertContentEquals(run("AES/CBC/PKCS5Padding", Cipher.ENCRYPT_MODE, plain), run("AES/CBC/PKCS7Padding", Cipher.ENCRYPT_MODE, plain, provider = "BC"))
    }
}
