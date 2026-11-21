package com.opentasker.core.actions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.engine.SUB_TASK_PARAM_PREFIX
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Remote updates of a phone nobody is holding (白い熊, 2026-11-22): the 携帯 relay phones sit at
// home, and a new build or a new bundle reaches them as a file in a Jami conversation. These two
// actions are what a task does with such a file — install an APK, import a bundle — and the two
// receivers below carry an installation's result back, including across the app's own death when
// the APK is this app.

/**
 * `Install Package` — install or update an APK from a file, without a hand on the phone where
 * Android allows it.
 *
 * Android 12+ installs silently when the installer asks for it and either updates itself or is the
 * installer of record of the app it updates (so 自由作業盤 updating itself is always silent; another
 * app is silent from the second time on — the first install by this app asks once). Where a
 * confirmation still appears, the accessibility service presses its button.
 *
 * Updating this app kills this app: the action then never returns. So for a self-update the action
 * first stores what should happen afterwards — [PackageReplacedReceiver] restarts the engine and
 * runs `report_task`, with every `param:<name>` argument and the `<prefix>_*` results as that task's
 * local variables.
 *
 * Args:
 *   - "path":        the APK file
 *   - "name":        the file's real name, for the kept copy (a file handed over by a provider has
 *                    lost its name); default: the file's own name
 *   - "keep_dir":    where a copy of the APK is kept (default `/sdcard/tmp`; `-` keeps none) — a build
 *                    is never thrown away, and an existing file of that name is left alone
 *   - "reinstall":   `true` installs a build that is already the installed one (default: refused)
 *   - "report_task": the task run after a SELF-update restarted the app
 *   - "param:<n>":   handed to that task as the local variable `%<n>`
 *   - "prefix":      result prefix (default `pi`): `%pi_ok` (true/false), `%pi_message`,
 *                    `%pi_package`, `%pi_label`, `%pi_version`, `%pi_old_version`, `%pi_self`,
 *                    `%pi_kept`
 *   - "timeout":     seconds to wait for the installer (default 180, max 280)
 */
class PackageInstallAction : Action {
    override val id = "package.install"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "pi"
        fun put(key: String, value: String) = ctx.variables.set("${prefix}_$key", value)
        listOf("message", "package", "label", "version", "old_version", "kept").forEach { put(it, "") }
        put("ok", "false")
        put("self", "false")
        fun fail(message: String): ActionResult {
            put("message", message)
            return ActionResult.Failure(message)
        }

        val path = args["path"]?.trim().orEmpty()
        if (path.isEmpty()) return fail("missing path")
        val file = File(path)
        if (!file.isFile) return fail("no such file: $path")
        val timeoutSec = args["timeout"]?.trim()?.toIntOrNull()?.coerceIn(10, 280) ?: 180
        val app = ctx.app
        val pm = app.packageManager

        val archive = withContext(Dispatchers.IO) { runCatching { pm.getPackageArchiveInfo(path, 0) }.getOrNull() }
            ?: return fail("not an installable APK: ${file.name}")
        val pkg = archive.packageName
        val newCode = archive.longVersionCodeCompat()
        val newName = archive.versionName.orEmpty()
        val installed = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val self = pkg == app.packageName
        put("package", pkg)
        put("version", newName)
        put("old_version", installed?.versionName.orEmpty())
        put("self", self.toString())
        val label = runCatching {
            archive.applicationInfo?.let { info ->
                info.sourceDir = path
                info.publicSourceDir = path
                pm.getApplicationLabel(info).toString()
            }
        }.getOrNull().orEmpty().ifEmpty { pkg }
        put("label", label)

