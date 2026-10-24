package com.haseeb.recorder.remux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.recording.RawRecordings
import com.haseeb.recorder.ui.activity.RawVideosActivity
import com.haseeb.recorder.util.AppLog
import com.haseeb.recorder.util.MainThreadWatchdog
import java.io.File

/*
 * Foreground service that converts a staged MKV recording to MP4 in the background,
 * with a progress notification and an optional live progress callback for the open screen.
 */
class RemuxService : Service() {

    companion object {
        const val ACTION_START = "com.haseeb.recorder.ACTION_REMUX_START"
        const val ACTION_CANCEL = "com.haseeb.recorder.ACTION_REMUX_CANCEL"
        const val ACTION_REMUX_DONE = "com.haseeb.recorder.ACTION_REMUX_DONE"
        private const val EXTRA_PATH = "extra_path"
        private const val CHANNEL_PROGRESS = "remux_progress_channel"
        private const val CHANNEL_DONE = "remux_done_channel"
        private const val NOTIFICATION_ID = 6
        private const val DONE_NOTIFICATION_ID = 7
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentName: String? = null
            private set

        /*
         * Live updates for a visible screen. Always called on the main thread.
         * percent = -1 when unknown, finished = true when the job ended (success = false if failed or cancelled).
         */
        var listener: ((percent: Int, etaSeconds: Long, finished: Boolean, success: Boolean) -> Unit)? = null

        fun start(context: Context, file: File) {
            val intent = Intent(context, RemuxService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PATH, file.absolutePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, RemuxService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var remuxer: MkvRemuxer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPercent = -2
    private var finished = false
    private var rawFileName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                AppLog.i("Remux", "Conversion cancelled by the user")
                remuxer?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startJob(File(path))
                return START_NOT_STICKY
            }
        }
        if (!isRunning) stopSelf()
        return START_NOT_STICKY
    }

    private fun startJob(file: File) {
        createChannels()
        enterForeground(buildProgressNotification(file.nameWithoutExtension, -1, -1))

        isRunning = true
        finished = false
        currentName = file.nameWithoutExtension
        rawFileName = file.name
        lastPercent = -2
        acquireWakeLock()
        MainThreadWatchdog.acquire()

        val job = MkvRemuxer(applicationContext)
        remuxer = job
        val name = file.nameWithoutExtension
        val mp4Name = (if (name.startsWith("ScreenRecord_")) name else "ScreenRecord_$name") + ".mp4"

        Thread({
            var savedName: String? = null
            var error: String? = null
            try {
                savedName = job.run(file, mp4Name, object : MkvRemuxer.Callback {
                    override fun onProgress(percent: Int, etaSeconds: Long) {
                        mainHandler.post { handleProgress(name, percent, etaSeconds) }
                    }
                })
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                AppLog.e("Remux", "Conversion failed", e)
            }
            mainHandler.post { finishJob(name, savedName, error) }
        }, "MkvRemuxThread").start()
    }

    private fun handleProgress(name: String, percent: Int, etaSeconds: Long) {
        if (finished) return
        listener?.invoke(percent, etaSeconds, false, false)
        if (percent != lastPercent) {
            lastPercent = percent
            notifyManager(NOTIFICATION_ID, buildProgressNotification(name, percent, etaSeconds))
        }
    }

    private fun finishJob(name: String, savedName: String?, error: String?) {
        if (finished) return
        finished = true
        val success = savedName != null && error == null
        val cancelled = savedName == null && error == null

        if (isRunning) MainThreadWatchdog.release()
        isRunning = false
        currentName = null
        remuxer = null
        releaseWakeLock()

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        listener?.invoke(100, 0, true, success)

        if (success) {
            RawRecordings.markConverted(applicationContext, rawFileName)
            AppLog.i("Remux", "Conversion finished: $savedName")
            showDone(getString(R.string.RemuxService_done_title), getString(R.string.RemuxService_done_text, name))
            sendBroadcast(Intent(ACTION_REMUX_DONE).setPackage(packageName))
        } else if (!cancelled) {
            showDone(getString(R.string.RemuxService_done_title), getString(R.string.RemuxService_failed_text, name))
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) {
            remuxer?.cancel()
            MainThreadWatchdog.release()
        }
        isRunning = false
        currentName = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenCats:remux").apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.RemuxService_channel_progress), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.RemuxService_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openScreenIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, RawVideosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(name: String, percent: Int, etaSeconds: Long): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 3,
            Intent(this, RemuxService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            percent < 0 -> getString(R.string.RemuxService_working)
            etaSeconds >= 0 -> getString(R.string.RemuxService_progress_eta, percent, formatEta(etaSeconds))
            else -> "$percent%"
        }
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(getString(R.string.RemuxService_notif_title, name))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(openScreenIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.VideoAdapter_btn_cancel), cancelIntent)
            .build()
    }

    private fun formatEta(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 1) getString(R.string.RemuxService_eta_minutes, minutes + 1) else getString(R.string.RemuxService_eta_seconds, seconds.coerceAtLeast(1))
    }

    private fun showDone(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreenIntent())
            .setAutoCancel(true)
            .build()
        notifyManager(DONE_NOTIFICATION_ID, notification)
    }

    private fun notifyManager(id: Int, notification: Notification) {
        try { getSystemService(NotificationManager::class.java)?.notify(id, notification) } catch (_: Exception) {}
    }
}
