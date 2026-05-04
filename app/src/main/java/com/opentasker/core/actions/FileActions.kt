package com.opentasker.core.actions

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
        val varName = args["var"] ?: "result"
        return try {
            val content = File(path).readText()
            ctx.variables.set(varName, content)
            ctx.logger("Read $path → \$$varName")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("read failed: ${e.message}")
        }
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
        val text = args["text"] ?: ""
        return try {
            File(path).writeText(text)
            ctx.logger("Write $path")
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
        val text = args["text"] ?: ""
        return try {
            File(path).appendText(text)
            ctx.logger("Append to $path")
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
            File(path).delete()
            ctx.logger("Delete $path")
            ActionResult.Success
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
        val varName = args["var"] ?: "result"
        return try {
            val dir = File(path)
            val files = dir.listFiles()?.joinToString("\n") { it.name } ?: ""
            ctx.variables.set(varName, files)
            ctx.logger("List $path → \$$varName")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("list failed: ${e.message}")
        }
    }
}
