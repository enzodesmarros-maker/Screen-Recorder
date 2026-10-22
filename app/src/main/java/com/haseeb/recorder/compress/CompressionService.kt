package com.haseeb.recorder.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.ui.activity.MainActivity
import com.haseeb.recorder.util.AppLog
import com.haseeb.recorder.util.CompressedVideoStore
import com.haseeb.recorder.util.MainThreadWatchdog
import com.haseeb.recorder.util.VideoCompressor
import java.io.File

/*
 * Foreground service that compresses a recording in the background, showing progress in a notification.
 * One compression runs at a time.
 */
class CompressionService : Service() {

    companion object {
        const val ACTION_START = "com.haseeb.recorder.ACTION_COMPRESS_START"
        const val ACTION_CANCEL = "com.haseeb.recorder.ACTION_COMPRESS_CANCEL"
        const val ACTION_COMPRESSION_DONE = "com.haseeb.recorder.ACTION_COMPRESSION_DONE"
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_SIZE = "extra_size"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_PRESET = "extra_preset"
        private const val CHANNEL_PROGRESS = "compress_progress_channel"
        private const val CHANNEL_DONE = "compress_done_channel"
        private const val NOTIFICATION_ID = 4
        private const val DONE_NOTIFICATION_ID = 5
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60 * 60 * 1000

        @Volatile
        var isRunning: Boolean = false
            private set

        /*
         * Starts compressing the given video in the background.
         */
        fun start(context: Context, uri: Uri, name: String, sizeBytes: Long, durationMs: Long, preset: VideoCompressor.Preset) {
            val intent = Intent(context, CompressionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, sizeBytes)
                putExtra(EXTRA_DURATION, durationMs)
                putExtra(EXTRA_PRESET, preset.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var compressor: VideoCompressor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalName = ""
    private var originalSize = 0L
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelJob()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                startJob(intent)
                return START_NOT_STICKY
            }
        }
        if (!isRunning) stopSelf()
        return START_NOT_STICKY
    }

    private fun startJob(intent: Intent) {
        createChannels()
        enterForeground(buildProgressNotification(-1))

        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        val preset = try {
            VideoCompressor.Preset.valueOf(intent.getStringExtra(EXTRA_PRESET) ?: "")
        } catch (_: Exception) {
            null
        }
        if (uri == null || preset == null) {
            finishJob()
            return
        }

        isRunning = true
        originalName = intent.getStringExtra(EXTRA_NAME) ?: "video.mp4"
        originalSize = intent.getLongExtra(EXTRA_SIZE, 0L)
        val durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)

        acquireWakeLock()
        MainThreadWatchdog.acquire()
        AppLog.i("Compress", "Starting compression: preset=${preset.name} size=${originalSize / 1024} KB duration=${durationMs}ms")

        val c = VideoCompressor(applicationContext)
        compressor = c
        c.start(uri, originalSize, durationMs, preset, object : VideoCompressor.Callback {
            override fun onProgress(percent: Int) {
                if (percent == lastPercent) return
                lastPercent = percent
                if (percent % 10 == 0) AppLog.d("Compress", "Progress $percent%")
                notifyManager(NOTIFICATION_ID, buildProgressNotification(percent))
            }

            override fun onSuccess(output: File) {
                Thread {
                    val newSize = output.length()
                    AppLog.i("Compress", "Compression finished: ${originalSize / 1024} KB -> ${newSize / 1024} KB")
                    if (newSize <= 0L || (originalSize > 0L && newSize >= originalSize)) {
                        output.delete()
                        showDone(getString(R.string.CompressionService_done_title), getString(R.string.VideoAdapter_compress_toast_not_smaller))
                    } else {
                        val saved = CompressedVideoStore.save(applicationContext, originalName, output)
                        output.delete()
                        if (saved) {
                            val oldMb = originalSize / (1024f * 1024f)
                            val newMb = newSize / (1024f * 1024f)
                            val percentSmaller = if (originalSize > 0L) (100f - newSize * 100f / originalSize).toInt() else 0
                            showDone(
                                getString(R.string.CompressionService_done_title),
                                getString(R.string.CompressionService_done_text_fmt, oldMb, newMb, percentSmaller)
                            )
                            sendBroadcast(Intent(ACTION_COMPRESSION_DONE).setPackage(packageName))
                        } else {
                            showDone(getString(R.string.CompressionService_done_title), getString(R.string.VideoAdapter_compress_toast_failed))
                        }
                    }
                    mainHandler.post { finishJob() }
                }.start()
            }

            override fun onError(message: String) {
                AppLog.e("Compress", "Compression failed: $message")
                showDone(getString(R.string.CompressionService_done_title), getString(R.string.VideoAdapter_compress_toast_failed))
                finishJob()
            }
        })
    }

    private fun cancelJob() {
        compressor?.cancel()
        finishJob()
    }

    private fun finishJob() {
        if (isRunning) MainThreadWatchdog.release()
        isRunning = false
        compressor = null
        releaseWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) {
            compressor?.cancel()
            MainThreadWatchdog.release()
        }
        isRunning = false
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
        } catch (_: Exception) {
            // Keep working as a regular service if the system refuses foreground mode.
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenRecorder:compress").apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.CompressionService_channel_progress), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.CompressionService_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /*
     * Builds the ongoing progress notification (percent < 0 means indeterminate).
     */
    private fun buildProgressNotification(percent: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (percent >= 0) "$percent%" else getString(R.string.CompressionService_preparing)
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(getString(R.string.CompressionService_notif_title))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.VideoAdapter_btn_cancel), cancelIntent)
            .build()
    }

    private fun showDone(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        notifyManager(DONE_NOTIFICATION_ID, notification)
    }

    private fun notifyManager(id: Int, notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (_: Exception) {}
    }
}
