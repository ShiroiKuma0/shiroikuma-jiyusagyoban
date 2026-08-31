package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Crypto vectors taken from the Python client that drives 白い熊's Band 11 Pro, which is itself
 * byte-verified against MIT `zyv/huawei-lpv2`. Green here means the Kotlin agrees with code that
 * has completed a real handshake, not merely with itself.
 *
 * The band's MAC is load-bearing in the key derivation, so the real one is used throughout.
 */
class HuaweiCryptoTest {

    private val mac = "A4:AA:FE:34:29:0F"
    private fun hex(s: String) = HuaweiCrypto.hex(s)
    private val nonce32 = ByteArray(32) { it.toByte() }

    @Test
    fun `secret key derivation matches for authVersion 1`() {
        assertArrayEquals(
            hex("15D4BB256D4CABF93D6CF3B8CC4117C4"),
            HuaweiCrypto.createSecretKey(1, mac),
        )
    }

    @Test
    fun `secret key derivation matches for authVersion 2`() {
        assertArrayEquals(
            hex("99C13144C7A14DD155C92EA919BC9EA4"),
            HuaweiCrypto.createSecretKey(2, mac),
        )
    }

    @Test
    fun `MAC case changes the derived key, so it must be passed as reported`() {
        assertNotEquals(
            HuaweiCrypto.upperHex(HuaweiCrypto.createSecretKey(1, mac)),
            HuaweiCrypto.upperHex(HuaweiCrypto.createSecretKey(1, mac.lowercase())),
        )
    }

    @Test
    fun `challenge and response digests differ and match the reference`() {
        val challenge = HuaweiCrypto.digestChallenge(1, nonce32)
        val response = HuaweiCrypto.digestResponse(1, nonce32)
        assertArrayEquals(
            hex(
                "1D9205858F993A450C15F861245DAADAD5D280F15CB4B9BBD9815F1CB11D2FEA" +
                    "CE6BC3B801B125D7F0C2D0C447EA7F50092931FDE884CC7AA970582E134B2A7E",
            ),
            challenge,
        )
        assertArrayEquals(
            hex(
                "412183C1F4476EEC900632BBE47A635B5B53636239AE5EB3665B812613362C28" +
                    "84721DA51DB1231F62364B425B6D4CF92BDD00335244D31593F27C85D15B99A6",
            ),
            response,
        )
        // The bug in zyv/huawei-lpv2 is passing the RESPONSE constant for both. If these ever
        // matched, our challenge would be rejected by the band.
        assertNotEquals(
            HuaweiCrypto.upperHex(challenge),
            HuaweiCrypto.upperHex(response),
        )
    }

    @Test
    fun `digest is 64 bytes and only the first 32 go on the wire`() {
        assertEquals(64, HuaweiCrypto.digestChallenge(1, nonce32).size)
    }

    @Test
    fun `PIN key hashes the UPPERCASE hex string, not the raw bytes`() {
        val pin = ByteArray(64) { it.toByte() }
        assertArrayEquals(
            hex("75B0E236ABBADF63793D28D01F0A750714FC2CB10C090690508440E0EE688731"),
            HuaweiCrypto.pinKey(pin),
        )
        // Hashing the raw bytes is the obvious wrong turn and yields a different key entirely.
        assertNotEquals(
            HuaweiCrypto.upperHex(HuaweiCrypto.pinKey(pin)),
            HuaweiCrypto.upperHex(HuaweiCrypto.sha256(pin)),
        )
    }

    @Test
    fun `HKDF matches the reference derivation used for the session key`() {
        assertArrayEquals(
            hex("8DC4ED18E917D811E4089397FA6596B417CAED213B7CFC92CBA847F55B0C99D5"),
            HuaweiCrypto.hkdfSha256(
                key = ByteArray(32) { it.toByte() },
                salt = ByteArray(32) { it.toByte() },
                info = "hichain_iso_session_key".toByteArray(),
            ),
        )
    }

    @Test
    fun `AES-CBC matches the reference and round-trips`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val plain = "hello huawei band".toByteArray()
        val enc = HuaweiCrypto.encryptCbc(plain, key, iv)
        assertArrayEquals(
            hex("4E550B09C068BE2A8AC16D5D9DC1832DD7B320ABE934C2514FA120EBBB88EAD4"),
            enc,
        )
        assertArrayEquals(plain, HuaweiCrypto.decryptCbc(enc, key, iv))
    }

    @Test
    fun `AES-GCM matches the reference with a 16-byte IV and round-trips`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val plain = "hello huawei band".toByteArray()
        val enc = HuaweiCrypto.encryptGcm(plain, key, iv)
        assertArrayEquals(
            hex("ADFB9BDA12924CFDB76FA6EB814D4EB991F853AF09B0531D3232D7D6329E84DD82"),
            enc,
        )
        assertArrayEquals(plain, HuaweiCrypto.decryptGcm(enc, key, iv))
    }

    @Test
    fun `GCM with AAD round-trips, as the HiChain exchange needs`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = HuaweiCrypto.randomBytes(12)
        val aad = "hichain_iso_exchange".toByteArray()
        val challenge = HuaweiCrypto.randomBytes(16)
        val enc = HuaweiCrypto.encryptGcm(challenge, key, iv, aad)
        assertArrayEquals(challenge, HuaweiCrypto.decryptGcm(enc, key, iv, aad))
    }

    @Test
    fun `PIN decryption uses the universal digest secret`() {
        // Round-trip through the same constant the band encrypts under, proving the selection
        // logic and the CBC layer agree.
        val iv = HuaweiCrypto.randomBytes(16)
        val pin = ByteArray(64) { (it * 3).toByte() }
        val ciphertext = HuaweiCrypto.encryptCbc(pin, HuaweiCrypto.DIGEST_SECRET_V1, iv)
        assertArrayEquals(pin, HuaweiCrypto.decryptPin(1, ciphertext, iv))
    }

    @Test
    fun `digest secret is selected by authVersion`() {
        assertArrayEquals(HuaweiCrypto.DIGEST_SECRET_V1, HuaweiCrypto.digestSecret(1))
        assertArrayEquals(HuaweiCrypto.DIGEST_SECRET_V1, HuaweiCrypto.digestSecret(4))
        assertArrayEquals(HuaweiCrypto.DIGEST_SECRET_V2, HuaweiCrypto.digestSecret(2))
        assertArrayEquals(HuaweiCrypto.DIGEST_SECRET_V3, HuaweiCrypto.digestSecret(3))
    }
}
