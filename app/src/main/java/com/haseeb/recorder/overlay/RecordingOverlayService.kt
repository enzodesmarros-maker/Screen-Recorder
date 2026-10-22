package com.haseeb.recorder.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.recording.RecordingStateManager
import com.haseeb.recorder.ui.activity.MainActivity
import com.haseeb.recorder.ui.activity.TileRecordingPermissionActivity
import com.haseeb.recorder.util.AppLog
import com.haseeb.recorder.util.MainThreadWatchdog

/*
 * Main overlay controller service orchestrating recording session state and decoupled floating window UIs.
 * Stays alive while the floating button is enabled: it shows an idle bubble (tap to record) when no
 * recording is running and the full recording controls while recording.
 */
class RecordingOverlayService : Service(), RecordingOverlayController, RecordingStateManager.StateListener {

    companion object {
        const val ACTION_OVERLAY_CONFIG_CHANGED = "com.haseeb.recorder.ACTION_OVERLAY_CONFIG_CHANGED"
        const val ACTION_CLOSE_BUBBLE = "com.haseeb.recorder.ACTION_CLOSE_BUBBLE"
        private const val CHANNEL_ID = "overlay_bubble_channel"
        private const val NOTIFICATION_ID = 3
        private const val WINDOW_NONE = 0
        private const val WINDOW_IDLE = 1
        private const val WINDOW_RECORDING = 2

        /* True while the service (and so the floating bubble / controls) is alive. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private lateinit var configManager: ConfigManager
    private var currentWindow: RecordingOverlayWindow? = null
    private var currentKind: Int = WINDOW_NONE
    private var currentStyle: String = ConfigManager.OVERLAY_STYLE_HORIZONTAL

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OVERLAY_CONFIG_CHANGED) {
                handleOverlayConfigChange()
            }
        }
    }

    /*
     * Returns null since the overlay operates as an unbound foreground overlay service.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes configuration, becomes a foreground service, registers listeners and shows the right window.
     */
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppLog.i("Overlay", "Overlay service created (pinned=${ConfigManager(this).isBubblePinned})")
        MainThreadWatchdog.acquire()
        configManager = ConfigManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        promoteToForeground()

        RecordingStateManager.addListener(this)

        val filter = IntentFilter(ACTION_OVERLAY_CONFIG_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(configReceiver, filter)
        }

        syncWindowWithState()

        // If started for a recording that never begins (and the bubble is not pinned), stop instead of idling.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!RecordingStateManager.isRecording && !configManager.isBubblePinned && currentKind == WINDOW_NONE) {
                stopSelf()
            }
        }, 15_000L)
    }

    /*
     * Handles the close action from the notification and keeps the window in sync on repeated starts.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_BUBBLE) {
            configManager.isBubblePinned = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!configManager.isRecordingOverlayEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        syncWindowWithState()
        return START_STICKY
    }

    /*
     * Callback from RecordingStateManager when any recording property changes.
     */
    override fun onStateChanged() {
        syncWindowWithState()
        currentWindow?.onRecordingStateChanged()
    }

    /*
     * Shows the idle bubble when not recording and the recording controls while recording.
     */
    private fun syncWindowWithState() {
        if (RecordingStateManager.isRecording) {
            if (currentKind != WINDOW_RECORDING) {
                currentStyle = resolveRecordingStyle()
                attachOverlayWindow(currentStyle)
            }
        } else if (configManager.isBubblePinned) {
            if (currentKind != WINDOW_IDLE) attachIdleBubble()
        } else if (currentKind == WINDOW_RECORDING) {
            // Recording ended and the user never pinned the bubble: this service was only for recording.
            stopSelf()
        }
        // Otherwise: started for a recording that has not begun yet; wait for it.
    }

    /*
     * Picks radial or horizontal style based on user settings and the number of enabled controls.
     */
    private fun resolveRecordingStyle(): String {
        val isRadial = (configManager.overlayStyle == ConfigManager.OVERLAY_STYLE_RADIAL) && (getActiveSubOptionsCount() >= 3)
        return if (isRadial) ConfigManager.OVERLAY_STYLE_RADIAL else ConfigManager.OVERLAY_STYLE_HORIZONTAL
    }

    /*
     * Instantiates and presents the requested floating overlay UI window implementation.
     */
    private fun attachOverlayWindow(style: String) {
        val wm = windowManager ?: return
        AppLog.d("Overlay", "Showing recording controls (style=$style)")
        currentWindow?.dismiss()
        val window: RecordingOverlayWindow = if (style == ConfigManager.OVERLAY_STYLE_RADIAL) {
            RadialOverlayWindow(this, wm, this)
        } else {
            HorizontalOverlayWindow(this, wm, this)
        }
        currentWindow = window
        currentKind = WINDOW_RECORDING
        window.show()
    }

    /*
     * Shows the small bubble used to start a new recording without opening the app.
     */
    private fun attachIdleBubble() {
        val wm = windowManager ?: return
        currentWindow?.dismiss()
        AppLog.d("Overlay", "Showing idle bubble")
        val window = IdleBubbleWindow(
            this, wm,
            onStartClicked = { startRecordingFromBubble() },
            onRemoveRequested = { removeBubbleForGood() }
        )
        currentWindow = window
        currentKind = WINDOW_IDLE
        window.show()
    }

    /*
     * Opens the permission/countdown flow that starts a new recording.
     */
    private fun startRecordingFromBubble() {
        try {
            val intent = Intent(this, TileRecordingPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    /*
     * Updates overlay style or control visibility dynamically without restarting recording.
     */
    private fun handleOverlayConfigChange() {
        AppLog.d("Overlay", "Overlay config changed")
        if (!configManager.isRecordingOverlayEnabled) {
            stopSelf()
            return
        }

        if (currentKind == WINDOW_RECORDING) {
            val targetStyle = resolveRecordingStyle()
            if (targetStyle != currentStyle) {
                currentStyle = targetStyle
                attachOverlayWindow(currentStyle)
            } else {
                currentWindow?.onConfigurationChanged()
            }
        } else if (currentKind == WINDOW_IDLE) {
            // Size, image or docking may have changed: rebuild the bubble.
            attachIdleBubble()
        } else {
            syncWindowWithState()
        }
    }

    /*
     * Dismisses the bubble completely (dragged onto the X): stops the service and unpins it.
     */
    private fun removeBubbleForGood() {
        AppLog.i("Overlay", "Bubble removed with the X")
        configManager.isBubblePinned = false
        stopSelf()
    }

    /*
     * Keeps the idle bubble inside the screen after a rotation.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentKind == WINDOW_IDLE) attachIdleBubble()
    }

    /*
     * Runs as a foreground service so Android keeps the bubble alive when the app is closed.
     */
    private fun promoteToForeground() {
        try {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Not allowed to become foreground from the background; keep running as a regular service.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_bubble_channel),
            NotificationManager.IMPORTANCE_MIN
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val closeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingOverlayService::class.java).setAction(ACTION_CLOSE_BUBBLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(getString(R.string.overlay_bubble_notif_title))
            .setContentText(getString(R.string.overlay_bubble_notif_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.overlay_bubble_notif_close), closeIntent)
            .build()
    }

    /*
     * Calculates the count of active action button options configured by the user.
     */
    private fun getActiveSubOptionsCount(): Int {
        var count = 0
        if (configManager.isOverlayShowPauseEnabled) count++
        if (configManager.isOverlayShowStopEnabled) count++
        if (configManager.isOverlayShowMuteEnabled) count++
        if (configManager.isOverlayShowCameraEnabled) count++
        if (configManager.isOverlayShowDrawEnabled) count++
        return count
    }

    /*
     * Retrieves the base timestamp in milliseconds used for chronometer synchronization.
     */
    override fun getChronometerBase(): Long = RecordingStateManager.getChronometerBase()

    /*
     * Returns whether the active recording session is currently paused.
     */
    override fun isPaused(): Boolean = RecordingStateManager.isPaused

    /*
     * Returns whether microphone audio recording is currently muted.
     */
    override fun isMuted(): Boolean = RecordingStateManager.isMuted

    /*
     * Returns whether floating camera preview overlay is currently active.
     */
    override fun isCameraActive(): Boolean = RecordingStateManager.isCameraActive

    /*
     * Returns whether screen annotation drawing overlay is currently active.
     */
    override fun isDrawActive(): Boolean = RecordingStateManager.isDrawActive

    /*
     * Returns whether the pause control button should be displayed in the overlay.
     */
    override fun isShowPauseEnabled(): Boolean = configManager.isOverlayShowPauseEnabled

    /*
     * Returns whether the stop control button should be displayed in the overlay.
     */
    override fun isShowStopEnabled(): Boolean = configManager.isOverlayShowStopEnabled

    /*
     * Returns whether the microphone mute control button should be displayed in the overlay.
     */
    override fun isShowMuteEnabled(): Boolean = configManager.isOverlayShowMuteEnabled

    /*
     * Returns whether the camera preview toggle button should be displayed in the overlay.
     */
    override fun isShowCameraEnabled(): Boolean = configManager.isOverlayShowCameraEnabled

    /*
     * Returns whether the screen draw toggle button should be displayed in the overlay.
     */
    override fun isShowDrawEnabled(): Boolean = configManager.isOverlayShowDrawEnabled

    /*
     * Invoked when the pause or resume control action is triggered by the user.
     */
    override fun onPauseClicked() {
        RecordingStateManager.togglePause(this)
    }

    /*
     * Invoked when the microphone mute or unmute control action is triggered by the user.
     */
    override fun onMuteClicked() {
        RecordingStateManager.toggleMute(this)
    }

    /*
     * Invoked when the camera preview toggle control action is triggered by the user.
     */
    override fun onCameraClicked() {
        RecordingStateManager.toggleCamera(this)
    }

    /*
     * Invoked when the screen annotation draw tool action is triggered by the user.
     */
    override fun onDrawClicked() {
        RecordingStateManager.toggleDraw(this)
    }

    /*
     * Invoked when the stop recording action is triggered by the user.
     */
    override fun onStopClicked() {
        RecordingStateManager.stopRecording(this)
    }

    /*
     * Cleans up receivers, listeners, and dismisses active floating window upon destruction.
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        AppLog.i("Overlay", "Overlay service destroyed")
        MainThreadWatchdog.release()
        RecordingStateManager.removeListener(this)

        try {
            unregisterReceiver(configReceiver)
        } catch (_: Exception) {}

        currentWindow?.dismiss()
        currentWindow = null
        windowManager = null
    }
}
