package com.haseeb.recorder.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.util.BubbleIconStore
import kotlin.math.abs
import kotlin.math.hypot

/*
 * Small draggable floating bubble shown while no recording is running.
 * Tapping it starts a new recording. Dragging shows an X at the bottom: dropping the bubble there removes it.
 * Size, custom image and edge docking come from the user settings.
 */
class IdleBubbleWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onStartClicked: () -> Unit,
    private val onRemoveRequested: () -> Unit
) : RecordingOverlayWindow {

    companion object {
        @Volatile private var lastX = Int.MIN_VALUE
        @Volatile private var lastY = Int.MIN_VALUE
    }

    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var removeTarget: ImageView? = null
    private var sizePx = 0
    private var dock = ConfigManager.BUBBLE_DOCK_FREE

    private val windowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /*
     * Creates the bubble view and attaches it to the WindowManager.
     */
    override fun show() {
        if (bubble != null) return
        val config = ConfigManager(context)
        val density = context.resources.displayMetrics.density
        sizePx = (config.bubbleSizeDp * density).toInt()
        dock = config.bubbleDock

        val customIcon = BubbleIconStore.load(context)
        val view = ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6D32F2F.toInt())
            }
            if (customIcon != null) {
                setImageBitmap(customIcon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            } else {
                setImageResource(R.drawable.ic_screen_record)
                setColorFilter(Color.WHITE)
                val pad = (sizePx * 0.25f).toInt()
                setPadding(pad, pad, pad, pad)
            }
            elevation = 6 * density
            alpha = 0.92f
            contentDescription = context.getString(R.string.overlay_bubble_start)
            setOnClickListener { onStartClicked() }
        }

        val (screenW, screenH) = screenSize()
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
        lp.x = snapX(lp.x, screenW)
        lp.y = lp.y.coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
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
     * Removes the bubble (and the X target, if visible) from the screen.
     */
    override fun dismiss() {
        hideRemoveTarget(animated = false)
        bubble?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubble = null
        params = null
    }

    override fun onRecordingStateChanged() {}

    override fun onConfigurationChanged() {}

    /*
     * Applies the edge docking choice to a horizontal position.
     */
    private fun snapX(x: Int, screenW: Int): Int {
        val maxX = (screenW - sizePx).coerceAtLeast(0)
        return when (dock) {
            ConfigManager.BUBBLE_DOCK_LEFT -> 0
            ConfigManager.BUBBLE_DOCK_RIGHT -> maxX
            else -> x.coerceIn(0, maxX)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

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
        var overTarget = false
        var screenW = 0
        var screenH = 0

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    overTarget = false
                    val size = screenSize()
                    screenW = size.first
                    screenH = size.second
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        showRemoveTarget()
                    }
                    if (moved) {
                        val nowOver = isOverTarget(event.rawX, event.rawY)
                        if (nowOver != overTarget) {
                            overTarget = nowOver
                            setTargetHighlighted(nowOver)
                            if (nowOver) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        val center = if (overTarget) targetCenter() else null
                        if (center != null) {
                            lp.x = center.first - sizePx / 2
                            lp.y = center.second - sizePx / 2
                        } else {
                            lp.x = (startX + dx).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
                            lp.y = (startY + dy).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
                        }
                        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                    } else if (overTarget) {
                        hideRemoveTarget(animated = false)
                        v.post { onRemoveRequested() }
                    } else {
                        hideRemoveTarget(animated = true)
                        lp.x = snapX(lp.x, screenW)
                        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                        lastX = lp.x
                        lastY = lp.y
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideRemoveTarget(animated = true)
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Shows the round X at the bottom center of the screen while the bubble is being dragged.
     */
    private fun showRemoveTarget() {
        if (removeTarget != null) return
        val density = context.resources.displayMetrics.density
        val size = (64 * density).toInt()
        val target = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC212121.toInt())
                setStroke((2 * density).toInt(), 0x66FFFFFF)
            }
            alpha = 0f
            contentDescription = context.getString(R.string.overlay_bubble_remove)
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (56 * density).toInt()
        }
        try {
            windowManager.addView(target, lp)
            removeTarget = target
            target.animate().alpha(1f).setDuration(150).start()
        } catch (_: Exception) {
            removeTarget = null
        }
    }

    private fun hideRemoveTarget(animated: Boolean) {
        val target = removeTarget ?: return
        removeTarget = null
        val remove = { try { windowManager.removeView(target) } catch (_: Exception) {} }
        if (animated) {
            target.animate().alpha(0f).setDuration(120).withEndAction { remove() }.start()
        } else {
            target.animate().cancel()
            remove()
        }
    }

    private fun setTargetHighlighted(highlighted: Boolean) {
        val target = removeTarget ?: return
        (target.background as? GradientDrawable)?.setColor(if (highlighted) 0xE6D32F2F.toInt() else 0xCC212121.toInt())
        val scale = if (highlighted) 1.3f else 1f
        target.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }

    private fun targetCenter(): Pair<Int, Int>? {
        val target = removeTarget ?: return null
        if (target.width == 0) return null
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return Pair(location[0] + target.width / 2, location[1] + target.height / 2)
    }

    private fun isOverTarget(rawX: Float, rawY: Float): Boolean {
        val center = targetCenter() ?: return false
        val target = removeTarget ?: return false
        val radius = target.width * 1.1f
        return hypot(rawX - center.first, rawY - center.second) <= radius
    }
}
