package com.haseeb.recorder.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.haseeb.recorder.R
import kotlin.math.abs

/*
 * Small draggable floating bubble shown while no recording is running.
 * Tapping it starts a new recording, so the app does not need to be reopened.
 */
class IdleBubbleWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onStartClicked: () -> Unit
) : RecordingOverlayWindow {

    companion object {
        @Volatile private var lastX = Int.MIN_VALUE
        @Volatile private var lastY = Int.MIN_VALUE
    }

    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizePx = 0

    /*
     * Creates the bubble view and attaches it to the WindowManager.
     */
    override fun show() {
        if (bubble != null) return
        val density = context.resources.displayMetrics.density
        sizePx = (52 * density).toInt()

        val view = ImageView(context).apply {
            setImageResource(R.drawable.ic_screen_record)
            setColorFilter(Color.WHITE)
            val pad = (13 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6D32F2F.toInt())
            }
            elevation = 6 * density
            alpha = 0.9f
            contentDescription = context.getString(R.string.overlay_bubble_start)
            setOnClickListener { onStartClicked() }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX != Int.MIN_VALUE) lastX else (16 * density).toInt()
            y = if (lastY != Int.MIN_VALUE) lastY else (160 * density).toInt()
        }
        params = lp

        attachDragHandling(view, lp)

        try {
            windowManager.addView(view, lp)
            bubble = view
        } catch (_: Exception) {
            // Overlay permission missing; the permission flow will ask for it when recording starts.
            bubble = null
        }
    }

    /*
     * Removes the bubble from the screen.
     */
    override fun dismiss() {
        bubble?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubble = null
        params = null
    }

    override fun onRecordingStateChanged() {}

    override fun onConfigurationChanged() {}

    /*
     * Lets the user drag the bubble anywhere; a short tap without movement still counts as a click.
     */
    private fun attachDragHandling(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val metrics = context.resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - sizePx).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - sizePx).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                    } else {
                        lastX = lp.x
                        lastY = lp.y
                    }
                    true
                }
                else -> false
            }
        }
    }
}
