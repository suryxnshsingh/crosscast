package dev.suryansh.crosscast.core.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class RaopCryptoTest {
    private val key = RaopCrypto.airportKey
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent))

    @Test
    fun `AirPort key loads`() {
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(BigInteger.valueOf(65537), key.publicExponent)
    }

    @Test
    fun `AES key wrapped by a sender with OAEP unwraps`() {
        val aesKey = Random(1).nextBytes(16)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, publicKey)
            doFinal(aesKey)
        }
        assertArrayEquals(aesKey, RaopCrypto.decryptAesKey(wrapped))
    }

    @Test
    fun `Apple-Response verifies as a type-1 signature over challenge, IP and MAC`() {
        val challenge = Random(2).nextBytes(16)
        val ip = InetAddress.getByName("192.168.29.48")
        val mac = byteArrayOf(0x1E, 0x2E, 0x5E, 0xB6.toByte(), 0x4A, 0x10)
        val signature = RaopCrypto.appleResponse(challenge, ip, mac)
        assertEquals(256, signature.size)

        // Undo the private-key operation with the public key and check the padded block.
        val block = RaopCrypto.toFixedLength(BigInteger(1, signature).modPow(key.publicExponent, key.modulus), 256)
        val message = (challenge + ip.address + mac).copyOf(32) // 26 bytes, zero-padded to 32
        assertEquals(0, block[0].toInt())
        assertEquals(1, block[1].toInt())
        val separator = 256 - message.size - 1
        for (i in 2 until separator) assertEquals(0xFF.toByte(), block[i])
        assertEquals(0, block[separator].toInt())
        assertArrayEquals(message, block.copyOfRange(separator + 1, 256))
    }

    @Test
    fun `audio decryption covers whole blocks and leaves the tail in the clear`() {
        val aesKey = Random(3).nextBytes(16)
        val iv = Random(4).nextBytes(16)
        val plain = Random(5).nextBytes(16 * 20 + 7)
        val encrypted = plain.copyOf()
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(encrypted, 0, 16 * 20, encrypted, 0)
        }
        // Prefix and suffix around the payload, as in an RTP packet.
        val packet = ByteArray(12) + encrypted + ByteArray(3)
        AudioDecryptor(aesKey, iv).decrypt(packet, 12, encrypted.size)
        assertArrayEquals(plain, packet.copyOfRange(12, 12 + plain.size))
    }
}
