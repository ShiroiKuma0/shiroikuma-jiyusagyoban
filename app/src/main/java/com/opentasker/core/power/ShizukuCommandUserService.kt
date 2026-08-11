package com.opentasker.core.power

import android.os.Parcel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs only the commands approved by [ShizukuCommandPolicy] in the Shizuku shell/root process.
 *
 * This class is instantiated by Shizuku, not Android's app process. It deliberately has no
 * application fallback and exposes no general-purpose shell method to the host.
 */
class ShizukuCommandUserService : IShizukuCommandService.Stub() {
    override fun execute(actionId: String, argv: Array<out String>, exitCode: IntArray): String {
        if (!ShizukuCommandPolicy.isExact(actionId, argv.toList())) {
            setExitCode(exitCode, EXIT_REJECTED)
            return "Command rejected by the Shizuku service allowlist"
        }
        return runCommand(argv.toList(), exitCode)
    }

    override fun captureScreenshot(actionId: String, path: String): Int {
        val command = ShizukuCommandPolicy.command(actionId, 0)
        if (command == null || command != listOf("screencap", "-p")) return EXIT_REJECTED
        val output = File(path)
        if (!output.isAbsolute || output.parentFile?.isDirectory != true) return EXIT_INVALID_ARGUMENT

        return runCatching {
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            var copyFailure: Throwable? = null
            val outputThread = Thread({
                runCatching {
                    process.inputStream.use { source ->
                        FileOutputStream(output).use { destination -> source.copyTo(destination) }
                    }
                }.onFailure { copyFailure = it }
            }, "opentasker-shizuku-screenshot")
            val errorThread = Thread({ readBounded(process.errorStream) }, "opentasker-shizuku-screenshot-error")
            outputThread.start()
            errorThread.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outputThread.interrupt()
                errorThread.interrupt()
                outputThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                errorThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                EXIT_TIMEOUT
            } else {
                outputThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                errorThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                when {
                    copyFailure != null -> EXIT_EXECUTION_ERROR
                    process.exitValue() != 0 -> process.exitValue()
                    !output.isFile || output.length() == 0L -> EXIT_EXECUTION_ERROR
                    else -> 0
                }
            }
        }.getOrElse { EXIT_EXECUTION_ERROR }
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_TRANSACTION_CODE) {
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runCommand(command: List<String>, exitCode: IntArray): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread({ stdout = readBounded(process.inputStream) }, "opentasker-shizuku-stdout")
            val stderrThread = Thread({ stderr = readBounded(process.errorStream) }, "opentasker-shizuku-stderr")
            stdoutThread.start()
            stderrThread.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                setExitCode(exitCode, EXIT_TIMEOUT)
                "Command timed out"
            } else {
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                val result = process.exitValue()
                setExitCode(exitCode, result)
                if (result == 0) stdout else stderr.ifBlank { stdout }
            }
        }.getOrElse { error ->
            setExitCode(exitCode, EXIT_EXECUTION_ERROR)
            error.message ?: "Shizuku command execution failed"
        }
    }

    private fun readBounded(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = MAX_OUTPUT_BYTES
        while (remaining > 0) {
            val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        stream.close()
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private fun setExitCode(exitCode: IntArray, value: Int) {
        if (exitCode.isNotEmpty()) exitCode[0] = value
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val STREAM_JOIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_OUTPUT_BYTES = 8 * 1024
        const val DESTROY_TRANSACTION_CODE = 16777114
        const val EXIT_REJECTED = 126
        const val EXIT_INVALID_ARGUMENT = 125
        const val EXIT_TIMEOUT = 124
        const val EXIT_EXECUTION_ERROR = 127
    }
}
