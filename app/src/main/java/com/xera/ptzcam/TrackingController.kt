package com.xera.ptzcam

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tan

/**
 * Active tracking. Grabs the displayed frame ~12×/s, finds the selected object with
 * [VisualTracker] and steers the camera so the object stays centred.
 *
 * Why v3.1.6 changed the control (the camera used to overshoot and run away even on a still object):
 *  - The video arrives a few hundred ms late. Steering on "where the object is in the picture" keeps
 *    the camera turning long after the object is already centred, and the old integral term added
 *    even more push → overshoot, object leaves the frame, tracker grabs something else.
 *  - ZT6 (gimbal angle feedback): each frame is converted to the ABSOLUTE angle of the target,
 *    using the gimbal angle at the moment that frame was taken (angle history shifted by the video
 *    delay). The gimbal is then driven to that angle using its live angle reading, which has
 *    almost no delay. A still object gives a fixed target angle → the gimbal settles and stops.
 *  - Cameras without angle feedback: gentle proportional control only, no integral.
 *  - The camera only moves on confident matches. Uncertain / look-alike / jumping matches hold
 *    the camera still and keep the last good position instead of chasing a wrong object.
 *  - Split (visible | thermal side by side) pictures are detected; tracking uses only the half the
 *    target is in and centres it in THAT half (before, it tried to drive it to the seam).
 */
