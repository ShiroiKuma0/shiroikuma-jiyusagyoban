package com.opentasker.core.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.shizuku.ShizukuShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single in-process voice recorder shared across task invocations — `audio.record.start` and
 * `audio.record.stop` run as separate task executions, so the live [MediaRecorder] must outlive a
 * single run. Records AAC/m4a to a shell-readable app-external temp, then on stop relocates it to the
 * user's configured directory (directly when writable, else via Shizuku so arbitrary paths like
 * `/sdcard/Recordings` work). State is mirrored to [DeviceStateEvents] so a profile can gate on
 * `recording=true` / `recording=false`.
 */
object AudioRecorderManager {
    private const val TAG = "OpenTasker"
    private const val TMP_NAME = "pkey_rec_tmp.m4a"

    private var recorder: MediaRecorder? = null
    private var tmpFile: File? = null
    private var targetDir: String = ""

    val isRecording: Boolean
        @Synchronized get() = recorder != null

    fun hasMicPermission(app: Context): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Begin recording. [dir] is the desired output directory; resolved/relocated on [stop]. */
    @Synchronized
    fun start(app: Context, dir: String): Result<Unit> {
        if (recorder != null) return Result.success(Unit) // already recording → no-op
        if (!hasMicPermission(app)) return Result.failure(IllegalStateException("RECORD_AUDIO permission not granted"))

        val tmp = File(app.getExternalFilesDir(null) ?: app.filesDir, TMP_NAME)
        return try {
            tmp.parentFile?.mkdirs()
            if (tmp.exists()) tmp.delete()
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(tmp.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            tmpFile = tmp
            targetDir = dir.trim()
            DeviceStateEvents.publishRecording(true)
            Log.i(TAG, "Recording started → ${tmp.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            tmpFile = null
            DeviceStateEvents.publishRecording(false)
            Result.failure(e)
        }
    }

    /** Stop recording and finalize. Returns the resolved path of the saved file, or failure. */
    @Synchronized
    fun stop(app: Context): Result<String> {
        val rec = recorder ?: return Result.failure(IllegalStateException("not recording"))
        val tmp = tmpFile
        return try {
            runCatching { rec.stop() }
            rec.release()
            recorder = null
            tmpFile = null
            DeviceStateEvents.publishRecording(false)
            if (tmp == null || !tmp.exists()) return Result.failure(IllegalStateException("no recording file produced"))

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val name = "物理鍵_$stamp.m4a"
            val dir = targetDir.ifBlank { defaultDir(app) }
            val finalPath = relocate(tmp, dir, name, app)
            Log.i(TAG, "Recording saved → $finalPath")
            Result.success(finalPath)
        } catch (e: Exception) {
            recorder = null
            tmpFile = null
            DeviceStateEvents.publishRecording(false)
            Result.failure(e)
        }
    }

    private fun defaultDir(app: Context): String =
        (app.getExternalFilesDir("Recordings") ?: File(app.filesDir, "Recordings")).absolutePath

    /** Move [tmp] into [dir]/[name]; direct rename when writable, else Shizuku cp, else app-external fallback. */
    private fun relocate(tmp: File, dir: String, name: String, app: Context): String {
        // 1) Direct file move (works for app-writable dirs).
        runCatching {
            val target = File(dir)
            if (target.exists() || target.mkdirs()) {
                val dest = File(target, name)
                if (tmp.renameTo(dest) || (tmp.copyTo(dest, overwrite = true).let { tmp.delete(); true })) {
                    return dest.absolutePath
                }
            }
        }
        // 2) Shizuku (uid 2000 can write arbitrary public dirs like /sdcard/Recordings).
        if (ShizukuShell.available()) {
            val destPath = "$dir/$name"
            val res = runCatching {
                ShizukuShell.exec("mkdir -p ${sq(dir)} && cp -f ${sq(tmp.absolutePath)} ${sq(destPath)} && rm -f ${sq(tmp.absolutePath)}")
            }.getOrNull()
            if (res != null && res.exitCode == 0) return destPath
            Log.w(TAG, "Shizuku relocate failed: ${res?.stderr}")
        }
        // 3) Fallback: keep it in the app-external Recordings dir so nothing is lost.
        val fallbackDir = File(defaultDir(app)).apply { mkdirs() }
        val dest = File(fallbackDir, name)
        runCatching { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        return dest.absolutePath
    }

    /** Single-quote a path for `sh -c`, escaping embedded single quotes. */
    private fun sq(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
