package com.example.airgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Executes actions system-wide: cursor overlay, taps, swipes, media keys. */
class GestureControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureControlService? = null
        private const val CURSOR_PX = 56
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var curX = 0f
    private var curY = 0f
    private val hideCursor = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
        return super.onUnbind(intent)
    }

    /** Safe to call from any thread. */
    fun handle(a: GestureAction) {
        main.post {
            when (a) {
                is GestureAction.MoveCursor -> moveCursor(a.nx, a.ny)
                GestureAction.Tap -> tap(curX, curY)
                is GestureAction.Swipe -> swipe(a.dir)
                GestureAction.Play -> media(KeyEvent.KEYCODE_MEDIA_PLAY)
                GestureAction.Pause -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
                GestureAction.NextTrack -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
                GestureAction.PrevTrack -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                GestureAction.VolumeUp -> volume(AudioManager.ADJUST_RAISE)
                GestureAction.VolumeDown -> volume(AudioManager.ADJUST_LOWER)
            }
        }
    }

    // ---- cursor ------------------------------------------------------------

    private fun ensureCursor() {
        if (cursor != null) return
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAAFF5252.toInt())
                setStroke(4, Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            CURSOR_PX, CURSOR_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, params)
        cursor = v
        lp = params
    }

    private fun moveCursor(nx: Float, ny: Float) {
        val dm = resources.displayMetrics
        curX = nx * dm.widthPixels
        curY = ny * dm.heightPixels
        ensureCursor()
        lp?.let {
            it.x = (curX - CURSOR_PX / 2).toInt()
            it.y = (curY - CURSOR_PX / 2).toInt()
            wm.updateViewLayout(cursor, it)
        }
        cursor?.visibility = View.VISIBLE
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, 3000)
    }

    // ---- touch injection ---------------------------------------------------

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(),
            null, null
        )
    }

    private fun swipe(dir: Dir) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            Dir.UP -> { p.moveTo(w / 2, h * 0.7f); p.lineTo(w / 2, h * 0.3f) }
            Dir.DOWN -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.7f) }
            Dir.LEFT -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            Dir.RIGHT -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(),
            null, null
        )
    }

    // ---- media -------------------------------------------------------------

    private fun volume(direction: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun media(code: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
