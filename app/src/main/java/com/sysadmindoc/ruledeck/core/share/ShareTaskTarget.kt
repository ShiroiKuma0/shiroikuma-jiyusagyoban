package com.sysadmindoc.ruledeck.core.share

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.IntentCompat
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.core.engine.executeAndLogTask
import com.sysadmindoc.ruledeck.core.engine.resolveTaskByName
import com.sysadmindoc.ruledeck.core.share.relay.RelayKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * A share tile that starts a TASK instead of forwarding to an app (白い熊, 2026-11-25) — first use:
 * 「PC へ送る」, which sends the shared files to the PC with scp from Termux.
 *
 * The task gets the share as per-run variables:
 * - `%share_paths` — every file as a real path, each quoted for a shell and separated by a space, so
 *   that `scp -- %share_paths host:dir/` is safe whatever a file is called;
 * - `%share_count` — how many files;
 * - `%share_subject` — the share's subject line, when it has one.
 *
 * ## Paths, and when a copy is made
 *
 * A share hands over references, not paths. A file that lies in shared storage is found again by
 * asking the system where the opened reference points ([realPath]) and is passed IN PLACE — nothing
 * is copied. Only what has no such path (a screenshot shared from its preview, a picture out of an
 * app's private cache) is copied into [stagingRoot], which Termux can read, and that copy is deleted
 * when the task ends, whatever its outcome.
 *
 * ## Who may start it
 *
 * [ShareForwardActivity] is open to every app, because a relay is a separately signed package.
 * Forwarding to an app is harmless; starting a task is not. So a task is started only when the
 * share proves it comes from the tile made for that task ([provenTile]): the tile sends a
 * PendingIntent, whose creator the system vouches for, and that package must be the entry's relay,
 * signed with this installation's own relay key.
 */
internal object ShareTaskTarget {
    const val EXTRA_PROOF = "share.proof"

    private const val STAGING_DIR = "share"
    private const val STAGING_TTL_MS = 24L * 60 * 60 * 1000
    private const val SHARED_ROOT = "/storage/emulated/0/"

    /** The same storage under the names other processes and mounts know it by. */
    private val SHARED_ROOT_ALIASES = listOf(
        SHARED_ROOT,
        "/sdcard/",
        "/data/media/0/",
        "/mnt/user/0/emulated/0/",
        "/mnt/pass_through/0/emulated/0/",
        "/mnt/androidwritable/0/emulated/0/",
        "/mnt/installer/0/emulated/0/",
    )

    /** Outlives the activity that took the share: the task may run for minutes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One file of a share: its path for the task, and whether that path is a copy of ours. */
    data class Shared(val path: String, val copied: Boolean)

    /** True when [intent] carries the proof of the tile made for [entry]. */
    fun provenTile(context: Context, intent: Intent, entry: ShareRelayEntry): Boolean {
        val proof = IntentCompat.getParcelableExtra(intent, EXTRA_PROOF, PendingIntent::class.java) ?: return false
        val creator = proof.creatorPackage ?: return false
        if (creator != entry.relayPackage) return false
        return runCatching { signerFingerprints(context, creator).contains(RelayKeystore.certFingerprint(context)) }
            .getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun signerFingerprints(context: Context, pkg: String): List<String> {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    /**
     * Every shared item as a path Termux can read: in place where it has one, else a copy in
     * [staging]. Null when an item can be neither found nor copied. Call while the activity that
     * received the share is alive — the read grants end with it.
     */
    fun collect(context: Context, uris: List<Uri>, staging: File): List<Shared>? = uris.map { uri ->
        realPath(context, uri)?.let { Shared(it, copied = false) }
            ?: copy(context, uri, staging)?.let { Shared(it.absolutePath, copied = true) }
            ?: return null
    }

    /** A fresh directory for the copies of one share; made only when a copy is written. */
    fun newStaging(context: Context): File = File(stagingRoot(context), System.currentTimeMillis().toString())

    /**
     * Shared storage, in a directory of this app that needs no permission to write and that another
     * app with storage access (Termux) can read — unlike `Android/data`.
     */
    private fun stagingRoot(context: Context): File {
        val media = context.externalMediaDirs.firstOrNull { it != null }
            ?: File("${SHARED_ROOT}Android/media/${context.packageName}")
        return File(media, STAGING_DIR)
    }

    /** The file [uri] points at, when it is a readable file in shared storage; else null. */
    fun realPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return accept(uri.path, null)
        if (uri.scheme != "content") return null
        val viaDescriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // Where the opened reference really points, and what it must look like there.
                val link = Os.readlink("/proc/self/fd/${pfd.fd}")
                val stat = Os.fstat(pfd.fileDescriptor)
                accept(link, stat.st_size to stat.st_mtime)
            }
        }.getOrNull()
        if (viaDescriptor != null) return viaDescriptor
        // Older providers name the path themselves; believed only when the file there has the size
        // the provider reports.
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_data", OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val path = c.getString(0) ?: return@use null
                val size = if (c.isNull(1)) return@use null else c.getLong(1)
                accept(path, size to null)
            }
        }.getOrNull()
    }

    /**
     * [raw] as a path under [SHARED_ROOT], when it names a readable file there that matches
     * [expect] (size, and modification time when known). `Android/data` and `Android/obb` are left
     * out: another app cannot read them, so such a file is copied instead.
     */
    private fun accept(raw: String?, expect: Pair<Long, Long?>?): String? {
        val path = raw ?: return null
        val alias = SHARED_ROOT_ALIASES.firstOrNull { path.startsWith(it) } ?: return null
        val file = runCatching { File(SHARED_ROOT + path.removePrefix(alias)).canonicalFile }.getOrNull() ?: return null
        val canonical = file.path
        if (!canonical.startsWith(SHARED_ROOT)) return null
        val inside = canonical.removePrefix(SHARED_ROOT)
        if (inside.startsWith("Android/data/") || inside.startsWith("Android/obb/")) return null
        if (!file.isFile || !file.canRead()) return null
        if (expect != null) {
            val stat = runCatching { Os.stat(canonical) }.getOrNull() ?: return null
            if (stat.st_size != expect.first) return null
            expect.second?.let { if (stat.st_mtime != it) return null }
        }
        return canonical
    }

    private fun copy(context: Context, uri: Uri, staging: File): File? = runCatching {
        staging.mkdirs()
        val name = displayName(context, uri)
        var file = File(staging, name)
        var n = 1
        while (file.exists()) file = File(staging, "${n++}_$name")
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        file
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "shared.bin"
        return raw.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f\\x7f]"), "_").takeLast(96).ifBlank { "shared.bin" }
    }

    /** One word of a shell command line, whatever [text] holds. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** The variables the task is started with. */
    fun variables(shared: List<Shared>, subject: String?): Map<String, String> = mapOf(
        "share_paths" to shared.joinToString(" ") { shellQuote(it.path) },
        "share_count" to shared.size.toString(),
        "share_subject" to subject.orEmpty(),
    )

    /**
     * Starts [taskName] with [variables] and removes [staging] when it ends. [onMissing] is called
     * (off the main thread) when no task has that name.
     */
    fun run(context: Context, taskName: String, variables: Map<String, String>, staging: File, onMissing: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                sweep(app, keep = staging)
                val db = RuleDeckApp_NoHilt.db
                val task = resolveTaskByName(db, taskName, null)
                if (task == null) onMissing()
                else runCatching { executeAndLogTask(app, db, task, source = "ShareTile", eventLocals = variables, logTag = TAG) }
            } finally {
                runCatching { staging.deleteRecursively() }
            }
        }
    }

    /** Copies a killed run left behind: gone after a day. A run still going is younger than that. */
    private fun sweep(context: Context, keep: File) {
        runCatching {
            val cutoff = System.currentTimeMillis() - STAGING_TTL_MS
            stagingRoot(context).listFiles()?.forEach { dir ->
                if (dir != keep && (dir.name.toLongOrNull() ?: Long.MAX_VALUE) < cutoff) dir.deleteRecursively()
            }
        }
    }

    private const val TAG = "ShareTaskTarget"
}