        // Kept before anything can go wrong, under the name the build was given.
        var keptPath = ""
        val keepDir = args["keep_dir"]?.trim().orEmpty().ifEmpty { "/sdcard/tmp" }
        if (keepDir != "-") {
            val keepName = args["name"]?.trim().orEmpty().ifEmpty { file.name }.substringAfterLast('/')
            val kept = File(keepDir, if (keepName.endsWith(".apk", ignoreCase = true)) keepName else "$keepName.apk")
            withContext(Dispatchers.IO) {
                runCatching {
                    kept.parentFile?.mkdirs()
                    if (!kept.exists()) file.copyTo(kept)
                    keptPath = kept.absolutePath
                    put("kept", keptPath)
                }.onFailure { ctx.logger("Install: could not keep a copy in $keepDir — ${it.message}") }
            }
        }

        if (installed != null && installed.longVersionCodeCompat() > newCode) {
            return fail("older than the installed build (${installed.versionName}) — Android refuses a downgrade")
        }
        // The same build again is refused unless asked for. A command that is read twice (its cursor
        // is written behind the engine, and a self-update kills the engine) would otherwise install,
        // restart and install again.
        val reinstall = args["reinstall"]?.trim()?.lowercase() in setOf("true", "1", "yes")
        if (installed != null && installed.longVersionCodeCompat() == newCode && !reinstall) {
            return fail("already on this build ($newName)")
        }
        if (!pm.canRequestPackageInstalls()) {
            return fail("白い熊 自由作業盤 is not allowed to install apps — allow “Install unknown apps” for it on that phone")
        }

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }
        val sessionId = try {
            withContext(Dispatchers.IO) {
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 1 shl 16) }
                        session.fsync(out)
                    }
                }
                id
            }
        } catch (ex: Exception) {
            return fail("could not hand the APK to the installer: ${ex.message}")
        }

        if (self) {
            PendingSelfUpdate.store(
                app,
                expectedCode = newCode,
                reportTask = args["report_task"]?.trim().orEmpty(),
                // Built with explicit receivers: a bare put() here is this function's own put(), which
                // writes a task variable — the first build stored an empty map that way.
                locals = mutableMapOf<String, String>().also { map ->
                    args.forEach { (key, value) ->
                        if (key.startsWith(SUB_TASK_PARAM_PREFIX)) map[key.removePrefix(SUB_TASK_PARAM_PREFIX)] = value
                    }
                    map["${prefix}_package"] = pkg
                    map["${prefix}_label"] = label
                    map["${prefix}_version"] = newName
                    map["${prefix}_old_version"] = installed?.versionName.orEmpty()
                    map["${prefix}_kept"] = keptPath
                    map["${prefix}_self"] = "true"
                },
                prefix = prefix,
            )
        }

        val outcome = CompletableDeferred<Pair<Boolean, String>>()
        PackageInstallResultReceiver.waiting[sessionId] = outcome
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    val intent = Intent(app, PackageInstallResultReceiver::class.java)
                        .setAction(PackageInstallResultReceiver.ACTION)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender)
                }
            }
            ctx.logger("Install $pkg $newName: session $sessionId committed")
            val result = withTimeoutOrNull(timeoutSec * 1000L) { outcome.await() }
            return when {
                result == null -> {
                    if (self) PendingSelfUpdate.clear(app)
                    fail(
                        if (PackageInstallResultReceiver.askedUser.remove(sessionId) == true)
                            "the phone is asking for a tap to confirm, and nothing pressed it within ${timeoutSec}s"
                        else "the installer did not answer within ${timeoutSec}s",
                    )
                }
                result.first -> {
                    // A self-update normally never reaches this line: the process is gone by now.
                    put("ok", "true")
                    put("message", result.second)
                    ActionResult.Success
                }
                else -> {
                    if (self) PendingSelfUpdate.clear(app)
                    fail(result.second)
                }
            }
        } catch (ex: Exception) {
            if (self) PendingSelfUpdate.clear(app)
            runCatching { installer.abandonSession(sessionId) }
            return fail("install failed: ${ex.message}")
        } finally {
            PackageInstallResultReceiver.waiting.remove(sessionId)
        }
    }
}

