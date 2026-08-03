package com.opentasker.core.actions

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Read file contents.
 *
 * Args:
 *   - "path": file path
 *   - "var": variable name to store contents
 */
class ReadFileAction : Action {
    override val id = "file.read"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val varName = args["var"] ?: args["variable"] ?: "result"
        return try {
            val file = safeTarget(ctx, path, shared = sharedArg(args))
                ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            if (!file.exists()) return ActionResult.Failure("no such file: $path")
            if (file.length() > MAX_READ_BYTES) {
                return ActionResult.Failure("file exceeds ${MAX_READ_BYTES / 1024 / 1024} MB read limit (${file.length()} bytes)")
            }
            val content = readNoFollow(file)
            ctx.variables.set(varName, content)
            ctx.logger("Read ${file.name} to \$$varName")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("read failed: ${e.message}")
        }
    }

    companion object {
        private const val MAX_READ_BYTES = 1_048_576L // 1 MB
    }
}

/**
 * Write file contents (overwrites).
 *
 * Args:
 *   - "path": file path
 *   - "text": content to write
 */
class WriteFileAction : Action {
    override val id = "file.write"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val text = args["text"] ?: args["content"] ?: ""
        return try {
            val file = safeTarget(ctx, path, shared = sharedArg(args))
                ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_FILE_BYTES) {
                return ActionResult.Failure("content exceeds ${MAX_FILE_BYTES / 1024 / 1024} MB write limit ($bytes bytes)")
            }
            createSandboxDirs(ctx, file, sharedArg(args))
            writeNoFollow(file, text, append = false)
            ctx.logger("Write ${file.name}")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("write failed: ${e.message}")
        }
    }
}

/**
 * Append to file.
 *
 * Args:
 *   - "path": file path
 *   - "text": content to append
 */
class AppendFileAction : Action {
    override val id = "file.append"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val text = args["text"] ?: args["content"] ?: ""
        return try {
            val file = safeTarget(ctx, path, shared = sharedArg(args))
                ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_FILE_BYTES) {
                return ActionResult.Failure("append content exceeds ${MAX_FILE_BYTES / 1024 / 1024} MB write limit ($bytes bytes)")
            }
            val projectedSize = file.takeIf { it.exists() }?.length().orZero() + bytes
            if (projectedSize > MAX_FILE_BYTES) {
                return ActionResult.Failure("append would exceed ${MAX_FILE_BYTES / 1024 / 1024} MB file limit ($projectedSize bytes)")
            }
            createSandboxDirs(ctx, file, sharedArg(args))
            writeNoFollow(file, text, append = true)
            ctx.logger("Append to ${file.name}")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("append failed: ${e.message}")
        }
    }
}

/**
 * Delete file.
 *
 * Args:
 *   - "path": file path
 */
class DeleteFileAction : Action {
    override val id = "file.delete"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        return try {
            val file = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            if (!file.isFile) return ActionResult.Failure("delete only supports files")
            if (file.delete()) {
                ctx.logger("Delete ${file.name}")
                ActionResult.Success
            } else {
                ActionResult.Failure("delete failed")
            }
        } catch (e: Exception) {
            ActionResult.Failure("delete failed: ${e.message}")
        }
    }
}

/**
 * List files in a directory.
 *
 * Args:
 *   - "path": directory path
 *   - "var": variable name to store list
 *   - "pattern": optional glob pattern
 */
class ListFilesAction : Action {
    override val id = "file.list"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val varName = args["var"] ?: args["variable"] ?: "result"
        val pattern = args["pattern"].orEmpty().trim()
        return try {
            val dir = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            if (!dir.isDirectory) return ActionResult.Failure("path is not a directory")
            val matcher = if (pattern.isEmpty()) {
                null
            } else {
                fileNameMatcher(pattern) ?: return ActionResult.Failure("invalid file name pattern")
            }
            val files = dir.listFiles()
                ?.filter { file -> matcher?.matches(File(file.name).toPath()) ?: true }
                ?.sortedWith(compareBy<File> { it.name.lowercase() }.thenBy { it.name })
                ?.joinToString("\n") { it.name }
                ?: ""
            ctx.variables.set(varName, files)
            ctx.logger("List ${dir.name} to \$$varName")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("list failed: ${e.message}")
        }
    }
}

