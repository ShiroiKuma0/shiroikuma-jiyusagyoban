package com.opentasker.core.share.relay

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File

/**
 * Signs a generated relay APK with the on-phone [RelayKeystore] key using Google's pure-Java `apksig`
 * (v1+v2+v3, minSdk 26). apksig signs each chunk via `java.security.Signature`, so the non-exportable
 * AndroidKeyStore key works directly. Input must already be 4-byte aligned (RelayApkBuilder does this);
 * apksig preserves alignment when inserting its signing block.
 */
object RelaySigner {
    fun sign(context: Context, input: File, output: File) {
        val (key, cert) = RelayKeystore.signer(context)
        val config = ApkSigner.SignerConfig.Builder("relay", key, listOf(cert)).build()
        try {
            ApkSigner.Builder(listOf(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign()
        } catch (e: Exception) {
            // apksig wraps the real reason (bad padding/digest, key access) in the cause chain.
            val causes = generateSequence(e as Throwable) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            throw RuntimeException("relay signing failed: $causes", e)
        }
    }
}
