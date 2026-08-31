package com.opentasker.core.share.relay

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The on-phone signing identity for generated relay APKs: a software RSA-2048 keypair with a
 * self-signed cert ([RelayCertBuilder]), generated once and persisted under the app-private
 * `filesDir/relay_signer/`. apksig signs generated relays with this via pure JCA — a software key
 * avoids the AndroidKeyStore/apksig block-encoding incompatibility hit during M0.
 *
 * The key is low-value (it only ever signs forwarder stubs) and lives in app-private storage
 * (`filesDir`, mode 0700). It persists so regenerating a relay reinstalls over the old one
 * (`pm install -r`) with a matching signature. If app data is cleared, a new key is generated with a
 * different [certFingerprint]; relays signed by the old key then need a fresh (uninstall+install)
 * reinstall — callers compare the stored per-relay fingerprint against the current one.
 */
object RelayKeystore {
    private const val DIR = "relay_signer"
    private const val KEY_FILE = "key.pk8"
    private const val CERT_FILE = "cert.der"
    private const val SUBJECT = "OpenTasker Share Relay"

    @Volatile
    private var cached: Pair<PrivateKey, X509Certificate>? = null

    @Synchronized
    fun signer(context: Context): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val dir = File(context.filesDir, DIR)
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        val result = if (keyFile.exists() && certFile.exists()) load(keyFile, certFile) else generate(dir, keyFile, certFile)
        cached = result
        return result
    }

    fun certFingerprint(context: Context): String {
        val (_, cert) = signer(context)
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun load(keyFile: File, certFile: File): Pair<PrivateKey, X509Certificate> {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return key to cert
    }

    private fun generate(dir: File, keyFile: File, certFile: File): Pair<PrivateKey, X509Certificate> {
        val kp: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = RelayCertBuilder.selfSigned(kp, SUBJECT)
        dir.mkdirs()
        keyFile.writeBytes(kp.private.encoded)   // PKCS#8
        certFile.writeBytes(cert.encoded)
        return kp.private to cert
    }
}
