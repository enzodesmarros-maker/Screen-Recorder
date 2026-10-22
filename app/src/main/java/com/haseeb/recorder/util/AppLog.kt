package com.haseeb.recorder.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.haseeb.recorder.data.ConfigManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * Lightweight app-wide logger. Callers only enqueue a small object (no disk work on their thread);
 * a single low-priority background thread writes to a size-limited file. Disabled by the user = zero work.
 */
object AppLog {

    private const val TAG = "ScreenCats"
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val QUEUE_CAPACITY = 4000
    private const val LOG_FILE = "app.log"
    private const val OLD_LOG_FILE = "app.log.1"
    private const val SHARE_FILE = "ScreenCats_log.txt"

    private class Entry(
        val time: Long,
        val level: Char,
        val tag: String,
        val message: String,
        val thread: String,
        val latch: CountDownLatch? = null
    )

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null
    private var logDir: File? = null
    private val queue = LinkedBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger(0)

    /*
     * Prepares the log folder, reads the user's on/off choice and starts the writer thread.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        AppLogContext.set(app)
        logDir = File(app.filesDir, "logs").apply { mkdirs() }
        enabled = ConfigManager(app).isLoggingEnabled
        Thread({ runWriter() }, "AppLogWriter").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        if (enabled) writeSessionHeader()
    }

    fun d(tag: String, message: String) = log('D', tag, message)
    fun i(tag: String, message: String) = log('I', tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log('W', tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log('E', tag, message, t)

    private fun log(level: Char, tag: String, message: String, t: Throwable? = null) {
        if (!enabled) return
        val full = if (t != null) message + "\n" + Log.getStackTraceString(t) else message
        when (level) {
            'E' -> Log.e(TAG, "$tag: $full")
            'W' -> Log.w(TAG, "$tag: $full")
            else -> Log.d(TAG, "$tag: $full")
        }
        if (!queue.offer(Entry(System.currentTimeMillis(), level, tag, full, Thread.currentThread().name))) {
            dropped.incrementAndGet()
        }
    }

    /*
     * Turns logging on or off and remembers the choice. Existing log files are kept.
     */
    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        if (value == enabled) return
        if (!value) {
            log('I', "AppLog", "Logging turned off by the user")
            flushBlocking(800)
            enabled = false
        } else {
            enabled = true
            writeSessionHeader()
            log('I', "AppLog", "Logging turned on by the user")
        }
        ConfigManager(app).isLoggingEnabled = value
        MainThreadWatchdog.onLoggingToggled()
    }

    /*
     * Waits (up to timeoutMs) until everything queued so far has been written to disk.
     */
    fun flushBlocking(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        if (!queue.offer(Entry(System.currentTimeMillis(), 'F', "", "", "", latch))) return
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
    }

    /*
     * Merges the old and current log into one text file ready to be shared. Call from a background thread.
     */
    fun buildShareFile(context: Context): File? {
        val dir = logDir ?: return null
        flushBlocking(1500)
        val current = File(dir, LOG_FILE)
        val old = File(dir, OLD_LOG_FILE)
        if ((!current.exists() || current.length() == 0L) && (!old.exists() || old.length() == 0L)) return null
        return try {
            val out = File(dir, SHARE_FILE)
            out.delete()
            out.outputStream().use { stream ->
                if (old.exists()) old.inputStream().use { it.copyTo(stream) }
                if (current.exists()) current.inputStream().use { it.copyTo(stream) }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSessionHeader() {
        val app = appContext ?: return
        try {
            val pm = app.packageManager
            val versionName = try {
                pm.getPackageInfo(app.packageName, 0).versionName
            } catch (_: PackageManager.NameNotFoundException) {
                "?"
            }
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val metrics = app.resources.displayMetrics
            log(
                'I', "Session",
                "App $versionName | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
                    "screen ${metrics.widthPixels}x${metrics.heightPixels} @${metrics.densityDpi}dpi | " +
                    "RAM ${memInfo.totalMem / (1024 * 1024)} MB | lowRamDevice=${am.isLowRamDevice}"
            )
        } catch (_: Exception) {}
    }

    private fun runWriter() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {}
        val dir = logDir ?: return
        val file = File(dir, LOG_FILE)
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val batch = ArrayList<Entry>(256)
        var writer: BufferedWriter? = null
        var written = if (file.exists()) file.length() else 0L

        while (true) {
            try {
                batch.clear()
                batch.add(queue.take())
                queue.drainTo(batch, 255)

                if (writer == null) writer = BufferedWriter(FileWriter(file, true), 16 * 1024)

                val lost = dropped.getAndSet(0)
                if (lost > 0) {
                    val line = "${formatter.format(System.currentTimeMillis())} W/AppLog: $lost log lines were dropped (queue full)\n"
                    writer.write(line)
                    written += line.length
                }
                for (entry in batch) {
                    if (entry.level == 'F') continue
                    val line = "${formatter.format(entry.time)} ${entry.level}/${entry.tag} [${entry.thread}]: ${entry.message}\n"
                    writer.write(line)
                    written += line.length
                }
                writer.flush()

                if (written > MAX_FILE_BYTES) {
                    writer.close()
                    writer = null
                    val old = File(dir, OLD_LOG_FILE)
                    old.delete()
                    file.renameTo(old)
                    written = 0L
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try { writer?.close() } catch (_: Exception) {}
                writer = null
            } finally {
                for (entry in batch) entry.latch?.countDown()
            }
        }
    }
}