@Suppress("DEPRECATION")
private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

/** The installer's answers for [PackageInstallAction]'s sessions. */
class PackageInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a confirmation after all (a first install by this app, an older
                // Android, a vendor's own prompt). Show it and press its button ourselves; the final
                // status arrives in a second broadcast either way.
                askedUser[sessionId] = true
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    waiting[sessionId]?.complete(false to "the installer asked for a confirmation it did not describe")
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { AppLogger.error("OpenTasker", "Install: cannot show the confirmation", it) }
                Thread {
                    val pressed = ShiroiKumaAccessibilityService.clickByLabel(CONFIRM_LABELS, timeoutMs = 25_000)
                    AppLogger.info("OpenTasker", "Install: confirmation button pressed = ${pressed ?: "none found"}")
                }.start()
            }
            PackageInstaller.STATUS_SUCCESS -> waiting[sessionId]?.complete(true to "installed")
            else -> waiting[sessionId]?.complete(false to describe(status, message))
        }
    }

    private fun describe(status: Int, message: String): String {
        val kind = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the phone"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicts with the installed app (another signing key?)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "not compatible with this phone"
            PackageInstaller.STATUS_FAILURE_INVALID -> "the APK is invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
            else -> "failed"
        }
        return if (message.isBlank()) kind else "$kind — $message"
    }

    companion object {
        const val ACTION = "com.opentasker.action.PACKAGE_INSTALL_RESULT"
        val waiting = ConcurrentHashMap<Int, CompletableDeferred<Pair<Boolean, String>>>()
        val askedUser = ConcurrentHashMap<Int, Boolean>()

        /** The confirm button in the languages these phones run in. Exact labels, never “Cancel”. */
        private val CONFIRM_LABELS = listOf(
            "Update", "Install", "Update anyway", "Install anyway",
            "更新", "インストール", "Aktualizovat", "Instalovat",
        )
    }
}

/** What a self-update left behind for the new process to finish. */
object PendingSelfUpdate {
    private const val PREFS = "pending_self_update"
    private const val KEY = "record"
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    data class Record(val expectedCode: Long, val reportTask: String, val locals: Map<String, String>, val prefix: String)

    fun store(context: Context, expectedCode: Long, reportTask: String, locals: Map<String, String>, prefix: String) {
        val json = JSONObject()
            .put("expectedCode", expectedCode)
            .put("reportTask", reportTask)
            .put("prefix", prefix)
            .put("at", System.currentTimeMillis())
            .put("locals", JSONObject(locals))
        // commit(), not apply(): the process may be killed within the second.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }

    /** The record, removed as it is read; null when there is none or it is too old to mean anything. */
    fun take(context: Context): Record? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return null
        prefs.edit().remove(KEY).commit()
        return runCatching {
            val json = JSONObject(raw)
            if (System.currentTimeMillis() - json.getLong("at") > MAX_AGE_MS) return null
            val locals = json.getJSONObject("locals")
            Record(
                expectedCode = json.getLong("expectedCode"),
                reportTask = json.getString("reportTask"),
                locals = locals.keys().asSequence().associateWith { locals.getString(it) },
                prefix = json.getString("prefix"),
            )
        }.getOrNull()
    }
}