class TrackingController(
    private val scope: CoroutineScope,
    private val ptz: PtzController,
    /** Current video frame scaled to the given width (main thread), or null if unavailable. */
    private val grabFrame: suspend (Int) -> Bitmap?,
    /**
     * How large one screen-width of error is in real angle, relative to the reference view
     * (visible camera at 1×). Used only without angle feedback.
     */
    private val angleScale: () -> Float,
    private val listener: Listener,
    /** Horizontal field of view (degrees, zoom applied) of the thermal (true) or visible (false) image. */
    private val fovDeg: (thermal: Boolean) -> Float = { 60f },
    /** Fresh gimbal angle, or null if this camera has no angle feedback. */
    private val attitude: () -> Att? = { null },
    /** Approximate video delay (ms) – how old a displayed frame is. */
    private val videoDelayMs: () -> Long = { 300L },
) {
    /** Gimbal angle sample: [t] = elapsedRealtime ms, [yaw] + = right, [pitch] + = up (degrees). */
    data class Att(val t: Long, val yaw: Float, val pitch: Float)

    interface Listener {
        /** Box in normalised video coordinates (0..1), or null to clear. */
        fun onTrackBox(box: RectF?, searching: Boolean, score: Float, fps: Float)
        fun onTrackEnded(message: String)
        /** One-off information (e.g. split view detected). */
        fun onTrackInfo(message: String) {}
    }

    /** 0.2 … 1.0 – how aggressively the camera follows (Settings → Tracking response). */
    var gain = 0.6f

    private var job: Job? = null
    val isRunning get() = job?.isActive == true

    /** Gimbal angle history, newest last. */
    private val attHist = ArrayDeque<Att>()

    private fun sampleAttitude() {
        val a = attitude() ?: return
        if (attHist.isEmpty() || attHist.last().t != a.t) attHist.addLast(a)
        while (attHist.size > 60) attHist.removeFirst()
    }

    /** Gimbal angle at time [t] (linear interpolation in the history), or null. */
    private fun attitudeAt(t: Long): Att? {
        if (attHist.isEmpty()) return null
        if (t <= attHist.first().t) return attHist.first()
        if (t >= attHist.last().t) return attHist.last()
        for (i in attHist.size - 1 downTo 1) {
            val a = attHist[i - 1]
            val b = attHist[i]
            if (t >= a.t && t <= b.t) {
                val f = if (b.t > a.t) (t - a.t).toFloat() / (b.t - a.t) else 0f
                return Att(t, a.yaw + (b.yaw - a.yaw) * f, a.pitch + (b.pitch - a.pitch) * f)
            }
        }
        return attHist.last()
    }

    /** Starts tracking the object inside [selection] (normalised 0..1 video coordinates). */
    fun start(selection: RectF) {
        stop()
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            var reason = "Tracking stopped"
            val tracker = VisualTracker()
            attHist.clear()
            try {
                val first = grabFrame(FRAME_WIDTH)
                if (first == null) {
                    reason = "No video frame available – wait for live video"
                    return@launch
                }
                val w = first.width
                val h = first.height
                val argb = IntArray(w * h)
                val luma = FloatArray(w * h)

                // region of the picture the target lives in (whole frame, or one half of a split view)
                var rx0 = 0
                var rx1 = w
                var thermal = false
                var split = false
                val ok = withContext(Dispatchers.Default) {
                    first.getPixels(argb, 0, w, 0, 0, w, h)
                    first.recycle()
                    VisualTracker.luma(argb, luma)
                    split = VisualTracker.isSideBySide(argb, luma, w, h)
                    if (split) {
                        if (selection.centerX() < 0.5f) rx1 = w / 2 else rx0 = w / 2
                    }
                    thermal = VisualTracker.saturation(argb, w, h, rx0, rx1) < 5f
                    tracker.setBounds(rx0, 0, rx1, h)
                    val selL = (selection.left * w).toInt().coerceIn(rx0, rx1 - 1)
                    val selR = (selection.right * w).toInt().coerceIn(selL + 1, rx1)
                    tracker.init(
                        luma, w, h,
                        selL, (selection.top * h).toInt(),
                        selR - selL, (selection.height() * h).toInt(),
                    )
                }
                if (!ok) {
                    reason = "Nothing to lock onto there – select an object with visible detail"
                    return@launch
                }
                if (split) listener.onTrackInfo(
                    "Split view – tracking in the ${if (rx0 == 0) "left" else "right"} (${if (thermal) "thermal" else "visible"}) picture"
                )
                val regionW = (rx1 - rx0).toFloat()
                val regionCx = (rx0 + rx1) / 2f

                var lostSince = 0L
                var weak = 0
                var fps = 0f
                var lastFrameAt = 0L
                var holdPan = false        // hysteresis: centred → stay still until the error grows again
                var holdTilt = false
                // angle mode state
                var tgtYaw = Float.NaN
                var tgtPitch = Float.NaN
                var prevFrameAtt: Att? = null
                // velocity mode state
                var smPan = 0f
                var smTilt = 0f

                while (isActive) {
                    val t0 = SystemClock.elapsedRealtime()
                    if (ptz.manualActive) { reason = "Tracking stopped (manual control)"; break }
                    sampleAttitude()

                    val bmp = grabFrame(FRAME_WIDTH)
                    if (bmp == null) { delay(120); continue }
                    if (bmp.width != w || bmp.height != h) {
                        bmp.recycle()
                        reason = "Tracking stopped – video layout changed"
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    val delayMs = videoDelayMs().coerceIn(0L, 2000L)
                    val frameAtt = attitudeAt(now - delayMs)
                    val angleMode = frameAtt != null && attitude() != null

                    val fov = fovDeg(thermal).coerceIn(0.5f, 170f)
                    val tanH = tan(Math.toRadians(fov / 2.0)).toFloat()
                    val tanV = tanH * h / regionW
                    // expected image motion since the previous frame, from the gimbal's own movement
                    var shiftX = 0f
                    var shiftY = 0f
                    val pa = prevFrameAtt
                    if (angleMode && pa != null) {
                        val pxPerDegX = regionW / fov
                        val pxPerDegY = pxPerDegX                 // square pixels
                        shiftX = -(frameAtt!!.yaw - pa.yaw) * pxPerDegX
                        shiftY = (frameAtt.pitch - pa.pitch) * pxPerDegY
                    }
                    prevFrameAtt = frameAtt

                    val searching = lostSince != 0L
                    val radius = if (searching) (regionW / 3).toInt() else max((regionW / 8).toInt(), 14)
                    val box = withContext(Dispatchers.Default) {
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        VisualTracker.luma(argb, luma)
                        tracker.update(luma, radius, shiftX, shiftY)
                    } ?: break

                    // ---- is this match trustworthy?
                    val distinct = box.secondScore < box.score - AMBIGUITY
                    val confident = if (searching)
                        box.score >= REACQUIRE_SCORE && box.anchorScore >= REACQUIRE_ANCHOR && distinct
                    else
                        box.score >= MIN_SCORE && box.anchorScore >= MIN_ANCHOR && (distinct || box.score > 0.85f)

                    if (!confident) {
                        tracker.rollback()
                        weak++
                        // hold still – never chase an uncertain match
                        ptz.autoPan = 0f
                        ptz.autoTilt = 0f
                        smPan = 0f; smTilt = 0f
                        if (weak >= LOST_FRAMES && lostSince == 0L) lostSince = now
                        if (lostSince != 0L && now - lostSince > SEARCH_MS) { reason = "Target lost"; break }
                    } else {
                        weak = 0
                        lostSince = 0L
                        // error of the target from the centre of its picture, -1..1
                        val ex = ((box.cx - regionCx) / (regionW / 2f)).coerceIn(-1f, 1f)
                        val ey = ((box.cy - h / 2f) / (h / 2f)).coerceIn(-1f, 1f)

                        if (angleMode) {
                            // absolute direction of the target when the frame was taken
                            val dYaw = Math.toDegrees(atan(ex * tanH).toDouble()).toFloat()
                            val dPitch = -Math.toDegrees(atan(ey * tanV).toDouble()).toFloat()
                            val mYaw = frameAtt!!.yaw + dYaw
                            val mPitch = frameAtt.pitch + dPitch
                            if (tgtYaw.isNaN()) {
                                tgtYaw = mYaw; tgtPitch = mPitch
                            } else {
                                // While the gimbal is turning fast, a frame's angle is uncertain (the video
                                // delay is only estimated) → trust it less. Settled frames are exact, so
                                // the gimbal moves, settles, corrects – it cannot run away.
                                val older = attitudeAt(now - delayMs - 200)
                                val rate = if (older != null)
                                    max(abs(frameAtt.yaw - older.yaw), abs(frameAtt.pitch - older.pitch)) / 0.2f
                                else 0f
                                val wgt = MEAS_BLEND / (1f + (rate / RATE_TRUST) * (rate / RATE_TRUST))
                                tgtYaw += (mYaw - tgtYaw) * wgt
                                tgtPitch += (mPitch - tgtPitch) * wgt
                            }
                        } else {
                            // no angle feedback: gentle proportional speed control
                            val z = 1f / angleScale().coerceIn(0.02f, 2f)
                            holdPan = hysteresis(holdPan, abs(ex))
                            holdTilt = hysteresis(holdTilt, abs(ey))
                            val k = gain * VEL_GAIN
                            val pan = if (holdPan) 0f else (k * ex / z).coerceIn(-VEL_MAX, VEL_MAX)
                            val tilt = if (holdTilt) 0f else (-k * ey / z).coerceIn(-VEL_MAX, VEL_MAX)
                            smPan = smPan * SMOOTH + pan * (1 - SMOOTH)
                            smTilt = smTilt * SMOOTH + tilt * (1 - SMOOTH)
                            ptz.autoPan = if (holdPan) 0f else smPan
                            ptz.autoTilt = if (holdTilt) 0f else smTilt
                        }
                    }

                    // angle mode: drive to the target angle using the LIVE gimbal angle (no video delay)
                    if (angleMode && !tgtYaw.isNaN() && lostSince == 0L && weak == 0) {
                        val live = attitude() ?: frameAtt!!
                        val eYaw = tgtYaw - live.yaw
                        val ePitch = tgtPitch - live.pitch
                        val dead = max(0.25f, fov * DEAD_FRAC)
                        holdPan = hysteresisDeg(holdPan, abs(eYaw), dead)
                        holdTilt = hysteresisDeg(holdTilt, abs(ePitch), dead)
                        ptz.autoPan = if (holdPan) 0f else degToSpeed(eYaw)
                        ptz.autoTilt = if (holdTilt) 0f else degToSpeed(ePitch)
                    }

                    if (lastFrameAt != 0L) {
                        val inst = 1000f / max(1L, now - lastFrameAt)
                        fps = if (fps == 0f) inst else fps * 0.8f + inst * 0.2f
                    }
                    lastFrameAt = now
                    val b = tracker.box ?: box
                    listener.onTrackBox(
                        RectF(b.x / w, b.y / h, (b.x + b.w) / w, (b.y + b.h) / h),
                        lostSince != 0L || weak > 0, box.score, fps,
                    )

                    // between frames keep the angle loop running on fresh gimbal angles
                    var spent = SystemClock.elapsedRealtime() - t0
                    while (angleMode && spent < FRAME_MS - 20 && isActive) {
                        delay(20)
                        sampleAttitude()
                        val live = attitude()
                        if (live != null && !tgtYaw.isNaN() && lostSince == 0L && weak == 0) {
                            val dead = max(0.25f, fov * DEAD_FRAC)
                            val eYaw = tgtYaw - live.yaw
                            val ePitch = tgtPitch - live.pitch
                            holdPan = hysteresisDeg(holdPan, abs(eYaw), dead)
                            holdTilt = hysteresisDeg(holdTilt, abs(ePitch), dead)
                            ptz.autoPan = if (holdPan) 0f else degToSpeed(eYaw)
                            ptz.autoTilt = if (holdTilt) 0f else degToSpeed(ePitch)
                        }
                        spent = SystemClock.elapsedRealtime() - t0
                    }
                    delay(max(0L, FRAME_MS - spent))
                }
            } finally {
                ptz.autoPan = 0f
                ptz.autoTilt = 0f
                if (job === me) {
                    job = null
                    listener.onTrackBox(null, false, 0f, 0f)
                    listener.onTrackEnded(reason)
                }
            }
        }
        job = run
        run.start()
    }

    /** Degrees of error → gimbal speed (-1..1), proportional with a small minimum to beat friction. */
    private fun degToSpeed(errDeg: Float): Float {
        val v = (errDeg * gain * DEG_GAIN).coerceIn(-ANGLE_MAX, ANGLE_MAX)
        return if (abs(v) < MIN_SPEED) MIN_SPEED * sign(errDeg) else v
    }

    /** Normalised error hysteresis: stop inside [DEAD_IN], start again only beyond [DEAD_OUT]. */
    private fun hysteresis(holding: Boolean, err: Float) =
        if (holding) err < DEAD_OUT else err < DEAD_IN

    private fun hysteresisDeg(holding: Boolean, err: Float, dead: Float) =
        if (holding) err < dead * 1.8f else err < dead

    /** Stops tracking without a callback (the caller updates its own UI). */
    fun stop() {
        job?.cancel()
        job = null
        ptz.autoPan = 0f
        ptz.autoTilt = 0f
    }

    companion object {
        private const val FRAME_WIDTH = 320
        private const val FRAME_MS = 80L          // ≈ 12 frames per second

        // match acceptance
        private const val MIN_SCORE = 0.55f       // template match needed to steer
        private const val MIN_ANCHOR = 0.40f      // …and still resembling the original selection
        private const val REACQUIRE_SCORE = 0.70f // stricter when picking the target up again
        private const val REACQUIRE_ANCHOR = 0.55f
        private const val AMBIGUITY = 0.06f       // a look-alike this close → don't trust the match
        private const val LOST_FRAMES = 4
        private const val SEARCH_MS = 4000L

        // angle mode (ZT6)
        private const val MEAS_BLEND = 0.5f       // how fast the target angle follows new measurements
        private const val DEG_GAIN = 0.035f       // speed per degree of error at gain 1
        private const val ANGLE_MAX = 0.6f
        private const val MIN_SPEED = 0.04f
        private const val DEAD_FRAC = 0.025f      // stop when within 2.5 % of the field of view
        private const val RATE_TRUST = 8f         // deg/s gimbal rate at which a frame counts half

        // velocity mode (no angle feedback)
        private const val VEL_GAIN = 0.45f
        private const val VEL_MAX = 0.45f
        private const val DEAD_IN = 0.07f
        private const val DEAD_OUT = 0.16f
        private const val SMOOTH = 0.6f
    }
}
