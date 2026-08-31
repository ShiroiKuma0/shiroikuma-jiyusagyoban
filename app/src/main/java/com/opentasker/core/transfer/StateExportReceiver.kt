package com.opentasker.core.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.app.BuildConfig
import com.opentasker.app.OpenTaskerApp_NoHilt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sister-app **state-export automation contract**, implemented by this app for itself — the
 * same wire shape every 白い熊 app exposes so a 保存復元 task can back them all up headlessly
 * (reference implementation: renrakusaki's BackupContactsReceiver, the EMUI-proven round-trip).
 *
 * - [ACTION_EXPORT_STATE]: run the full category-ZIP export ([SettingsBackup]) without UI.
 *   Extras (all String): `token` (required — [AutomationAuth]), `path` (optional absolute
 *   directory, wins over the configured SAF directory), `items` (optional comma list of
 *   [SettingsBackup.Cat] ids; absent/empty = all), `progress_action` (optional — see below),
 *   plus the reply trio `reply_action` / `reply_package` / `reply_id`.
 * - [ACTION_LIST_CATEGORIES]: token-gated category enumeration for the caller's item picker.
 * - [ACTION_CANCEL_EXPORT]: stop the running export at the next category boundary, delete its partial
 *   output, and let it answer `ERROR:cancelled`. Extras: `token` (required) + optional `reply_id`
 *   (absent = whatever is running). Fire-and-forget — it sends no reply of its own, and arriving with
 *   nothing running is a silent no-op. There is no foreground service or wakelock to release here: the
 *   export rides `goAsync()` + an IO coroutine, and `pending.finish()` in the `finally` is the
 *   equivalent of the contract's step 4.
 *
 * Reply: a FRESH broadcast to `reply_package` with action `reply_action`, extras `reply_id`
 * (echoed) + `result` = `OK:<path>|<bytes>|<human size>|<n> categories` (EXPORT_STATE),
 * `OK:` + `id<TAB>label` lines (LIST_CATEGORIES), or `ERROR:<reason>`. Exactly one terminal
 * reply, single-fire guarded. NO binders and NO ordered-result reliance — EMUI severs both
 * between third-party apps (verified 2026-07-23); the plain reply broadcast is the only
 * working channel. [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] so a stopped caller still hears it.
 *
 * Progress: while exporting, plain broadcasts to `reply_package` with action `progress_action`,
 * extras `reply_id`, `app` (display label), `text` (numbers-first, e.g. `区分 3/7 — Widgets`),
 * and structured `current`/`total` (long) + `unit` (String).
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()
        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val items = intent.getStringExtra(EXTRA_ITEMS)?.trim().orEmpty()

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (replyAction.isEmpty() || replyPackage.isEmpty()) return
            if (!replied.compareAndSet(false, true)) return
            app.sendBroadcast(Intent(replyAction).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_REPLY_ID, replyId)
                putExtra(EXTRA_RESULT, result)
            })
        }

        // Gate first, and report "disabled" and "bad token" distinctly (renrakusaki convention).
        if (!AutomationAuth.enabled(app)) {
            reply("ERROR:automation disabled")
            return
        }
        if (!AutomationAuth.isTokenValid(app, token)) {
            reply("ERROR:bad token")
            return
        }

        when (action) {
            ACTION_LIST_CATEGORIES -> {
                // id⇥label⇥parent⇥on|off — the fourth field says whether the item starts ticked in the
                // caller's picker. No category here has parts, so the third field is always empty.
                reply(
                    "OK:" + SettingsBackup.Cat.entries.joinToString("\n") {
                        "${it.id}\t${it.label}\t\t${if (it.defaultSelected) "on" else "off"}"
                    },
                )
            }
            ACTION_CANCEL_EXPORT -> {
                // Fire-and-forget: the cancel sends NO reply of its own. The one terminal reply belongs
                // to the export it stops, which answers ERROR:cancelled through its own single-fire
                // guard. Safe to send at any time — nothing running is a silent no-op.
                StateExportRun.requestCancel(replyId)
            }
            ACTION_EXPORT_STATE -> {
                // Absent items = OUR default set (the `on` ones), which the contract distinguishes from
                // "everything" — the two coincide here only because nothing in this app is opt-out.
                val cats: Set<SettingsBackup.Cat> = if (items.isEmpty()) {
                    SettingsBackup.Cat.entries.filter { it.defaultSelected }.toSet()
                } else {
                    val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val resolved = ids.mapNotNull { SettingsBackup.Cat.byId(it) }
                    if (resolved.size != ids.size) {
                        reply("ERROR:unknown category in items: $items")
                        return
                    }
                    resolved.toSet()
                }
                val appLabel = app.packageManager.getApplicationLabel(app.applicationInfo).toString()
                val fileName = SettingsBackup.exportFileName()

                fun progress(done: Int, total: Int, catLabel: String) {
                    if (progressAction.isEmpty() || replyPackage.isEmpty()) return
                    app.sendBroadcast(Intent(progressAction).apply {
                        setPackage(replyPackage)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(EXTRA_REPLY_ID, replyId)
                        putExtra(EXTRA_PROGRESS_APP, appLabel)
                        putExtra(EXTRA_PROGRESS_TEXT, "区分 $done/$total — $catLabel")
                        putExtra(EXTRA_PROGRESS_CURRENT, done.toLong())
                        putExtra(EXTRA_PROGRESS_TOTAL, total.toLong())
                        putExtra(EXTRA_PROGRESS_UNIT, "区分")
                    })
                }

                // One export at a time — the contract forbids two, and CANCEL_EXPORT's "the one you are
                // running" only means anything while that holds.
                if (!StateExportRun.begin(replyId)) {
                    reply("ERROR:export already running")
                    return
                }

                // The export writes ZIP entries + walks Room — go async and finish from IO.
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val db = OpenTaskerApp_NoHilt.db
                        val bytes: Long
                        val shownPath: String
                        if (pathOverride.isNotEmpty()) {
                            // Absolute-directory override (MANAGE_EXTERNAL_STORAGE) — the normal
                            // automation route; %BR_Dir wins over the app's own configured dir.
                            val dir = File(pathOverride)
                            dir.mkdirs()
                            if (!dir.isDirectory) throw IllegalArgumentException("not a directory: $pathOverride")
                            // Write to <final>.part and rename only on success, so a cancelled or failed
                            // run can leave the directory EXACTLY as it found it — no short archive
                            // sitting at the real name for a later restore to pick up.
                            val part = File(dir, "$fileName.part")
                            val file = File(dir, fileName)
                            StateExportRun.onDiscard { part.delete() }
                            part.outputStream().use { out ->
                                SettingsBackup.export(
                                    app, db, BuildConfig.VERSION_NAME, cats, out, ::progress,
                                    isCancelled = StateExportRun::isCancelled,
                                )
                            }
                            if (!part.renameTo(file)) throw IllegalStateException("cannot finalise ${file.name}")
                            bytes = file.length()
                            shownPath = file.absolutePath
                        } else {
                            val dir = SettingsBackup.exportDir(app)
                                ?: throw IllegalStateException("no-directory (no path extra and no export directory configured)")
                            // SAF: a ".part" display name gets mangled by providers that re-derive the
                            // extension, so the document is created under its final name and DELETED on
                            // the way out instead. Same guarantee, different mechanism.
                            val doc = dir.createFile("application/zip", fileName)
                                ?: throw IllegalStateException("cannot create $fileName in export directory")
                            StateExportRun.onDiscard { doc.delete() }
                            app.contentResolver.openOutputStream(doc.uri)?.use { out ->
                                SettingsBackup.export(
                                    app, db, BuildConfig.VERSION_NAME, cats, out, ::progress,
                                    isCancelled = StateExportRun::isCancelled,
                                )
                            } ?: throw IllegalStateException("cannot open $fileName for writing")
                            bytes = doc.length()
                            shownPath = "${SettingsBackup.dirLabel(app)}/${doc.name ?: fileName}"
                        }
                        reply("OK:$shownPath|$bytes|${humanSize(bytes)}|${cats.size} categories")
                    } catch (e: ExportCancelledException) {
                        // The terminal reply for the stopped request, sent even though 自由作業盤 stopped
                        // listening the moment 中止 was pressed: it is what proves the run really ended
                        // rather than carrying on unseen.
                        StateExportRun.discard()
                        reply("ERROR:cancelled")
                    } catch (e: Exception) {
                        StateExportRun.discard()
                        reply("ERROR:${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        StateExportRun.end()
                        pending.finish()
                    }
                }
            }
            else -> reply("ERROR:unknown action: $action")
        }
    }

    companion object {
        const val ACTION_EXPORT_STATE = "shiroikuma.jiyusagyoban.action.EXPORT_STATE"
        const val ACTION_LIST_CATEGORIES = "shiroikuma.jiyusagyoban.action.LIST_CATEGORIES"
        const val ACTION_CANCEL_EXPORT = "shiroikuma.jiyusagyoban.action.CANCEL_EXPORT"

        // Contract extras — deliberately bare names, shared verbatim by every sister app.
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PATH = "path"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_PROGRESS_ACTION = "progress_action"
        const val EXTRA_REPLY_ACTION = "reply_action"
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_REPLY_ID = "reply_id"
        const val EXTRA_RESULT = "result"
        const val EXTRA_PROGRESS_APP = "app"
        const val EXTRA_PROGRESS_TEXT = "text"
        const val EXTRA_PROGRESS_CURRENT = "current"
        const val EXTRA_PROGRESS_TOTAL = "total"
        const val EXTRA_PROGRESS_UNIT = "unit"

        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
