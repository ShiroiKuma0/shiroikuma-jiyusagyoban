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

        val path = args["file"]?.trim()?.ifEmpty { null }
            ?: return fail(ctx, prefix, store, "no file given — pass the path to a captured face, or browse= a directory")

        val file = File(path)
        if (!file.isFile) return fail(ctx, prefix, store, "no such file: $path")
        if (file.length() < 1024) {
            return fail(ctx, prefix, store, "${file.name} is only ${file.length()} bytes — not a face")
        }

        ctx.logger("Huawei watch face: sending ${file.name} (${file.length()} bytes)")
        val result = HuaweiSyncRunner.uploadWatchFace(ctx.app, address, file) { sent ->
            ctx.variables.set("${prefix}FaceSent", sent.toString())
        }
        return result.fold(
            onSuccess = { r ->
                val text = "${r.message} · ${r.bytesSent} B in ${r.blocks} blocks"
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
