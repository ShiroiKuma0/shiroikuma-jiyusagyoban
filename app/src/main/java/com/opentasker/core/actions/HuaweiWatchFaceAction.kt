package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.io.File

/**
 * `Install Huawei watch face` — push a captured face onto the band.
 *
 * ## Where the faces come from
 *
 * Huawei Health downloads a face as an encrypted `themeV2Cipher` package, decrypts it, uploads the
 * result to the band, and deletes the download immediately. So the file that goes over the air never
 * exists on disk in a usable form, and the only way to obtain one is to capture the upload itself.
 * That is how the faces in `%Huawei_FaceDir` were made, and each was verified byte-for-byte against
 * the SHA-256 that Health sent the band before transferring it.
 *
 * The practical consequence: **this installs faces you already own** — ones Health put on the band
 * while it was paired to another phone. It is a way to rotate between them without re-pairing, not a
 * way to obtain new ones.
 *
 * The band verifies the digest, so a corrupt or truncated file is refused rather than installed
 * broken, and this reports which happened.
 */
class HuaweiWatchFaceAction : Action {
    override val id = "huawei.watchface"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        // A directory instead of a file opens the library rather than installing anything. Kept on
        // this action rather than given its own id because it is the same job seen from the other
        // end — 白い熊 picking the face instead of a task naming it.
        args["browse"]?.trim()?.ifEmpty { null }?.let { dir ->
            com.opentasker.ui.charts.huawei.HuaweiFacesActivity.open(ctx.app, dir)
            ctx.variables.set("${prefix}Summary", "opened the watch-face library")
            return ActionResult.Success
        }

        // Delete on its own, installing nothing. The band holds a dozen faces and making room is
        // an operation in its own right — until now the only way to remove one from a task was to
        // install something over it, which is not the same job and not always what is wanted.
        args["remove"]?.trim()?.ifEmpty { null }?.let { spec ->
            val face = spec.removeSuffix(".bin").substringAfterLast('/')
            val id = face.substringBefore('_')
            val version = face.substringAfter('_', "")
            if (version.isEmpty()) {
                return fail(ctx, prefix, store, "remove must be <assetId>_<version>, not '$spec'")
            }
            val gone = HuaweiSyncRunner.deleteWatchFace(ctx.app, address, id, version)
            val text = when {
                gone.getOrNull() == true -> "$face removed"
                gone.isSuccess -> "$face is still on the band"
                else -> gone.exceptionOrNull()?.message ?: "failed"
            }
            ctx.variables.set("${prefix}Summary", text)
            store?.let { ctx.variables.set(it, text) }
            ctx.logger("Huawei watch face: $text")
            return if (gone.getOrNull() == true) ActionResult.Success else ActionResult.Failure(text)
        }

        val path = args["file"]?.trim()?.ifEmpty { null }
            ?: return fail(ctx, prefix, store, "no file given — pass the path to a captured face, or browse= a directory")

        val file = File(path)
        if (!file.isFile) return fail(ctx, prefix, store, "no such file: $path")
        if (file.length() < 1024) {
            return fail(ctx, prefix, store, "${file.name} is only ${file.length()} bytes — not a face")
        }

        // Remove one face first, inside the same session. The band holds a dozen and refuses the
        // next one in silence, so "make room then install" as two separate connections means two
        // pairings and a window where the room is free and something else could take it. It is
        // also what makes the install repeatable from a task at all: reinstalling a face the band
        // already holds is otherwise short-circuited to an activate, which sends no bytes.
        val evict = args["evict"]?.trim()?.ifEmpty { null }?.let { spec ->
            val name = spec.removeSuffix(".bin").substringAfterLast('/')
            val id = name.substringBefore('_')
            val version = name.substringAfter('_', "")
            if (version.isEmpty()) {
                return fail(ctx, prefix, store, "evict must be <assetId>_<version>, not '$spec'")
            }
            id to version
        }

        ctx.logger("Huawei watch face: sending ${file.name} (${file.length()} bytes)")
        val result = HuaweiSyncRunner.uploadWatchFace(ctx.app, address, file, evict) { sent ->
            ctx.variables.set("${prefix}FaceSent", sent.toString())
        }
        return result.fold(
            onSuccess = { r ->
                // A task has no dialog to ask "which face should go?" in, so the answer it can act
                // on has to be in the text: what the band is holding, and which one it is showing.
                // Without this a headless run reports only that there was no room, which is the one
                // thing 白い熊 already knew.
                val shelf = if (!r.needsRoom) "" else r.store?.faces.orEmpty()
                    .joinToString(", ") { it.assetId + if (it.showing) " (showing)" else "" }
                    .let { if (it.isEmpty()) "" else " · on the band: $it" }
                val text = "${r.message} · ${r.bytesSent} B in ${r.blocks} blocks$shelf"
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei watch face: $text")
                if (r.ok) ActionResult.Success else ActionResult.Failure(text)
            },
            onFailure = { fail(ctx, prefix, store, it.message ?: it::class.java.simpleName) },
        )
    }

    private fun fail(ctx: ActionContext, prefix: String, store: String?, why: String): ActionResult {
        ctx.variables.set("${prefix}Summary", why)
        store?.let { ctx.variables.set(it, why) }
        return ActionResult.Failure(why)
    }
}
