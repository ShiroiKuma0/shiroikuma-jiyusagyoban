package com.opentasker.core.actions

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
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
            val file = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            if (file.length() > MAX_READ_BYTES) {
                return ActionResult.Failure("file exceeds ${MAX_READ_BYTES / 1024 / 1024} MB read limit (${file.length()} bytes)")
            }
            val content = file.readText()
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
            val file = safeUserFile(ctx, path) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            file.parentFile?.mkdirs()
            file.writeText(text)
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
            val file = safeUserFile(ctx, path) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            file.parentFile?.mkdirs()
            file.appendText(text)
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
        return try {
            val dir = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
            if (!dir.isDirectory) return ActionResult.Failure("path is not a directory")
            val files = dir.listFiles()?.joinToString("\n") { it.name } ?: ""
            ctx.variables.set(varName, files)
            ctx.logger("List ${dir.name} to \$$varName")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("list failed: ${e.message}")
        }
    }
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
        val dir = safeUserFile(ctx, path) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
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
    val baseDir = File(ctx.app.filesDir, "user_files").canonicalFile
    val requested = File(baseDir, path.trimStart('/', '\\')).canonicalFile
    if (!requested.path.startsWith(baseDir.path + File.separator) && requested != baseDir) return null
    if (mustExist && !requested.exists()) return null
    return requested
}
