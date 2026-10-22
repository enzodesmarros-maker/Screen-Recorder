package com.haseeb.recorder.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.haseeb.recorder.recording.RecordingStateManager
import java.util.concurrent.atomic.AtomicLong

/*
 * Detects when the main thread stops responding and logs what it was doing (its stack trace),
 * plus a memory/thermal snapshot every few seconds. Runs only while the app has something active
 * (a visible screen, a recording, the floating bubble or a compression) and only if logging is on.
 */
object MainThreadWatchdog {

    private const val STALL_THRESHOLD_MS = 400L
    private const val CYCLE_MS = 1000L
    private const val HEARTBEAT_EVERY_CYCLES = 15
    private const val MAX_WAIT_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var users = 0
    private var thread: Thread? = null

    /*
     * Marks that something that should be watched is active.
     */
    fun acquire() {
        synchronized(lock) {
            users++
            startIfNeeded()
        }
    }

    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) stop()
        }
    }

    /*
     * Applies the logging on/off switch to the watcher.
     */
    fun onLoggingToggled() {
        synchronized(lock) {
            if (AppLog.enabled && users > 0) startIfNeeded() else stop()
        }
    }

    private fun startIfNeeded() {
        if (!AppLog.enabled || users == 0 || thread != null) return
        thread = Thread({ loop() }, "MainThreadWatchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var cycles = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val sentAt = SystemClock.uptimeMillis()
                val ackedAt = AtomicLong(0L)
                mainHandler.post { ackedAt.set(SystemClock.uptimeMillis()) }
                Thread.sleep(STALL_THRESHOLD_MS)

                if (ackedAt.get() == 0L) {
                    // The main thread did not answer in time: capture what it is doing right now.
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(14)
                        .joinToString("\n    at ")
                    var waited = STALL_THRESHOLD_MS
                    while (ackedAt.get() == 0L && waited < MAX_WAIT_MS) {
                        Thread.sleep(50)
                        waited += 50
                    }
                    val total = if (ackedAt.get() != 0L) ackedAt.get() - sentAt else waited
                    AppLog.w("Watchdog", "Main thread was blocked for about ${total}ms. It was here:\n    at $stack")
                }

                cycles++
                if (cycles % HEARTBEAT_EVERY_CYCLES == 0) logHeartbeat()
                val elapsed = SystemClock.uptimeMillis() - sentAt
                if (elapsed < CYCLE_MS) Thread.sleep(CYCLE_MS - elapsed)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun logHeartbeat() {
        try {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            var extra = ""
            AppLogContext.get()?.let { ctx ->
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                extra += " | RAM free ${info.availMem / (1024 * 1024)} MB lowMemory=${info.lowMemory}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    extra += " | thermal=${pm.currentThermalStatus}"
                }
            }
            AppLog.d(
                "Heartbeat",
                "heap ${usedMb}/${maxMb} MB$extra | recording=${RecordingStateManager.isRecording}"
            )
        } catch (_: Exception) {}
    }
}

/*
 * Small holder so the watchdog can reach the application context without a hard dependency chain.
 */
object AppLogContext {
    @Volatile
    private var context: Context? = null

    fun set(value: Context) {
        context = value.applicationContext
    }

    fun get(): Context? = context
}
