package com.opentasker.core.huawei

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Huawei LPv2 crypto for `authVersion 1` — the scheme 白い熊's Band 11 Pro negotiates.
 *
 * Android-free (stock `javax.crypto` only) so it is JVM-testable. Cross-checked byte-for-byte
 * against MIT `zyv/huawei-lpv2` via the Python reference in `.scratch/hw/`; nothing here comes from
 * AGPL Gadgetbridge.
 *
 * **No Huawei account is involved anywhere.** The constants below are universal — identical on every
 * Huawei band, not per-device, not server-issued, not rotated — and already published under MIT. The
 * PIN is fetched from the band itself, encrypted under one of these very constants. Every session
 * key is derived locally.
 *
 * Primitives: SHA-256, HMAC-SHA256, HKDF-SHA256, AES-128-CBC, AES-GCM. No elliptic curve, no modexp.
 */
object HuaweiCrypto {

    private const val AES_KEY_SIZE = 16

    /** Universal digest secrets, selected by `authVersion`. */
    val DIGEST_SECRET_V1: ByteArray = hex("70FB6C24035FDB552F38898AEEDE3F69")
    val DIGEST_SECRET_V2: ByteArray = hex("93ACDEF76ACB09857DBFE5261AABCD78")
    val DIGEST_SECRET_V3: ByteArray = hex("9C2763A9CCE134766DE3FF6118200553")

    /** Universal secret keys. The v1 pair is ASCII: "oujymwq4clv9378y" / "b10jgfd9y7vsuda9". */
    private val SECRET_KEY_1_V1: ByteArray = hex("6F756A796D777134636C763933373879")
    private val SECRET_KEY_2_V1: ByteArray = hex("6231306A676664397937767375646139")
    private val SECRET_KEY_1_V23: ByteArray = hex("555386FC632007AA86493522B86AE25C")
    private val SECRET_KEY_2_V23: ByteArray = hex("33079BC57A886D3CF56137096F228000")

    /**
     * The two message constants. Challenge is `{0x01,0x00}`, response is `{0x01,0x10}`.
     *
     * MIT `zyv/huawei-lpv2` passes the RESPONSE constant for both — a genuine bug its tests do not
     * cover, since they only exercise the empty-message case. Copying it would make the band reject
     * our challenge, so we deliberately differ here.
     */
    private val MESSAGE_CHALLENGE = byteArrayOf(0x01, 0x00)
    private val MESSAGE_RESPONSE = byteArrayOf(0x01, 0x10)

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun digestSecret(authVersion: Int): ByteArray = when (authVersion) {
        1, 4 -> DIGEST_SECRET_V1
        2 -> DIGEST_SECRET_V2
        else -> DIGEST_SECRET_V3
    }

    private fun secretKeys(authVersion: Int): Pair<ByteArray, ByteArray> =
        if (authVersion == 1) SECRET_KEY_1_V1 to SECRET_KEY_2_V1
        else SECRET_KEY_1_V23 to SECRET_KEY_2_V23

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    /**
     * HKDF-SHA256 (RFC 5869), written out rather than pulled from a library so the whole
     * derivation is visible and testable.
     */
    fun hkdfSha256(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(salt, key)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * Nested HMAC-SHA256 producing **64 bytes**: `outer || step1`.
     *
     * [nonce] is the doubled nonce, `serverNonce || clientNonce` (32 bytes). Only the first 32 bytes
     * go on the wire; bytes 32..48 are the "first key" the protocol keeps for later.
     */
    fun computeDigest(authVersion: Int, message: ByteArray, nonce: ByteArray): ByteArray {
        val step1 = hmacSha256(digestSecret(authVersion) + message, nonce)
        return hmacSha256(step1, nonce) + step1
    }

    fun digestChallenge(authVersion: Int, nonce: ByteArray): ByteArray =
        computeDigest(authVersion, MESSAGE_CHALLENGE, nonce)

    fun digestResponse(authVersion: Int, nonce: ByteArray): ByteArray =
        computeDigest(authVersion, MESSAGE_RESPONSE, nonce)

    /**
     * Mix the two universal secret keys with the **band's own MAC**, then SHA-256.
     *
     * The MAC goes in as ASCII with colons stripped and "0000" appended, so **case matters** — pass
     * it exactly as the device reports it (uppercase).
     */
    fun createSecretKey(authVersion: Int, deviceMac: String): ByteArray {
        val macKey = (deviceMac.replace(":", "") + "0000").toByteArray(Charsets.UTF_8)
        val (k1, k2) = secretKeys(authVersion)
        val mixed = ByteArray(AES_KEY_SIZE) { i ->
            (((k1[i].toInt() and 0xFF) shl 4) xor (k2[i].toInt() and 0xFF)).toByte()
        }
        val mixedHash = sha256(mixed)
        val finalMixed = ByteArray(AES_KEY_SIZE) { i ->
            (((mixedHash[i].toInt() and 0xFF) ushr 6) xor (macKey[i].toInt() and 0xFF)).toByte()
        }
        return sha256(finalMixed).copyOf(AES_KEY_SIZE)
    }

    fun encryptCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun decryptCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    /**
     * AES-GCM. Note the IV here is **16 bytes**, not the usual 12 — that is what the band uses once
     * `deviceSupportType == 4`, and GCM accepts it.
     */
    fun encryptGcm(data: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            aad?.let(::updateAAD)
            doFinal(data)
        }

    fun decryptGcm(data: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            aad?.let(::updateAAD)
            doFinal(data)
        }

    /** Decrypt the PIN the band hands over at `0x01/0x2C` — AES-CBC under the digest secret. */
    fun decryptPin(authVersion: Int, ciphertext: ByteArray, iv: ByteArray): ByteArray =
        decryptCbc(ciphertext, digestSecret(authVersion), iv)

    /**
     * The PSK seed for a HiChain bind: `SHA256(UPPERCASE_hex(pin) as ASCII)`.
     *
     * The hex STRING is hashed, not the raw bytes, and the case is load-bearing — lowercase yields a
     * different key and the handshake fails at step 1 with nothing to indicate why.
     */
    fun pinKey(pin: ByteArray): ByteArray = sha256(upperHex(pin).toByteArray(Charsets.US_ASCII))

    fun upperHex(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it) }

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
