package com.opentasker.core.share.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Builds a self-signed X.509 v3 certificate for a software RSA keypair using hand-rolled DER — so the
 * relay signer needs neither BouncyCastle nor a hardware AndroidKeyStore key (apksig had block-encoding
 * trouble signing generated relays with hardware keys). The public key's own encoded form is already a
 * DER `SubjectPublicKeyInfo`, so it is embedded verbatim; only the TBSCertificate wrapper is encoded
 * and signed with `SHA256withRSA`.
 */
object RelayCertBuilder {

    // OIDs
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)                               // 2.5.4.3
    private val OID_SHA256_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b) // 1.2.840.113549.1.1.11

    fun selfSigned(keyPair: KeyPair, commonName: String): X509Certificate {
        val sigAlg = seq(oid(OID_SHA256_RSA), nul())
        val name = seq(set(seq(oid(OID_COMMON_NAME), utf8(commonName))))
        val validity = seq(utcTime("200101000000Z"), utcTime("491231235959Z"))
        val spki = keyPair.public.encoded  // already a DER SubjectPublicKeyInfo

        val tbs = seq(
            explicit0(int(byteArrayOf(2))),   // version v3 (== 2)
            int(byteArrayOf(0x01)),           // serialNumber
            sigAlg,
            name,                             // issuer
            validity,
            name,                             // subject (self-signed)
            spki,
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val cert = seq(tbs, sigAlg, bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    // ---- minimal DER ----------------------------------------------------------------------------

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < 0x80) {
            out.write(n)
        } else {
            val lenBytes = ByteArrayOutputStream()
            var v = n
            while (v > 0) { lenBytes.write(v and 0xFF); v = v ushr 8 }
            val b = lenBytes.toByteArray().reversedArray()
            out.write(0x80 or b.size)
            out.write(b)
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(*parts))
    private fun set(part: ByteArray) = tlv(0x31, part)
    private fun int(v: ByteArray) = tlv(0x02, v)
    private fun oid(v: ByteArray) = tlv(0x06, v)
    private fun nul() = byteArrayOf(0x05, 0x00)
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    private fun bitString(v: ByteArray) = tlv(0x03, byteArrayOf(0x00) + v)   // 0 unused bits
    private fun explicit0(v: ByteArray) = tlv(0xA0, v)                        // [0] EXPLICIT

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }
}
