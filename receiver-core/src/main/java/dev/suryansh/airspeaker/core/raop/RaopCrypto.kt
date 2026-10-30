package dev.suryansh.airspeaker.core.raop

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Classic AirPlay cryptography.
 *
 * Every classic AirPlay receiver uses the same RSA key, extracted from the AirPort Express
 * firmware and published with the original Shairport. Senders encrypt the per-session AES key
 * to it (RSA-OAEP, SHA-1) and use it to check the Apple-Challenge response (PKCS#1 v1.5).
 */
object RaopCrypto {
    /** The AirPort Express key from Shairport Sync's common.c, converted to PKCS#8. */
    private const val AIRPORT_KEY_PKCS8 =
        "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDn10TyouJ4i2wfVaCOtwVEqPp5RaqL5sYs5fUcvdTcaEL+" +
        "PRCD3S7ewb/UJS3ALm85i98OYUjqhIVeLkQtptYmZPZ0ofMEkpreT2iT7y325xGox3oNkcnZgIIuUNEpIq/qQOqfDhTA92k4" +
        "xfOIL8AyPdn+VRVfUbtZIcIBYp/XM1LV4u+qv5ugSNe4E6K2dn9sPM8etM5nPQN7DS6jDF//6wb40Ird5AlXGpxon+8QcohV" +
        "3Yz7movvXIlD7ztfqhXd5pi+3fNZlgPrPm9hNyu2KPZVn1maeL9QBoeqf0l2wFYtQSlW+JieGKY1W9gVl4JeD8h1ND7HghF2" +
        "Jc2/mER7AgMBAAECggEBAOXwDHL1d9YEuaTOQSKqhLAXQ+yZWs/Mf0qyfAsYf5BmW+NZ3xJZgY3u7XnTse+EXk3d2smhVTc7" +
        "XicNjhMVABouUn1UzfkACldovJjURGs3u70Asp3YtTBiEzsqbnf07jJQViKQTacg+xwSwDmW2nE6BQYJjtvt7Pk20PqcvVkp" +
        "q7Dto1eZUC+YlNy4/FaaiS0XeAMkorbDFm40ZwkTS4VAQbhncGtY/vKg25Ird2KLaOaWk8evQ78qc9C3Mjd6C6F7RPBR6b95" +
        "hJ3LMzJXH9inCTPC1gvexHmTSj2spAu28vN8Cp0HEG6tyLNpoD8vQciACY6K3UYkDaxozFNU82ECgYEA9+C/Wh5nGDGai2IJ" +
        "wxcURARZ+XOFZhOxeuFQi7PmMW5rf0YtL31kQSuEt2vCPysMNWJFUnmyQ6n3MW+VgAezTGH3aOLUTtX/KycoF+wys+STkpIo" +
        "+ueOd0yg9169adWSAnmPEW42DGQ4sy4b2LncHjIy8NMJGIg8xD743aIsNpECgYEA72//+ZTx5WRBqgA1/RmgyNbwI3jHBYDZ" +
        "xIQgeR30B8WR+26/yjIsMIbdkB/S+uGuu2St9rt5/4BRvr0M2CCriYdABgGnsv6TkMrMmsq47Sv5HRhtj2lkPX7+D11W33V3" +
        "otA16lQT/JjY8/kI2gWaN52kscw48V1WCoPMMXFTyEsCgYEA0OuvvEAluoGMdXAjNDhOj2lvgE16oOd2TlB7t9Pf78fWeMZo" +
        "LT+tcTRBvurnJKCewJvcO8BwnJEz1Ins4qUa3QUxJ0kPkobRc8ikBU3CCldcfkwMmDT0od6HSRej5ADq+IUGLbXLfjQ2iecR" +
        "91/ng9fhkZL9dpzVQr6kuQEH7NECgYB/QBjcfeopLaUwQjhvMQWgd4rcbz3mkNordMUFWYPt9XRmGi/Xt96AU8zA4gjwyKxi" +
        "b1l9PZnSzlGjezmuS36e8sB18L89g8rNMtqWkZLCiZI1glwH0c0yWaGQbNzUmcthPiLJTLHqlxkGYJ3xsPSLBj8XNyA0NpSZ" +
        "tf35cO9EDQKBgQCQTukg+UTvWq98lCCgD16bSAgsC4Tg+7XdoqImd9+3uEiNsr7mTJvdPKxm+jIOdvcc4q8icru9dsq5TghK" +
        "DEHZsHcdxjNAwazPWonaAbQ3mG8mnPDCFuFeoUoDjNppKvDrbbAOeIArkyUgTS0gAoo/jLE0aOgPZBiOEEa6G+RYpg=="

    val airportKey: RSAPrivateCrtKey by lazy {
        val der = Base64.getDecoder().decode(AIRPORT_KEY_PKCS8)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
    }

    /** Unwraps the AES session key from the SDP `a=rsaaeskey:` attribute. */
    fun decryptAesKey(rsaAesKey: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, airportKey)
            doFinal(rsaAesKey)
        }

    /**
     * The `Apple-Response` value: a PKCS#1 v1.5 (type 1) private-key signature over
     * challenge ‖ local IP ‖ hardware address, zero-padded to at least 32 bytes.
     */
    fun appleResponse(challenge: ByteArray, localAddress: InetAddress, hardwareAddress: ByteArray): ByteArray {
        var message = challenge + localAddress.address + hardwareAddress
        if (message.size < 32) message = message.copyOf(32)
        return signRaw(message)
    }

    /** RSA private-key operation with type-1 padding, done with BigInteger so it behaves the same on every JCA provider. */
    internal fun signRaw(message: ByteArray): ByteArray {
        val k = (airportKey.modulus.bitLength() + 7) / 8
        require(message.size <= k - 11) { "message too long for RSA block" }
        val block = ByteArray(k)
        block[1] = 1
        val separator = k - message.size - 1
        block.fill(0xFF.toByte(), 2, separator)
        message.copyInto(block, separator + 1)
        val signature = BigInteger(1, block).modPow(airportKey.privateExponent, airportKey.modulus)
        return toFixedLength(signature, k)
    }

    internal fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size) // drop the sign byte
            else -> ByteArray(length - raw.size) + raw
        }
    }
}

/**
 * Decrypts audio packets in place. Each packet is AES-128-CBC with the session IV; only whole
 * 16-byte blocks are encrypted and any trailing bytes are sent in the clear.
 */
class AudioDecryptor(key: ByteArray, private val iv: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")

    fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
        val encrypted = length and 0xF.inv()
        if (encrypted == 0) return
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        cipher.doFinal(buffer, offset, encrypted, buffer, offset)
    }
}
