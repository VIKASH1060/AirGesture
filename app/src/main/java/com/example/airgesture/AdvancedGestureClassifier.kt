package com.example.airgesture

import kotlin.math.abs
import kotlin.math.hypot

data class P(val x: Float, val y: Float)
enum class Dir { UP, DOWN, LEFT, RIGHT }

sealed class GestureAction {
    data class MoveCursor(val nx: Float, val ny: Float) : GestureAction()
    data class Swipe(val dir: Dir) : GestureAction()
    object Tap : GestureAction()
    object Play : GestureAction()
    object Pause : GestureAction()
    object NextTrack : GestureAction()
    object PrevTrack : GestureAction()
    object VolumeUp : GestureAction()
    object VolumeDown : GestureAction()
}

/**
 * Input: 21 MediaPipe landmarks, already upright + mirrored (x grows to the user's right).
 *
 * Gestures
 *  - 1 finger (index)                 -> move cursor
 *  - 2 fingers together (index+middle)-> tap / select at cursor
 *  - 2 fingers apart (V sign) + move  -> swipe
 *  - fist (held)                      -> pause
 *  - open palm (held, still)          -> play
 *  - open palm sweeping left / right  -> previous / next track
 *  - four fingers (thumb tucked) moving up / down -> volume up / down (repeats while you move)
 */
class AdvancedGestureClassifier {

    private enum class Pose { NONE, FIST, OPEN, FOUR, POINT, TWO_TOGETHER, TWO_APART }

    private companion object {
        const val DEBOUNCE_MS = 90L      // pose must be stable this long before it counts
        const val HOLD_MS = 450L         // fist / open palm hold time
        const val TAP_HOLD_MS = 250L
        const val WINDOW_MS = 350L       // sweep detection window
        const val COOLDOWN_MS = 800L
        const val PALM_THR = 0.22f       // fraction of frame width
        const val SWIPE_THR = 0.15f
        const val SMOOTH = 0.35f
        const val VOL_STEP = 0.06f       // hand travel per volume step
        const val VOL_REPEAT_MS = 120L
    }

    private var pose = Pose.NONE
    private var cand = Pose.NONE
    private var candSince = 0L
    private var poseSince = 0L
    private var latched = false          // fire-once-per-pose guard
    private var armed = true             // blocks the "return stroke" after a sweep
    private var lastSweep = 0L
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()
    private var sx = 0.5f
    private var sy = 0.5f
    private var volAnchor = Float.NaN
    private var lastVol = 0L

    private fun reset() {
        pose = Pose.NONE; cand = Pose.NONE; latched = false; armed = true; trail.clear(); volAnchor = Float.NaN
    }

    fun update(lm: List<P>?, now: Long): GestureAction? {
        if (lm == null || lm.size < 21) { reset(); return null }

        val raw = detectPose(lm)
        if (raw != cand) { cand = raw; candSince = now }
        if (cand != pose && now - candSince >= DEBOUNCE_MS) {
            pose = cand; poseSince = now; latched = false; armed = true; trail.clear(); volAnchor = Float.NaN
        }
        val held = now - poseSince

        return when (pose) {
            Pose.POINT -> {
                // use the centre of the frame so you don't have to stretch your arm
                val tx = ((lm[8].x - 0.2f) / 0.6f).coerceIn(0f, 1f)
                val ty = ((lm[8].y - 0.15f) / 0.6f).coerceIn(0f, 1f)
                sx += (tx - sx) * SMOOTH
                sy += (ty - sy) * SMOOTH
                GestureAction.MoveCursor(sx, sy)
            }
            Pose.FIST ->
                if (!latched && held >= HOLD_MS) { latched = true; GestureAction.Pause } else null
            Pose.OPEN -> {
                val dir = sweep(lm[9].x, lm[9].y, now, PALM_THR, horizontalOnly = true)
                when {
                    dir == Dir.RIGHT -> { latched = true; GestureAction.NextTrack }
                    dir == Dir.LEFT -> { latched = true; GestureAction.PrevTrack }
                    !latched && armed && held >= HOLD_MS && isStill() -> { latched = true; GestureAction.Play }
                    else -> null
                }
            }
            Pose.FOUR -> {
                val y = lm[9].y
                if (volAnchor.isNaN()) volAnchor = y
                val delta = y - volAnchor // image y grows downward
                if (now - lastVol >= VOL_REPEAT_MS && abs(delta) >= VOL_STEP) {
                    volAnchor = y
                    lastVol = now
                    if (delta < 0) GestureAction.VolumeUp else GestureAction.VolumeDown
                } else null
            }
            Pose.TWO_TOGETHER ->
                if (!latched && held >= TAP_HOLD_MS) { latched = true; GestureAction.Tap } else null
            Pose.TWO_APART -> {
                val mx = (lm[8].x + lm[12].x) / 2f
                val my = (lm[8].y + lm[12].y) / 2f
                sweep(mx, my, now, SWIPE_THR, horizontalOnly = false)?.let { GestureAction.Swipe(it) }
            }
            Pose.NONE -> null
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun d(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    // finger is extended when its tip is clearly farther from the wrist than its PIP joint
    private fun ext(lm: List<P>, tip: Int, pip: Int) = d(lm[tip], lm[0]) > d(lm[pip], lm[0]) * 1.1f

    private fun detectPose(lm: List<P>): Pose {
        val i = ext(lm, 8, 6)
        val m = ext(lm, 12, 10)
        val r = ext(lm, 16, 14)
        val p = ext(lm, 20, 18)
        val scale = d(lm[0], lm[9]) // hand size, so distance doesn't matter
        // thumb counts as "out" when its tip is far from the base of the index finger
        val thumbOut = d(lm[4], lm[5]) > 0.6f * scale
        return when {
            i && m && r && p -> if (thumbOut) Pose.OPEN else Pose.FOUR
            !i && !m && !r && !p -> Pose.FIST
            i && !m && !r && !p -> Pose.POINT
            i && m && !r && !p ->
                if (d(lm[8], lm[12]) < 0.45f * scale) Pose.TWO_TOGETHER else Pose.TWO_APART
            else -> Pose.NONE
        }
    }

    private fun isStill(): Boolean {
        val a = trail.firstOrNull() ?: return false
        val b = trail.last()
        return b.first - a.first >= 300 && abs(b.second - a.second) < 0.05f && abs(b.third - a.third) < 0.05f
    }

    /** Returns a direction when the tracked point moved far/fast enough inside the window. */
    private fun sweep(x: Float, y: Float, now: Long, thr: Float, horizontalOnly: Boolean): Dir? {
        trail.addLast(Triple(now, x, y))
        while (trail.size > 1 && now - trail.first().first > WINDOW_MS) trail.removeFirst()
        val a = trail.first()
        val dx = x - a.second
        val dy = y - a.third

        if (!armed) { // after a sweep, wait until the hand settles so the return stroke is ignored
            if (now - a.first >= 250 && abs(dx) < 0.04f && abs(dy) < 0.04f) armed = true
            return null
        }
        if (now - lastSweep < COOLDOWN_MS) return null

        val dir = when {
            abs(dx) > thr && abs(dx) > abs(dy) * 1.5f -> if (dx > 0) Dir.RIGHT else Dir.LEFT
            !horizontalOnly && abs(dy) > thr && abs(dy) > abs(dx) * 1.5f -> if (dy > 0) Dir.DOWN else Dir.UP
            else -> null
        }
        if (dir != null) { armed = false; lastSweep = now; trail.clear() }
        return dir
    }
}
