package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.apps.TargetSignerMismatchException
import com.opentasker.core.scripting.TermuxCommandBroker
import com.opentasker.core.scripting.TermuxCommandRequest
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Run Shell` (Tasker 123) — run a shell command and capture stdout, stderr and the exit code.
 *
 * Args:
 *   - "command":  what `sh -c` runs
 *   - "mode":     who runs it — `shizuku` (default: the Android shell user, through Shizuku),
 *                 or `root` (through `su`; the root manager must allow this app, and the switch
 *                 "Allow tasks to run commands as root" must be on — [CommandSwitches]). There was
 *                 a third mode, `plain` (this app's own user); it was removed on 2026-11-24 because
 *                 a command as the app could rewrite the app's own gates.
 *   - "timeout":  seconds to wait in `root` mode (default 50, at most 600). A command
 *                 that must outlive the task starts its work detached (`nohup … >/dev/null 2>&1 &`)
 *                 and returns at once; the action never waits on a process meant to run for days.
 *   - "store_stdout" / "store_stderr" / "store_exit": variable names (default stdout, stderr, exit)
 *   - "ignore_exit": succeed even when the exit code is not 0
 *   - "window":   `true` shows the run in a command window over the screen — the command, what it
 *                 prints (as it prints it in `root` mode, at the end through Shizuku)
 *                 and how it ended; the window stays until it is closed
 *   - "quiet":    `true` keeps the output from being flashed; by default what the command printed,
 *                 and its errors, are flashed when it ends. Never flashed when "window" is on:
 *                 the window already shows it (白い熊, 2026-11-24).
 *
 * `root` exists for the 携帯 relay phones (白い熊, 2026-11-24): they are rooted and run no Shizuku,
 * and what a task has to start there — Termux's sshd and an ssh tunnel, as Termux's user — is
 * reachable for nobody but root. An unrooted phone cannot do that from here at all; there the same
 * work goes through `Run Termux Script`, which asks Termux to run it itself.
 */
class ShellRunAction : Action {
    override val id = "shell.run"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return ActionResult.Failure("missing command")
        val mode = args["mode"]?.trim()?.lowercase().orEmpty().ifEmpty { "shizuku" }
        val window = truthy(args["window"])
        if (window) ShellWindow.open(ctx.app, "[$mode] $command")
        fun refuse(message: String): ActionResult {
            if (window) ShellWindow.finish(null, "✗ $message")
            return ActionResult.Failure(message)
        }
        val result = when (mode) {
            "shizuku" -> {
                if (!ShizukuShell.isRunning()) {
                    return refuse("Shizuku is not running — install and start Shizuku, then grant access.")
                }
                if (!ShizukuShell.hasPermission()) {
                    ShizukuShell.requestPermission()
                    return refuse("Requested Shizuku access — grant it in the dialog, then run again.")
                }
                try {
                    withContext(Dispatchers.IO) { ShizukuShell.exec(command) }
                } catch (e: Exception) {
                    return refuse("Shizuku exec failed: ${e.message}")
                }.also { if (window) ShellWindow.print(it.stdout + it.stderr) }
            }
            "root" -> {
                if (!CommandSwitches.rootAllowed(ctx.app)) return refuse(CommandSwitches.ROOT_REFUSED)
                val timeoutSec = args["timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
                val outcome = withContext(Dispatchers.IO) {
                    LocalShell.exec(command, root = true, timeoutSec = timeoutSec, onOutput = if (window) ShellWindow::print else null)
                }
                when (outcome) {
                    is LocalShell.Outcome.Done -> outcome.result
                    is LocalShell.Outcome.Refused -> return refuse(outcome.message)
                }
            }
            "plain" -> return refuse("plain mode was removed — use shizuku (the default) or root")
            else -> return refuse("mode is shizuku or root — not “$mode”")
        }
        if (window) ShellWindow.finish(result.exitCode)
        flashShellOutput(ctx, result.stdout, result.stderr, quiet = window || truthy(args["quiet"]))
        ctx.variables.set(args["store_stdout"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stdout", result.stdout.trim())
        ctx.variables.set(args["store_stderr"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stderr", result.stderr.trim())
        ctx.variables.set(args["store_exit"]?.trim()?.takeIf { it.isNotEmpty() } ?: "exit", result.exitCode.toString())
        ctx.logger("Run Shell ($mode) exit ${result.exitCode}")
        return if (result.exitCode == 0 || truthy(args["ignore_exit"])) {
            ActionResult.Success
        } else {
            ActionResult.Failure("shell exited ${result.exitCode}: ${result.stderr.trim().take(200)}")
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SEC = 50
        const val MAX_TIMEOUT_SEC = 600
    }
}

/** A command run by this process itself: as this app's user, or as root through `su`. */
internal object LocalShell {
    sealed interface Outcome {
        data class Done(val result: ShizukuShell.ShellResult) : Outcome
        data class Refused(val message: String) : Outcome
    }

    /** Output kept per stream; a command that prints more is cut, not failed. */
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    fun exec(command: String, root: Boolean, timeoutSec: Int, onOutput: ((String) -> Unit)? = null): Outcome {
        val argv = if (root) listOf("su", "-c", command) else listOf("sh", "-c", command)
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: Exception) {
            return Outcome.Refused(if (root) "this phone has no root (su): ${e.message}" else "could not start a shell: ${e.message}")
        }
        runCatching { process.outputStream.close() }
        val out = StringBuilder()
        val err = StringBuilder()
        // Both streams are drained while the command runs: a full pipe would stop it for good.
        val readers = listOf(process.inputStream to out, process.errorStream to err).map { (stream, into) ->
            Thread {
                runCatching {
                    stream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            if (into.length < MAX_OUTPUT_CHARS) into.append(buffer, 0, minOf(n, MAX_OUTPUT_CHARS - into.length))
                            onOutput?.invoke(String(buffer, 0, n))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        if (!process.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome.Refused(
                "the command did not end within ${timeoutSec}s and was stopped — work that has to go on is started detached " +
                    "(nohup … >/dev/null 2>&1 &)",
            )
        }
        // A detached child may keep the pipes open; what was printed so far is the answer.
        readers.forEach { it.join(1_500) }
        val exit = process.exitValue()
        val stderr = err.toString()
        if (root && exit != 0 && out.isEmpty() && rootDenied(stderr)) {
            return Outcome.Refused("root is refused to 白い熊 自由作業盤 on this phone — allow it in the root manager (Magisk → Superuser)")
        }
        return Outcome.Done(ShizukuShell.ShellResult(out.toString(), stderr, exit))
    }

    /** What a root manager prints when it says no, as opposed to the command's own failure. */
    internal fun rootDenied(stderr: String): Boolean =
        stderr.contains("Permission denied", ignoreCase = true) || stderr.contains("not allowed", ignoreCase = true) ||
            stderr.contains("access denied", ignoreCase = true)
}

/**
 * `Run Termux Shell` — run a command line inside Termux, as Termux, and capture stdout, stderr and
 * the exit code.
 *
 * The command is whatever would be typed at Termux's prompt: `sshd`, `ssh -N …`, a script anywhere
 * (`~/0/bin/sshd.sh`, `/sdcard/tmp/x.sh`), several commands joined with `;` or `&&`. Termux's own
 * programs are found without a path, because Termux runs the line with its own environment.
 *
 * Args:
 *   - "command":  the command line
 *   - "profile":  `true` reads Termux's `~/.profile` first, so that the `PATH` and whatever else it
 *                 sets apply; without it Termux's default environment is used
 *   - "workdir":  directory to run in (default Termux's home)
 *   - "timeout":  seconds to wait for the answer (default 50, at most 600). Work that has to go on
 *                 is started detached (`nohup … >/dev/null 2>&1 &`): Termux answers only when every
 *                 process holding the output has ended.
 *   - "store_stdout" / "store_stderr" / "store_exit": variable names (default stdout, stderr, exit)
 *   - "ignore_exit": succeed even when the exit code is not 0
 *
 *   - "window":   `true` shows the run: in the same command window `Run Shell` uses, fed while the
 *                 command runs (see [TermuxLiveOutput]); off, it runs unseen
 *   - "fullscreen": with "window", run in a terminal session of Termux instead, brought to the
 *                 front. Our window only displays; a command that asks for input needs this.
 *   - "quiet":    `true` keeps the output from being flashed; by default what the command printed,
 *                 and its errors, are flashed when it ends. Never flashed when "window" is on:
 *                 the window already shows it (白い熊, 2026-11-24).
 *
 * This goes through Termux's own door (its RUN_COMMAND service): only Termux can start a process as
 * Termux on a phone without root. It needs “Run commands in Termux environment” granted to this app
 * and `allow-external-apps = true` in Termux's settings.
 *
 * Beside upstream's `Run Termux Script` on purpose (白い熊, 2026-11-24). That one runs nothing but a
 * file under `~/.termux/tasker` whose hash was approved in Settings — a carry-over from Tasker's
 * plug-in. This one runs any line, exactly as `Run Shell` does for the other three users.
 */
class TermuxShellAction : Action {
    override val id = "shell.termux"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return ActionResult.Failure("missing command")
        if (!CommandSwitches.termuxAllowed(ctx.app)) return ActionResult.Failure(CommandSwitches.TERMUX_REFUSED)
        val status = TermuxScriptBackend.inspect(ctx.app)
        if (!status.isReady) return ActionResult.Failure(status.summary)
        val timeoutSec = args["timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
        val shown = truthy(args["window"])
        val fullscreen = shown && truthy(args["fullscreen"])
        val ownWindow = shown && !fullscreen
        val line = commandLine(command, profile = truthy(args["profile"]))
        // Our window: Termux runs it unseen and the line is wrapped so that what it prints also
        // comes here while it runs. A feed that cannot be opened costs only the live view.
        val live = if (ownWindow) TermuxLiveOutput.open(ShellWindow::print) else null
        if (ownWindow) ShellWindow.open(ctx.app, "[termux] $command")
        fun refuse(message: String): ActionResult {
            if (ownWindow) ShellWindow.finish(null, "✗ $message")
            return ActionResult.Failure(message)
        }
        val request = TermuxCommandRequest(
            executable = TERMUX_SHELL,
            arguments = listOf("-c", live?.wrap(line) ?: line),
            workingDirectory = args["workdir"]?.trim()?.ifEmpty { null },
            timeoutMs = timeoutSec * 1000L,
            foreground = fullscreen,
        )
        val raw = try {
            TermuxCommandBroker.execute(ctx.app, request)
        } catch (_: TimeoutCancellationException) {
            return refuse(
                "Termux did not answer within ${timeoutSec}s — work that has to go on is started detached " +
                    "(nohup … >/dev/null 2>&1 &)",
            )
        } catch (refused: TargetSignerMismatchException) {
            return refuse(refused.message.orEmpty())
        } catch (_: SecurityException) {
            return refuse("Termux refused: grant “Run commands in Termux environment” to this app")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return refuse("could not reach Termux (${e.javaClass.simpleName}): ${e.message}")
        } finally {
            live?.close()
        }
        // A terminal session hands back everything it showed, its own last line included.
        val result = if (fullscreen) raw.copy(stdout = withoutSessionFooter(raw.stdout)) else raw
        if (!result.termuxSucceeded) {
            // Termux's own refusal, e.g. allow-external-apps switched off: its message says which.
            return refuse(
                "Termux did not run the command" + (result.errorCode?.let { " (error $it)" } ?: "") +
                    (if (result.errorMessage.isNotEmpty()) ": ${result.errorMessage}" else ""),
            )
        }
        ctx.variables.set(args["store_stdout"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stdout", result.stdout.trim())
        ctx.variables.set(args["store_stderr"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stderr", result.stderr.trim())
        ctx.variables.set(args["store_exit"]?.trim()?.takeIf { it.isNotEmpty() } ?: "exit", result.exitCode.toString())
        ctx.logger("Run Termux Shell exit ${result.exitCode}")
        if (ownWindow) {
            // Nothing came over the feed (it could not be opened, or Termux's bash has no /dev/tcp):
            // the window still gets the whole output, at the end.
            if (live == null || !live.sawOutput) ShellWindow.print(result.stdout + result.stderr)
            ShellWindow.finish(result.exitCode)
        }
        flashShellOutput(ctx, result.stdout, result.stderr, quiet = shown || truthy(args["quiet"]))
        return if (result.exitCode == 0 || truthy(args["ignore_exit"])) {
            ActionResult.Success
        } else {
            ActionResult.Failure("command exited ${result.exitCode}: ${result.stderr.trim().take(200)}")
        }
    }

    internal companion object {
        const val DEFAULT_TIMEOUT_SEC = 50
        const val MAX_TIMEOUT_SEC = 600

        /** Termux's own shell; it and everything it starts get Termux's environment from Termux. */
        const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/bash"

        /**
         * What the shell is given. With [profile], `~/.profile` is read first — quietly when it does
         * not exist, and without letting a failing line in it stop the command.
         */
        /** Termux's "[Process completed … press Enter]" line, and the blank line before it. */
        private val SESSION_FOOTER = Regex("""\s*\[Process completed[^\]\n]*]\s*$""")

        fun withoutSessionFooter(stdout: String): String = stdout.replace(SESSION_FOOTER, "")

        fun commandLine(command: String, profile: Boolean): String =
            if (profile) "if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n$command" else command
    }
}

/**
 * What a command prints inside Termux, brought to this app while the command still runs.
 *
 * Termux answers a RUN_COMMAND only when the command has ended, so a window fed from that answer
 * would stay empty until the end. Instead this app listens on a loopback port for the length of
 * the run, and the command line is wrapped so that bash copies both of its streams there as well
 * (`/dev/tcp` is bash's own; no file is written anywhere). The streams still reach Termux
 * untouched, so the stored output and the exit code are what they would be without the window.
 *
 * The first line that arrives must be a one-time token: any app on the phone can connect to a
 * loopback port, and only the command this run started knows the token.
 */
internal class TermuxLiveOutput private constructor(
    private val server: java.net.ServerSocket,
    private val token: String,
    private val sink: (String) -> Unit,
) {
    @Volatile var sawOutput = false
        private set

    private val reader = Thread {
        runCatching {
            server.soTimeout = CONNECT_TIMEOUT_MS
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                if (input.readLine() != token) return@use
                val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sawOutput = true
                    sink(String(buffer, 0, n))
                }
            }
        }
    }.apply { isDaemon = true; start() }

    /** [line] run so that everything it prints also goes to the feed; its exit code stays its own. */
    fun wrap(line: String): String = wrap(line, server.localPort, token)

    fun close() {
        // A moment for the last lines: Termux's answer can overtake the end of the feed.
        runCatching { reader.join(400) }
        runCatching { server.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        fun open(sink: (String) -> Unit): TermuxLiveOutput? = runCatching {
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            TermuxLiveOutput(server, java.util.UUID.randomUUID().toString(), sink)
        }.getOrNull()

        /**
         * Descriptor 9 is the feed, or `/dev/null` when it cannot be opened, so the command runs
         * either way. Each stream is copied by its own `tee` into a `cat` that writes to the
         * descriptor — `tee /dev/fd/9` cannot be used: a socket cannot be opened by path, and tee's
         * complaint about that ended up in the command's own stderr (seen on the Master, 2026-11-24).
         * The group's exit code is kept by hand because the copies run beside it.
         */
        fun wrap(line: String, port: Int, token: String): String = buildString {
            append("if ! { exec 9>/dev/tcp/127.0.0.1/").append(port).append("; } 2>/dev/null; then exec 9>/dev/null; fi\n")
            append("printf '%s\\n' '").append(token).append("' >&9\n")
            append("{\n").append(line).append("\n} > >(tee >(cat >&9)) 2> >(tee >(cat >&9) >&2)\n")
            append("__jsb_exit=\$?\nexec 9>&-\nexit \$__jsb_exit\n")
        }
    }
}