/**
 * This app was replaced by a new build. Android killed the old process and starts nothing by
 * itself, so: bring the engine back up (unless 白い熊 had stopped it), and finish what a
 * [PackageInstallAction] self-update left in [PendingSelfUpdate] — run its report task.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (EngineShutdown.isStopped(app)) {
            AppLogger.info("OpenTasker", "Updated — the engine stays stopped, as it was")
            PendingSelfUpdate.clear(app)
            return
        }
        val ready = runCatching {
            (app as? OpenTaskerApp_NoHilt)?.initializeAfterUnlock() ?: error("application unavailable")
        }.onFailure { AppLogger.error("OpenTasker", "Startup after an update failed", it) }.isSuccess
        if (!ready) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, AutomationService::class.java)) }
            .onFailure { AppLogger.error("OpenTasker", "Failed to start the engine after an update", it) }

        val record = PendingSelfUpdate.take(app) ?: return
        if (record.reportTask.isEmpty()) return
        // The broadcast is released at once; the foreground service keeps the process for the task.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                delay(SETTLE_MS)
                val db = OpenTaskerApp_NoHilt.db
                val task = db.taskDao().getAll().firstOrNull { it.name == record.reportTask }
                if (task == null) {
                    AppLogger.error("OpenTasker", "Updated, but the report task '${record.reportTask}' does not exist", null)
                    return@launch
                }
                val now = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
                val ok = now != null && now.longVersionCodeCompat() == record.expectedCode
                executeAndLogTask(
                    appContext = app,
                    db = db,
                    task = task.toDomain(),
                    source = "after update",
                    initialVariables = record.locals + mapOf(
                        "${record.prefix}_ok" to ok.toString(),
                        "${record.prefix}_message" to if (ok) "installed" else "the app restarted, but on build ${now?.versionName.orEmpty()}",
                    ),
                    logTag = "PackageReplaced",
                )
            }.onFailure { AppLogger.error("OpenTasker", "Report after an update failed", it) }
        }
    }

    private companion object {
        /** Let the engine and the network come up before the report is sent. */
        const val SETTLE_MS = 8_000L
    }
}

/**
 * `Import Bundle` — import an OpenTasker bundle file as the Import screen does with its defaults:
 * projects merged by name, a same-name task / profile / scene overwritten in place. An overwritten
 * profile keeps its on/off state, variables the bundle does not carry keep their values, and a
 * profile that is NEW arrives switched off.
 *
 * Args:
 *   - "path":   the bundle JSON
 *   - "prefix": result prefix (default `wi`): `%wi_ok`, `%wi_message`, `%wi_tasks`, `%wi_profiles`,
 *               `%wi_scenes`, `%wi_variables`, `%wi_projects` (names, comma-separated),
 *               `%wi_warnings` (one per line)
 */
class WorkspaceImportAction : Action {
    override val id = "workspace.import"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "wi"
        fun put(key: String, value: String) = ctx.variables.set("${prefix}_$key", value)
        listOf("message", "tasks", "profiles", "scenes", "variables", "projects", "warnings").forEach { put(it, "") }
        put("ok", "false")
        fun fail(message: String): ActionResult {
            put("message", message)
            return ActionResult.Failure(message)
        }
        val path = args["path"]?.trim().orEmpty()
        if (path.isEmpty()) return fail("missing path")
        val file = File(path)
        if (!file.isFile) return fail("no such file: $path")
        return withContext(Dispatchers.IO) {
            val bundle = runCatching { OpenTaskerBundleCodec.decode(file.readText(Charsets.UTF_8)) }
                .getOrElse { return@withContext fail("not an OpenTasker bundle: ${it.message}") }
            val report = runCatching { OpenTaskerBundleRepository(OpenTaskerApp_NoHilt.db).importBundle(bundle) }
                .getOrElse { return@withContext fail("import refused: ${it.message}") }
            put("ok", "true")
            put("tasks", report.insertedTasks.toString())
            put("profiles", report.insertedProfiles.toString())
            put("scenes", report.insertedScenes.toString())
            put("variables", report.insertedVariables.toString())
            put("projects", report.projectNames.joinToString(", "))
            put("warnings", (report.warnings + report.lossyWarnings).joinToString("\n"))
            put(
                "message",
                "tasks ${report.insertedTasks} · profiles ${report.insertedProfiles} · scenes ${report.insertedScenes} · variables ${report.insertedVariables}",
            )
            ctx.logger("Imported ${file.name}: ${report.insertedTasks} tasks, ${report.insertedProfiles} profiles")
            ActionResult.Success
        }
    }
}