/**
 * Builds a file-name glob matcher, or returns null for an invalid pattern (too long, contains a
 * path separator/null byte, or is not a valid glob) so the caller can surface a clean validation
 * failure instead of leaking a raw Java exception string. Callers pass a non-blank, trimmed pattern.
 */
private fun fileNameMatcher(pattern: String): java.nio.file.PathMatcher? {
    if (pattern.isEmpty() || pattern.length > MAX_LIST_PATTERN_CHARS) return null
    if (pattern.any { it == '/' || it == '\\' || it == '\u0000' }) return null
    return runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
}

/**
 * Move/rename a file within the sandbox.
 *
 * Args:
 *   - "from": source path (must exist)
 *   - "to": destination path
 */
class MoveFileAction : Action {
    override val id = "file.move"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val fromArg = args["from"] ?: return ActionResult.Failure("missing from")
        val toArg = args["to"] ?: return ActionResult.Failure("missing to")
        val src = safeUserFile(ctx, fromArg, mustExist = true) ?: return ActionResult.Failure("source is outside 白い熊 自由作業盤 files")
        val dest = safeUserFile(ctx, toArg) ?: return ActionResult.Failure("destination is outside 白い熊 自由作業盤 files")
        return try {
            dest.parentFile?.mkdirs()
            val ok = src.renameTo(dest) || run {
                src.copyTo(dest, overwrite = true); src.delete()
            }
            if (ok) {
                ctx.logger("Moved ${src.name} → ${dest.name}")
                ActionResult.Success
            } else {
                ActionResult.Failure("move failed")
            }
        } catch (e: Exception) {
            ActionResult.Failure("move failed: ${e.message}")
        }
    }
}

/**
 * Create a directory (and any missing parents) within the sandbox.
 *
 * Args:
 *   - "path": directory path
 */
class MakeDirectoryAction : Action {
    override val id = "file.mkdir"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val dir = safeTarget(ctx, path, shared = sharedArg(args))
            ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
        return if (dir.isDirectory || dir.mkdirs()) {
            ctx.logger("Created directory ${dir.name}")
            ActionResult.Success
        } else {
            ActionResult.Failure("could not create directory")
        }
    }
}

/**
 * Open a file with another app (ACTION_VIEW) via a content:// URI from our FileProvider.
 *
 * Args:
 *   - "path": file path (must exist)
 *   - "mime": optional MIME type override (otherwise guessed from the extension)
 */
class OpenFileAction : Action {
    override val id = "file.open"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val file = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
        return try {
            val uri = FileProvider.getUriForFile(ctx.app, "${ctx.app.packageName}.fileprovider", file)
            val mime = args["mime"]?.takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.app.startActivity(intent)
            ctx.logger("Opened ${file.name}")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("could not open file: ${e.message}")
        }
    }
}

internal fun safeUserFile(ctx: ActionContext, path: String, mustExist: Boolean = false): File? {
    if (path.isBlank() || path.contains('\u0000')) return null
    return resolveSandboxTarget(File(ctx.app.filesDir, "user_files").canonicalFile, path, mustExist)
}

/**
 * The base a file action resolves against: the private sandbox by default, shared storage when the
 * action opts in with `shared`.
 *
 * The sandbox is the right default — a task should not be able to touch the whole filesystem by
 * accident, and an absolute path is folded into it rather than escaping. But some files are the
 * user's own and belong in their own tree, where a file manager and their backups can see them;
 * 接続's run history is one. This makes that an explicit per-action choice instead of a silent
 * rewrite into a directory nothing else can reach.
 *
 * Shared paths keep every protection the sandbox has — lexical normalization, containment under the
 * external-storage root, the symlink component scan and no-follow open — only the base differs. It
 * grants no access the app does not already hold for its backups.
 */
internal fun fileBase(ctx: ActionContext, shared: Boolean): File =
    if (shared) android.os.Environment.getExternalStorageDirectory().canonicalFile
    else File(ctx.app.filesDir, "user_files").canonicalFile

