package com.opentasker.core.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.opentasker.core.bubbles.FreezeBubbleStore
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.policy.AppFreeze
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The internal handler each generated relay APK forwards into (see `core/share/relay/`). It receives
 * the relay's `ACTION_SEND`/`SEND_MULTIPLE` with `EXTRA_SHORTCUT_ID = "share_<targetpkg>"`, unfreezes
 * the target app if it is frozen ([AppFreeze.thaw], which clears the disabled and both suspension
 * slots), forwards the shared content to it, and drops a re-freeze bubble ([FreezeBubbleStore]) so the
 * app can be frozen again from the Desktop.
 *
 * Exported (no permission) so a relay — a separately-signed app — can call it by explicit component;
 * it has no SEND intent-filter, so the main app itself is not a share-sheet tile (only the relays are).
 *
 * Streams are forwarded pass-through (re-delegating our temporary read grant via ClipData +
 * FLAG_GRANT_READ_URI_PERMISSION); with [ShareRelayStore.copyStreams] on they are first copied into
 * our FileProvider — the fallback for receivers that cannot open a re-delegated grant.
 */
class ShareForwardActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) {
            finish(); return
        }
        scope.launch { sweepOldCopies() }

        val target = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            ?.takeIf { it.startsWith(SHORTCUT_ID_PREFIX) }
            ?.removePrefix(SHORTCUT_ID_PREFIX)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (target == null) { finish(); return }
        process(target)
    }

    /** Unfreeze (if needed) + forward + bubble, then finish. Runs off the main thread. */
    private fun process(pkg: String) {
        scope.launch {
            val entry = ShareRelayStore.find(pkg)
            val label = entry?.label ?: appLabel(pkg)
            val iconPath = entry?.iconPath ?: TaskIconStore.saveFromApp(pkg)
            // The same three-slot read the app.frozen action uses. Reading only the enabled state
            // here reported a policy-suspended target as ready, and the share then forwarded into an
            // app that could not be started at all.
            val state = withContext(Dispatchers.IO) { AppFreeze.read(this@ShareForwardActivity, pkg) }
            if (!state.installed) {
                if (entry != null) ShareRelayStore.remove(pkg)
                toastAndFinish("$label is no longer installed"); return@launch
            }
            if (state.frozen) {
                val thawed = withTimeoutOrNull(UNFREEZE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { AppFreeze.thaw(this@ShareForwardActivity, pkg) }
                }
                if (thawed != true) {
                    toastAndFinish("Could not unfreeze $label"); return@launch
                }
                // Re-freeze reminder — only when WE unfroze it; an already-running app stays as-is.
                FreezeBubbleStore.enqueue(pkg, label, iconPath)
            }
            forward(pkg, label)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS),
        ).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')

    // ---- forwarding ------------------------------------------------------------------------------

    private fun forward(pkg: String, label: String) {
        val incoming = intent
        var uris = collectUris(incoming)
        if (uris.isNotEmpty() && ShareRelayStore.copyStreams) {
            uris = uris.mapNotNull { copyToProvider(it) }
            if (uris.isEmpty()) {
                toastAndFinish("Could not copy the shared content"); return
            }
        }
        val out = Intent(incoming.action).apply {
            type = incoming.type ?: "*/*"
            setPackage(pkg)
            incoming.getStringExtra(Intent.EXTRA_TEXT)?.let { putExtra(Intent.EXTRA_TEXT, it) }
            incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            incoming.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { putExtra(Intent.EXTRA_HTML_TEXT, it) }
            if (uris.isNotEmpty()) {
                if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                } else {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
                clipData = ClipData.newUri(contentResolver, "shared", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The receiver gets its own task — ours is no-history / recents-excluded.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(out)
            runOnUiThread { finish() }
        } catch (e: ActivityNotFoundException) {
            toastAndFinish("$label cannot receive this content")
        } catch (e: SecurityException) {
            // Re-delegated grant refused → suggest the copy fallback.
            toastAndFinish("$label rejected the shared content — turn on \"copy files when forwarding\"")
        }
    }

    /** All shared content URIs: EXTRA_STREAM (single or list) plus any ClipData-only items. */
    private fun collectUris(incoming: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (incoming.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris += it.filterNotNull() }
        }
        incoming.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.takeIf { it !in uris }?.let { uris += it }
            }
        }
        return uris
    }

    // ---- copy fallback ---------------------------------------------------------------------------

    /** Copy [uri] into our FileProvider tree and return our own grantable URI (null on failure). */
    private fun copyToProvider(uri: Uri): Uri? = runCatching {
        val dir = File(filesDir, "$COPY_DIR/${System.currentTimeMillis()}").apply { mkdirs() }
        val name = displayName(uri)
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "${n++}_$name")
        contentResolver.openInputStream(uri)!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()

    /** The source's display name, sanitized to a safe filename. */
    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "shared.bin"
        return raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").takeLast(96).ifBlank { "shared.bin" }
    }

    /** Delete copy-fallback directories older than 24 h (cheap TTL sweep on every run). */
    private fun sweepOldCopies() {
        runCatching {
            val cutoff = System.currentTimeMillis() - COPY_TTL_MS
            File(filesDir, COPY_DIR).listFiles()?.forEach { dir ->
                if ((dir.name.toLongOrNull() ?: Long.MAX_VALUE) < cutoff) dir.deleteRecursively()
            }
        }
    }

    private fun toastAndFinish(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        /** Relay → handler target key: `EXTRA_SHORTCUT_ID = "share_<targetpkg>"` (matches RelayActivity). */
        const val SHORTCUT_ID_PREFIX = "share_"
        private const val UNFREEZE_TIMEOUT_MS = 10_000L
        /** Under filesDir AND inside the FileProvider's existing user_files <files-path>. */
        private const val COPY_DIR = "user_files/share_fwd"
        private const val COPY_TTL_MS = 24L * 60 * 60 * 1000
    }
}
