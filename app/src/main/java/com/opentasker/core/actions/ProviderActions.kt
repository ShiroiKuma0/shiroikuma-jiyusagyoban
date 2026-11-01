package com.opentasker.core.actions

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * `Call Provider` — `ContentResolver.call(authority, method, arg, extras)` against another app's
 * content provider, with the answer spread into task variables.
 *
 * This is the door the SMS-over-Jami bridge walks through: 白い熊 GNU Jami
 * (`shiroikuma.jami.automation`) and 白い熊 メッセージ (`shiroikuma.messeji.automation`) expose their
 * bridge methods as caller-verified provider calls — exact package, uid and pinned signing
 * certificate — because a provider call, unlike an activity start, works from the background and
 * returns a value. The sister apps keep their answers flat on purpose so a task can read them:
 *
 *  - a String / number / boolean        → `%<prefix>_<key>`
 *  - a String[] / list of strings       → the array `%<prefix>_<key>` (`%<prefix>_<key>1…n`)
 *  - a nested Bundle                    → `%<prefix>_<key>_<subkey>` (one level)
 *  - a Bundle[] / list of Bundles       → one array per field, `%<prefix>_<key>_<field>`, all the
 *                                         same length (item i of every array belongs together), plus
 *                                         `%<prefix>_<key>_count` — a list a task walks with For
 *  - a ParcelFileDescriptor             → copied to a file; `%<prefix>_<key>` is its path
 *  - a Uri                              → its string
 *
 * Keys keep their own spelling, with anything that is not a letter, digit or `_` folded to `_`. With
 * the default lowercase prefix every result is task-local, so a bridge task never leaks a message
 * body into the global namespace.
 *
 * An answer carrying `status` = ERROR (or a legacy `result` beginning with `ERROR`) still publishes
 * every value, then FAILS the action with the app's own `error` text, so a Try block catches it.
 *
 * Args:
 *   - "authority": the provider authority (e.g. `shiroikuma.jami.automation`)
 *   - "method":    the provider method (e.g. `bridge_send_text`)
 *   - "arg":       the optional String argument
 *   - "extra1_key"/"extra1_value"/"extra1_type" … extra8: extras, String unless typed (int / long /
 *                  float / bool, or array = a String[] of "|"-separated items)
 *   - "grant_uri": content:// URIs or local file paths ("|"-separated) to lend the provider for this
 *                  call — read access is granted to the provider's package first (a file is offered
 *                  through our FileProvider), and the URI strings travel as the extra "grant_key"
 *   - "grant_key": the extra that carries the granted URI(s) (default `uri`)
 *   - "grant_array": send the URI(s) as a String[] even when there is only one
 *   - "prefix":    the variable prefix (default `pc`)
 *   - "file_dir":  where returned file descriptors are copied (default the app's `user_files/provider`)
 *   - "timeout":   seconds (default 30, max 55)
 */
class ProviderCallAction : Action {
    override val id = "provider.call"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val authority = args["authority"]?.trim()?.ifBlank { null }
            ?: return ActionResult.Failure("missing authority")
        val method = args["method"]?.trim()?.ifBlank { null }
            ?: return ActionResult.Failure("missing method")
        val arg = args["arg"]?.ifEmpty { null }
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: DEFAULT_PREFIX
        val timeoutSec = args["timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
        val fileDir = args["file_dir"]?.trim()?.ifBlank { null }?.let(::File)
            ?: File(ctx.app.filesDir, "user_files/provider")

        val extras = Bundle()
        for (i in 1..MAX_EXTRAS) {
            val key = args["extra${i}_key"]?.trim()?.ifBlank { null } ?: continue
            putTyped(extras, key, args["extra${i}_value"].orEmpty(), args["extra${i}_type"])
        }

        val providerPackage = runCatching {
            ctx.app.packageManager.resolveContentProvider(authority, 0)?.packageName
        }.getOrNull() ?: return ActionResult.Failure("no provider '$authority' on this phone (or it is not visible to us)")

        args["grant_uri"]?.trim()?.ifBlank { null }?.let { rawList ->
            val uris = rawList.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.map { raw ->
                val uri = if (raw.startsWith("content://")) {
                    Uri.parse(raw)
                } else {
                    val file = File(raw)
                    if (!file.isFile) return ActionResult.Failure("grant_uri: no such file $raw")
                    runCatching { FileProvider.getUriForFile(ctx.app, "${ctx.app.packageName}.fileprovider", file) }
                        .getOrElse { return ActionResult.Failure("grant_uri: $raw is outside the shareable paths") }
                }
                ctx.app.grantUriPermission(providerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uri.toString()
            }
            val key = args["grant_key"]?.trim()?.ifBlank { null } ?: "uri"
            // Messēji's part_uris is a String[] even for one picture; Jami's uri is a single String.
            if (isTrue(args["grant_array"]) || uris.size > 1) {
                extras.putStringArray(key, uris.toTypedArray())
            } else {
                extras.putString(key, uris.first())
            }
        }

        val answer = try {
            withTimeoutOrNull(timeoutSec * 1000L) {
                withContext(Dispatchers.IO) {
                    ctx.app.contentResolver.call(Uri.parse("content://$authority"), method, arg, extras)
                }
            } ?: return ActionResult.Failure("$authority/$method: no answer within ${timeoutSec}s (or the app returned nothing)")
        } catch (ex: SecurityException) {
            return ActionResult.Failure("$authority/$method refused us: ${ex.message}", ex)
        } catch (ex: Exception) {
            return ActionResult.Failure("$authority/$method failed: ${ex.message}", ex)
        }

        val published = withContext(Dispatchers.IO) { publish(ctx, prefix, answer, fileDir) }
        val status = answer.getString("status").orEmpty()
        val legacy = answer.getString("result").orEmpty()
        val error = answer.getString("error").orEmpty()
        if (status.equals("ERROR", ignoreCase = true) || legacy.startsWith("ERROR")) {
            return ActionResult.Failure("$authority/$method: ${error.ifEmpty { legacy.ifEmpty { "ERROR" } }}")
        }
        ctx.logger("Provider $authority/$method → $published value(s) under %${prefix}_*")
        return ActionResult.Success
    }

    /** Spreads one answer into variables; returns how many it wrote. */
    private fun publish(ctx: ActionContext, prefix: String, bundle: Bundle, fileDir: File): Int {
        var count = 0
        for (key in bundle.keySet()) {
            val name = "${prefix}_${varKey(key)}"
            @Suppress("DEPRECATION")
            when (val value = bundle.get(key)) {
                null -> {}
                is Bundle -> for (sub in value.keySet()) {
                    @Suppress("DEPRECATION")
                    scalar(value.get(sub))?.let { ctx.variables.set("${name}_${varKey(sub)}", it); count++ }
                }
                is Array<*> -> count += publishList(ctx, name, value.toList())
                is List<*> -> count += publishList(ctx, name, value)
                is ParcelFileDescriptor -> {
                    ctx.variables.set(name, copyDescriptor(value, fileDir, key).absolutePath); count++
                }
                else -> scalar(value)?.let { ctx.variables.set(name, it); count++ }
            }
        }
        return count
    }

    private fun publishList(ctx: ActionContext, name: String, items: List<*>): Int {
        if (items.isNotEmpty() && items.all { it is Bundle }) {
            val bundles = items.filterIsInstance<Bundle>()
            val fields = bundles.flatMap { it.keySet() }.distinct()
            for (field in fields) {
                @Suppress("DEPRECATION")
                ctx.variables.setArray("${name}_${varKey(field)}", bundles.map { scalar(it.get(field)).orEmpty() })
            }
            ctx.variables.set("${name}_count", bundles.size.toString())
            return fields.size + 1
        }
        ctx.variables.setArray(name, items.map { scalar(it).orEmpty() })
        return 1
    }

    private fun scalar(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Uri -> value.toString()
        is Number, is Boolean, is Char -> value.toString()
        is CharSequence -> value.toString()
        is IntArray -> value.joinToString(",")
        is LongArray -> value.joinToString(",")
        else -> value.toString()
    }

    private fun copyDescriptor(pfd: ParcelFileDescriptor, dir: File, key: String): File {
        dir.mkdirs()
        val target = File(dir, "${varKey(key)}_${System.currentTimeMillis()}")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun putTyped(bundle: Bundle, key: String, value: String, type: String?) {
        when (type?.trim()?.lowercase(Locale.US)) {
            "int" -> value.trim().toIntOrNull()?.let { bundle.putInt(key, it) } ?: bundle.putString(key, value)
            "long" -> value.trim().toLongOrNull()?.let { bundle.putLong(key, it) } ?: bundle.putString(key, value)
            "float" -> value.trim().toFloatOrNull()?.let { bundle.putFloat(key, it) } ?: bundle.putString(key, value)
            "bool", "boolean" -> bundle.putBoolean(key, isTrue(value))
            // A String[]: items separated by "|" — a %array() joins with "," which a name or a URI
            // can contain, so the bar is the one separator a task writes on purpose.
            "array" -> bundle.putStringArray(
                key,
                if (value.isEmpty()) emptyArray() else value.split(LIST_SEPARATOR).map { it.trim() }.toTypedArray(),
            )
            else -> bundle.putString(key, value)
        }
    }

    private fun isTrue(value: String?): Boolean = value?.trim()?.lowercase(Locale.US) in setOf("true", "1", "yes", "on")

    companion object {
        private const val DEFAULT_PREFIX = "pc"
        private const val DEFAULT_TIMEOUT_SEC = 30
        // The engine's default per-action budget is 60 s; stay under it.
        private const val MAX_TIMEOUT_SEC = 55
        private const val MAX_EXTRAS = 8
        private const val LIST_SEPARATOR = "|"

        internal fun varKey(key: String): String = key.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
    }
}

/**
 * `Copy From URI` — copy a content:// URI another app lent us into a local file, the other half of the
 * bridge's picture hand-over: Jami announces a received file as a URI granted to us, and Messēji
 * wants a file it can store as an MMS part (and the reverse).
 *
 * Args:
 *   - "uri":    the content:// (or file://) URI
 *   - "dir":    the target directory (default the app's `user_files/bridge`)
 *   - "name":   the file name (default the provider's display name, else a timestamp)
 *   - "prefix": the variable prefix (default `uri`) — writes `%<prefix>_path`, `%<prefix>_name`,
 *               `%<prefix>_mime` and `%<prefix>_size`
 */
class CopyFromUriAction : Action {
    override val id = "file.from_uri"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val raw = args["uri"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing uri")
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return ActionResult.Failure("invalid uri: $raw")
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "uri"
        val dir = args["dir"]?.trim()?.ifBlank { null }?.let(::File) ?: File(ctx.app.filesDir, "user_files/bridge")
        val resolver = ctx.app.contentResolver
        return try {
            withContext(Dispatchers.IO) {
                val displayName = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()
                val name = (args["name"]?.trim()?.ifBlank { null } ?: displayName ?: "file_${System.currentTimeMillis()}")
                    .replace('/', '_')
                dir.mkdirs()
                val target = File(dir, name)
                val input = resolver.openInputStream(uri) ?: error("the provider gave no stream")
                input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
                ctx.variables.set("${prefix}_path", target.absolutePath)
                ctx.variables.set("${prefix}_name", target.name)
                ctx.variables.set("${prefix}_mime", resolver.getType(uri).orEmpty())
                ctx.variables.set("${prefix}_size", target.length().toString())
                ctx.logger("Copied $uri → ${target.absolutePath} (${target.length()} bytes)")
            }
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("no read access to $raw: ${ex.message}", ex)
        } catch (ex: Exception) {
            ActionResult.Failure("copy from $raw failed: ${ex.message}", ex)
        }
    }
}
