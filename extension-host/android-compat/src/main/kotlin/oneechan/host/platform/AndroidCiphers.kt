package oneechan.host.platform

import java.io.ByteArrayOutputStream
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi

/**
 * Cipher names Android accepts (its BouncyCastle provider, "BC") that the JDK lacks:
 * `PKCS7Padding`, which is the JDK's PKCS5Padding under its block-size-neutral name, and
 * BouncyCastle's `ZeroBytePadding` (en.mangago decrypts its page list with AES/CBC/ZEROBYTEPADDING).
 * Extensions ask for them by name, sometimes naming the provider too, so this provider is "BC".
 */
object AndroidCiphers {
    private const val NAME = "BC"

    @Synchronized
    fun install() {
        if (Security.getProvider(NAME) == null) Security.addProvider(CompatProvider())
    }

    private class CompatProvider : Provider(NAME, "1.0", "Oneechan: Android's BouncyCastle cipher names over the JDK's ciphers") {
        init {
            for (cipher in listOf("AES", "DESede", "DES")) {
                for (mode in listOf("CBC", "ECB")) {
                    putService(CipherService(this, "$cipher/$mode/PKCS7Padding") { AliasCipher("$cipher/$mode/PKCS5Padding") })
                    putService(CipherService(this, "$cipher/$mode/ZeroBytePadding") { ZeroBytePaddingCipher("$cipher/$mode/NoPadding") })
                }
            }
        }
    }

    /** Builds the cipher itself: there is no class for the JCA to instantiate by name. */
    private class CipherService(
        provider: Provider,
        algorithm: String,
        private val create: () -> CipherSpi,
    ) : Provider.Service(provider, "Cipher", algorithm, CipherSpi::class.java.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = create()
    }

    /** A JDK cipher under another name. */
    private open class AliasCipher(
        transformation: String,
    ) : CipherSpi() {
        protected val delegate: Cipher = Cipher.getInstance(transformation)

        override fun engineSetMode(mode: String?) {}

        override fun engineSetPadding(padding: String?) {}

        override fun engineGetBlockSize(): Int = delegate.blockSize

        override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)

        override fun engineGetIV(): ByteArray? = delegate.iv

        override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

        override fun engineInit(
            opmode: Int,
            key: Key,
            random: SecureRandom?,
        ) = delegate.init(opmode, key, random ?: SecureRandom())

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) = delegate.init(opmode, key, params, random ?: SecureRandom())

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameters?,
            random: SecureRandom?,
        ) = delegate.init(opmode, key, params, random ?: SecureRandom())

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray? = delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray = if (input == null) delegate.doFinal() else delegate.doFinal(input, inputOffset, inputLen)

        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = engineDoFinal(input, inputOffset, inputLen).also { it.copyInto(output, outputOffset) }.size
    }

    /**
     * BouncyCastle's ZeroBytePadding over the JDK's NoPadding: encryption fills the last block with
     * zeros, adding a whole block when the input is block-aligned (PaddedBufferedBlockCipher), and
     * decryption drops the zeros at the end of the last block. Data is buffered until doFinal.
     */
    private class ZeroBytePaddingCipher(
        transformation: String,
    ) : AliasCipher(transformation) {
        private val buffered = ByteArrayOutputStream()
        private var encrypting = true

        private fun reset(opmode: Int) {
            buffered.reset()
            encrypting = opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE
        }

        override fun engineInit(
            opmode: Int,
            key: Key,
            random: SecureRandom?,
        ) = super.engineInit(opmode, key, random).also { reset(opmode) }

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) = super.engineInit(opmode, key, params, random).also { reset(opmode) }

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameters?,
            random: SecureRandom?,
        ) = super.engineInit(opmode, key, params, random).also { reset(opmode) }

        override fun engineGetOutputSize(inputLen: Int): Int = buffered.size() + inputLen + delegate.blockSize

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray {
            buffered.write(input, inputOffset, inputLen)
            return ByteArray(0)
        }

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int {
            buffered.write(input, inputOffset, inputLen)
            return 0
        }

        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray {
            if (input != null) buffered.write(input, inputOffset, inputLen)
            val data = buffered.toByteArray()
            buffered.reset()
            val block = delegate.blockSize
            if (encrypting) return delegate.doFinal(data.copyOf((data.size / block + 1) * block))
            val plain = delegate.doFinal(data)
            var end = plain.size
            val lastBlock = maxOf(0, plain.size - block)
            while (end > lastBlock && plain[end - 1] == 0.toByte()) end--
            return plain.copyOf(end)
        }
    }
}
