package com.snaketracker.app.logging

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent on-device debug log. Writes to the app's private storage
 * (`<filesDir>/logs/`), so it needs no permission and nothing leaves the
 * device unless the user exports it from Settings.
 *
 * Two files are kept: the current log and one rotated backup, each capped at
 * [MAX_BYTES]. Every call is also mirrored to logcat. Logging never throws.
 */
object FileLogger {

    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "snake_tracker_debug.log"
    private const val BACKUP_NAME = "snake_tracker_debug.1.log"
    internal const val MAX_BYTES = 512 * 1024L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FileLogger").apply { isDaemon = true }
    }

    // SimpleDateFormat is not thread-safe; only the writer thread touches it.
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    /** Call once from Application.onCreate, before any other logging. */
    fun init(context: Context) {
        initForFile(File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME))
        installCrashHandler()
        i("FileLogger", "---- process started (pid=${android.os.Process.myPid()}) ----")
    }

    /** Test seam: point the logger at an explicit file. */
    internal fun initForFile(file: File) {
        file.parentFile?.mkdirs()
        logFile = file
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        runCatching {
            when (level) {
                "E" -> Log.e(tag, msg, t)
                "W" -> Log.w(tag, msg, t)
                "I" -> Log.i(tag, msg)
                else -> Log.d(tag, msg)
            }
        }
        val file = logFile ?: return
        val now = Date()
        executor.execute { append(file, format(now, level, tag, msg, t)) }
    }

    private fun format(now: Date, level: String, tag: String, msg: String, t: Throwable?): String =
        buildString {
            append(timeFormat.format(now)).append(' ').append(level).append('/').append(tag)
            append(": ").append(msg).append('\n')
            if (t != null) append(Log.getStackTraceString(t)).append('\n')
        }

    private fun append(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(file.parentFile, BACKUP_NAME)
                backup.delete()
                file.renameTo(backup)
            }
            file.appendText(text)
        } catch (_: Exception) {
            // Logging must never take the app down.
        }
    }

    /** Logs uncaught exceptions synchronously (the process is about to die), then defers. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logFile?.let { file ->
                append(
                    file,
                    format(Date(), "E", "CRASH", "Uncaught exception on ${thread.name}", throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Full log text, rotated backup first. Blocks until queued writes land. */
    fun readAll(): String {
        flush()
        val file = logFile ?: return ""
        val backup = File(file.parentFile, BACKUP_NAME)
        return buildString {
            if (backup.exists()) append(backup.readText())
            if (file.exists()) append(file.readText())
        }
    }

    fun clear() {
        val file = logFile ?: return
        executor.execute {
            file.delete()
            File(file.parentFile, BACKUP_NAME).delete()
        }
    }

    /**
     * Copies the whole log to a user-chosen [destination] (e.g. from
     * `ActivityResultContracts.CreateDocument`). [onDone] runs on the main thread.
     */
    fun exportTo(context: Context, destination: Uri, onDone: (Boolean) -> Unit = {}) {
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val ok = try {
                val text = readAllOnWriterThread()
                resolver.openOutputStream(destination)?.use { it.write(text.toByteArray()) } != null
            } catch (e: Exception) {
                Log.e("FileLogger", "export failed", e)
                false
            }
            Handler(Looper.getMainLooper()).post { onDone(ok) }
        }
    }

    private fun readAllOnWriterThread(): String {
        val file = logFile ?: return ""
        val backup = File(file.parentFile, BACKUP_NAME)
        return buildString {
            if (backup.exists()) append(backup.readText())
            if (file.exists()) append(file.readText())
        }
    }

    /** Blocks until every queued write has been flushed (test + export helper). */
    internal fun flush() {
        runCatching { executor.submit {}.get() }
    }
}
