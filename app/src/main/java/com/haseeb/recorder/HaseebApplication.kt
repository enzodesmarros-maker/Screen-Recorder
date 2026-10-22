package com.haseeb.recorder

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.privileged.PrivilegedAccessManager
import com.haseeb.recorder.util.AppLog
import com.haseeb.recorder.util.MainThreadWatchdog
import com.haseeb.recorder.util.LocaleHelper

/*
 * Application class that runs before any activity.
 * Applies the saved theme mode, language locale, and registers dynamic colors with a precondition.
 */
class HaseebApplication : Application() {

    private lateinit var config: ConfigManager
    private var startedActivities = 0

    /*
     * Wraps application base context with saved locale.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    /*
     * Sets up theme, dynamic color registration, locale, and silent Shizuku manager initialization.
     */
    override fun onCreate() {
        super.onCreate()
        config = ConfigManager(this)
        config.applyDefaults()

        AppLog.init(this)
        installCrashLogger()
        registerThermalLogger()

        AppCompatDelegate.setDefaultNightMode(config.getThemeModeValue())
        LocaleHelper.applyLanguage(config.appLanguage)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ ->
                    config.isDynamicColorsEnabled
                }
                .build()
        )

        // Registered after DynamicColors so the pure-black overlay wins over the dynamic surfaces.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyAmoled(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) applyAmoled(activity)
                AppLog.d("Activity", "${activity.javaClass.simpleName} created")
            }

            override fun onActivityStarted(activity: Activity) {
                AppLog.d("Activity", "${activity.javaClass.simpleName} started")
                if (++startedActivities == 1) MainThreadWatchdog.acquire()
            }

            override fun onActivityResumed(activity: Activity) {
                AppLog.d("Activity", "${activity.javaClass.simpleName} resumed")
            }

            override fun onActivityPaused(activity: Activity) {
                AppLog.d("Activity", "${activity.javaClass.simpleName} paused")
            }

            override fun onActivityStopped(activity: Activity) {
                AppLog.d("Activity", "${activity.javaClass.simpleName} stopped")
                if (startedActivities > 0 && --startedActivities == 0) MainThreadWatchdog.release()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                AppLog.d("Activity", "${activity.javaClass.simpleName} destroyed")
            }
        })

        PrivilegedAccessManager.init(this)
    }

    /*
     * Writes uncaught exceptions to the log (with the full stack) before the app closes.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("CRASH", "Uncaught exception in thread ${thread.name}", throwable)
            AppLog.flushBlocking(1000)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /*
     * Logs device temperature state changes (event based, no polling).
     */
    private fun registerThermalLogger() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.addThermalStatusListener(androidx.core.content.ContextCompat.getMainExecutor(this)) { status ->
                AppLog.w("Thermal", "Thermal status changed to $status")
            }
        } catch (_: Exception) {}
    }

    /*
     * Applies the pure black overlay when AMOLED mode is on and the app is currently in dark theme.
     */
    private fun applyAmoled(activity: Activity) {
        if (!config.isAmoledEnabled) return
        val isNight = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        if (isNight) activity.theme.applyStyle(R.style.ThemeOverlay_App_Amoled, true)
    }
}
