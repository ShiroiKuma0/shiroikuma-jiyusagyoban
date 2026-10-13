package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// window.mode — the Huawei floating window and split screen, without Huawei Home.
//
// Huawei Home's gesture-nav diagonals (up toward the top-right → floating window, toward the top-left
// → split screen) live in its own TouchInteractionService, which SystemUI binds only while Huawei Home
// is the default home. With 白い熊 雷起動盤 as home they are gone, and nothing outside SystemUI can bind
// that service. What CAN be reproduced is the result: `am start --windowingMode <N>` from the shell
// (Shizuku) moves an app's EXISTING task into a Huawei window mode, state kept.
//
// Huawei's modes are its own numbers (android.app.WindowConfiguration on EMUI 14.2):
//   100 = hwMultiwindow-primary (split, left), 101 = -secondary (split, right),
//   102 = hwMultiwindow-freeform (the floating window). AOSP's 5 (freeform) is accepted but drawn full
//   screen, so it is no use here.
// Measured on the Mate XT, 2026-10-11:
//   - the app must not be the top-most full-screen activity when the start arrives, or the start is
//     "delivered to the currently running top-most instance" and nothing changes — hence HOME first;
//   - after HOME the start brings the same task back (same task id, not restarted) in the new mode.
// ---------------------------------------------------------------------------------------------

/** `Window Mode` — move the full-screen app on top (or a named app) into a Huawei window mode. */
class WindowModeAction : Action {
    override val id = "window.mode"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"]?.trim()?.lowercase().orEmpty().ifEmpty { "float" }
        val windowingMode = HUAWEI_MODES[mode]
            ?: return ActionResult.Failure("unknown mode '$mode' — float, split_left or split_right")
        requireShizuku("Window mode")?.let { return it }
        val named = args["package"]?.trim().orEmpty()
        val delayMs = args["delay_ms"]?.trim()?.toLongOrNull()?.coerceIn(0L, 3000L) ?: 300L

        val resolved = withContext(Dispatchers.IO) {
            val pkg: String
            val taskComponent: String?
            if (named.isNotEmpty()) {
                pkg = named
                taskComponent = null
            } else {
                val dump = ShizukuShell.exec("dumpsys activity activities").stdout
                taskComponent = topFullscreenComponent(dump)
                pkg = taskComponent?.substringBefore('/') ?: ""
            }
            if (pkg.isEmpty()) null else launcherComponent(pkg)?.let { it to true } ?: taskComponent?.let { it to false }
        }
        if (resolved == null) {
            RecentAppsSwitcher.toast(
                ctx.app,
                if (named.isEmpty()) "全画面のアプリがありません" else "$named を起動できません",
            )
            return ActionResult.Failure(
                if (named.isEmpty()) "no full-screen app on screen" else "no launchable activity in $named",
            )
        }
        val (component, fromLauncher) = resolved

        // HOME only for the app on top: it has to stop being the top-most activity for the start to
        // take. A NAMED app is the other half of a split being built — the first half is already on
        // screen, and HOME there would tear the split down before the second app reached it (Huawei's
        // own two-app recipe is 100 then 101 back to back, nothing between). Measured 2026-10-11:
        // 雷起動盤 in the empty right half cannot fill it, since its launches open full screen.
        val home = if (named.isEmpty()) {
            "input keyevent KEYCODE_HOME; " + if (delayMs > 0) "sleep ${delayMs / 1000.0}; " else ""
        } else {
            ""
        }
        // Started the way a launcher starts it — MAIN/LAUNCHER with NEW_TASK|RESET_TASK_IF_NEEDED — so
        // a running app's EXISTING task is brought forward into the mode as it stands. A bare `-n` put a
        // fresh copy of the launch activity on top instead: every gesture stacked one more screen
        // (書類管理 grew 5 → 9 activities), and 応用管理's SplashActivity then opened its main screen
        // FULL SCREEN, which took the whole task out of the split and buried the right-half picker
        // (measured 2026-10-13). A task component with no launcher activity keeps the plain start.
        val launcherStyle = if (fromLauncher) {
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 "
        } else {
            ""
        }
        val command = "${home}am start --windowingMode $windowingMode $launcherStyle-n '$component'"
        val result = runCatching { withContext(Dispatchers.IO) { ShizukuShell.exec(command) } }
            .getOrElse { return ActionResult.Failure("Shizuku exec failed: ${it.message}") }
        val out = (result.stdout + "\n" + result.stderr).trim()
        if (result.exitCode != 0 || out.contains("Error", ignoreCase = false)) {
            return ActionResult.Failure("am start failed: ${out.take(200).ifBlank { "exit ${result.exitCode}" }}")
        }
        ctx.variables.set(args["store"]?.trim()?.takeIf { it.isNotEmpty() } ?: "window_app", component.substringBefore('/'))
        ctx.logger("Window mode $mode: $component")
        return ActionResult.Success
    }

    companion object {
        private val HUAWEI_MODES = mapOf(
            "float" to 102,
            "split_left" to 100,
            "split_right" to 101,
        )

        private val TASK_HEADER = Regex("""^\s*\* Task\{\S+ #\d+ (.*)\}\s*$""")
        private val ACTIVITY_COMPONENT = Regex("""^\s*mActivityComponent=(\S+/\S+)\s*$""")

        /**
         * The component of the top-most visible, full-screen, standard (not home, not recents) task in a
         * `dumpsys activity activities` dump. Tasks are listed top of the z-order first, and each task
         * header is followed by its own `mActivityComponent=` line, so the first header that qualifies
         * and the component under it are the app the person is looking at. A floating window over it is
         * skipped (it is not full screen), which is what Huawei Home's gesture does too: it acts on the
         * app behind.
         */
        internal fun topFullscreenComponent(dump: String): String? {
            var armed = false
            for (line in dump.lineSequence()) {
                val header = TASK_HEADER.find(line)
                if (header != null) {
                    val fields = header.groupValues[1]
                    armed = "type=standard" in fields && "visible=true" in fields && " mode=fullscreen" in " $fields"
                    continue
                }
                if (armed) ACTIVITY_COMPONENT.find(line)?.let { return it.groupValues[1] }
            }
            return null
        }

        /** The package's launcher activity — `am start` needs an exported one, and this one always is. */
        private fun launcherComponent(pkg: String): String? =
            ShizukuShell.exec(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER '$pkg'",
            ).stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$pkg/") }
    }
}
