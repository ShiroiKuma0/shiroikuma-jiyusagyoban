package com.opentasker.core.share.relay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.share.ShareRelayEntry
import com.opentasker.core.share.ShareRelayStore
import com.opentasker.core.shizuku.ShizukuShell
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ties the relay engine together for the "Share apps" screen: build ([RelayApkBuilder]) → install
 * ([RelayInstaller]) → record ([ShareRelayStore]). Also handles removal (uninstall + forget) and the
 * signing-key-rotation case (uninstall the stale-signature relay, then install fresh).
 */
object RelayGenerator {

    sealed interface Outcome {
        data object Installed : Outcome
        data object ShizukuUnavailable : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Build + sign + install the relay for [entry], recording success in the store. */
    suspend fun generate(context: Context, entry: ShareRelayEntry): Outcome {
        if (!ShizukuShell.available()) return Outcome.ShizukuUnavailable
        val work = File(context.filesDir, "relay_build")
        val out = File(work, "relay_${entry.relayPackage}.apk")
        try {
            RelayApkBuilder.buildSigned(
                context,
                RelayApkBuilder.Spec(entry.relayPackage, entry.label, entry.targetPackage, iconPng(context, entry)),
                work, out,
            )
            var result = RelayInstaller.install(out)
            if (result is RelayInstaller.Result.SignatureMismatch) {
                // Our signing key rotated (app data cleared) — remove the stale-signature relay, reinstall.
                RelayInstaller.uninstall(entry.relayPackage)
                result = RelayInstaller.install(out)
            }
            return when (result) {
                is RelayInstaller.Result.Success -> {
                    ShareRelayStore.markGenerated(entry.targetPackage, RelayKeystore.certFingerprint(context))
                    Outcome.Installed
                }
                is RelayInstaller.Result.SignatureMismatch -> Outcome.Failed("signature mismatch")
                is RelayInstaller.Result.Failure -> Outcome.Failed(result.message)
            }
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            out.delete()
        }
    }

    /** Uninstall the relay and forget the target. */
    suspend fun remove(context: Context, entry: ShareRelayEntry): Outcome {
        if (RelayInstaller.isInstalled(context, entry.relayPackage)) {
            if (!ShizukuShell.available()) return Outcome.ShizukuUnavailable
            val r = RelayInstaller.uninstall(entry.relayPackage)
            if (r is RelayInstaller.Result.Failure) return Outcome.Failed(r.message)
        }
        ShareRelayStore.remove(entry.targetPackage)
        return Outcome.Installed
    }

    /** The chosen icon as PNG bytes (its saved snapshot, else the target's launcher icon). */
    private fun iconPng(context: Context, entry: ShareRelayEntry): ByteArray? {
        val bmp = TaskIconStore.loadBitmap(entry.iconPath) ?: appIcon(context, entry.targetPackage) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun appIcon(context: Context, pkg: String): Bitmap? = runCatching {
        val pm = context.packageManager
        val d = pm.getApplicationIcon(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS))
        (d as? BitmapDrawable)?.bitmap ?: Bitmap.createBitmap(
            d.intrinsicWidth.coerceAtLeast(96), d.intrinsicHeight.coerceAtLeast(96), Bitmap.Config.ARGB_8888,
        ).also { d.setBounds(0, 0, it.width, it.height); d.draw(Canvas(it)) }
    }.getOrNull()
}
