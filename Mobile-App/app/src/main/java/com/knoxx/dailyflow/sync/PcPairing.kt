package com.knoxx.dailyflow.sync

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing by the 6-digit code the PC shows: SPAKE2 over the RFC 3526
 * 2048-bit MODP group. Byte-for-byte the same as the PC's
 * dailyflow/sync_protocol.py, which explains the construction.
 *
 * Why not something simpler. Sending the code, or a hash of it, lets anyone
 * on the same Wi-Fi capture it. An HMAC of the code under a Diffie-Hellman
 * key stops a listener, but a man in the middle who completes the exchange
 * can try all one million codes offline in milliseconds. SPAKE2 leaves an
 * attacker one guess per attempt, and the PC allows five.
 */
object PcPairing {

    private val P = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF",
        16
    )
    private val Q = P.subtract(BigInteger.ONE).shiftRight(1)
    private val G = BigInteger.valueOf(2)
    // BigInteger.TWO is Java 9; not every Android this app supports has it.
    private val TWO = BigInteger.valueOf(2)
    private const val ELEMENT_BYTES = 256

    private val M = BigInteger(1, sha256("DailyFlow SPAKE2 M v1".toByteArray())).modPow(TWO, P)
    private val N = BigInteger(1, sha256("DailyFlow SPAKE2 N v1".toByteArray())).modPow(TWO, P)

    private val random = SecureRandom()

    /** The phone's half of the exchange, kept until the PC answers. */
    // Not named x / X: both would compile to getX() and clash on the JVM.
    class Start internal constructor(
        val code: String,
        internal val secret: BigInteger,
        val element: BigInteger
    ) {
        val elementHex: String get() = element.toString(16)
    }

    class Keys(val confirmPc: ByteArray, val confirmPhone: ByteArray, val syncKey: ByteArray)

    fun start(code: String): Start {
        val w = codeScalar(code)
        // Uniform in [1, q): the same range as the PC's randbelow(q - 1) + 1.
        val x = BigInteger(P.bitLength(), random).mod(Q.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val xElement = G.modPow(x, P).multiply(M.modPow(w, P)).mod(P)
        return Start(code, x, xElement)
    }

    /** Null when the PC's element is invalid. */
    fun finish(start: Start, phoneId: String, pcId: String, yHex: String): Keys? {
        val y = BigInteger(yHex, 16)
        if (y <= BigInteger.ONE || y >= P.subtract(BigInteger.ONE)) return null
        val w = codeScalar(start.code)
        val z = y.multiply(N.modPow(w, P).modInverse(P)).mod(P).modPow(start.secret, P)
        val k = sha256(
            "DailyFlow pair v1".toByteArray(),
            lp(phoneId),
            lp(pcId),
            element(start.element),
            element(y),
            element(z)
        )
        return Keys(
            confirmPc = hmac(k, "pc confirm"),
            confirmPhone = hmac(k, "phone confirm"),
            syncKey = hmac(k, "sync key")
        )
    }

    private fun codeScalar(code: String): BigInteger =
        BigInteger(1, sha256("DailyFlow pairing code v1|$code".toByteArray(Charsets.UTF_8))).mod(Q)

    /** 256 bytes, big-endian, unsigned. BigInteger.toByteArray is signed and
     *  minimal, so it can be 257 bytes (a leading sign byte) or fewer than 256;
     *  either would change the hash and fail every pairing. */
    private fun element(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(ELEMENT_BYTES)
        val copy = minOf(raw.size, ELEMENT_BYTES)
        System.arraycopy(raw, raw.size - copy, out, ELEMENT_BYTES - copy, copy)
        return out
    }

    private fun lp(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hmac(key: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