internal fun safeTarget(
    ctx: ActionContext,
    path: String,
    mustExist: Boolean = false,
    shared: Boolean = false,
): File? {
    if (path.isBlank() || path.contains('\u0000')) return null
    val base = fileBase(ctx, shared)
    // An absolute path under the base is expressed relative to it, so "/sdcard/x" and "x" mean the
    // same file. Every spelling of the external root has to be recognised, not just the canonical
    // one: /sdcard is a symlink to /storage/emulated/0, so matching on the canonical path alone
    // turned "/sdcard/〇/…" into "/storage/emulated/0/sdcard/〇/…" — a real doubled root that got
    // created and written to, silently.
    val relative = if (!shared) path else {
        val root = EXTERNAL_ROOTS.plus(base.path)
            .firstOrNull { path == it || path.startsWith("$it/") }
        if (root != null) path.removePrefix(root) else path
    }
    return resolveSandboxTarget(base, relative, mustExist)
}

/** Every spelling of the primary external volume; /sdcard and /storage/self/primary are symlinks. */
private val EXTERNAL_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary")

/** `shared=true` on a file action: resolve against the user's storage instead of the sandbox. */
internal fun sharedArg(args: Map<String, String>): Boolean =
    args["shared"]?.trim()?.lowercase() in setOf("true", "1", "yes", "on")

/**
 * Resolves [path] against the sandbox [baseDir] and refuses anything that could escape it,
 * including symlink-based TOCTOU escapes. The target is resolved lexically (NOT canonicalized) so a
 * malicious symlink component is detected rather than transparently followed and hidden, then every
 * existing component from the base down to the target is checked for symlinks. Callers must still
 * open with no-follow semantics ([writeNoFollow]/[readNoFollow]) to close the residual check-to-open
 * window.
 */
internal fun resolveSandboxTarget(baseDir: File, path: String, mustExist: Boolean = false): File? {
    if (path.isBlank() || path.any { it.code < 0x20 }) return null
    // Lexical normalization collapses "." / ".." without touching the filesystem, so an escape via
    // "../.." is rejected here and a real symlink is left intact for the component scan below.
    val normalized = File(baseDir, path.trimStart('/', '\\')).toPath().normalize().toFile()
    if (normalized != baseDir && !normalized.path.startsWith(baseDir.path + File.separator)) return null
    if (mustExist && !normalized.exists()) return null
    if (containsSymlinkComponent(baseDir, normalized)) return null
    return normalized
}

/** True if any existing path component between [baseDir] (exclusive) and [target] is a symlink. */
private fun containsSymlinkComponent(baseDir: File, target: File): Boolean {
    var current: File? = target
    while (current != null && current != baseDir) {
        if (current.exists() && Files.isSymbolicLink(current.toPath())) return true
        current = current.parentFile
    }
    return false
}

/** Creates the sandboxed parent directories, then re-verifies no symlink component slipped in. */
private fun createSandboxDirs(ctx: ActionContext, file: File, shared: Boolean = false) {
    file.parentFile?.mkdirs()
    val baseDir = fileBase(ctx, shared)
    if (containsSymlinkComponent(baseDir, file)) {
        throw java.io.IOException("path component became a symlink")
    }
}

/** Writes [text] to [file] without following a symlink at the final component (O_NOFOLLOW). */
private fun writeNoFollow(file: File, text: String, append: Boolean) {
    val lastWriteMode: OpenOption =
        if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING
    val options: Array<OpenOption> = arrayOf(
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        lastWriteMode,
        LinkOption.NOFOLLOW_LINKS,
    )
    Files.newOutputStream(file.toPath(), *options).use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

/** Reads [file] without following a symlink at the final component (O_NOFOLLOW). */
private fun readNoFollow(file: File): String =
    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        .use { it.readBytes().toString(Charsets.UTF_8) }

private fun Long?.orZero(): Long = this ?: 0L

private const val MAX_LIST_PATTERN_CHARS = 128
private const val MAX_FILE_BYTES = 1_048_576L
