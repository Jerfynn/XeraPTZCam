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
import kotlin.math.max

/**
 * Active tracking by visual servoing: grabs the displayed video frame ~12×/s, follows the
 * selected object with [VisualTracker], and steers the camera so the object stays centred.
 * Works with any camera that accepts pan/tilt speed commands (no position feedback needed).
 *
 * Runs on the main thread except for the image processing (Dispatchers.Default).
 */
class TrackingController(
    private val scope: CoroutineScope,
    private val ptz: PtzController,
    /** Current video frame scaled to the given width (main thread), or null if unavailable. */
    private val grabFrame: suspend (Int) -> Bitmap?,
    /**
     * How large one screen-width of error is in real angle, relative to the reference view
     * (visible camera at 1×). Narrower view (thermal lens, zoom) → smaller → gentler corrections.
     * Example: thermal 32.8° vs visible 84.5° → 0.39; visible at 4× zoom → 0.25.
     */
    private val angleScale: () -> Float,
    private val listener: Listener,
) {
    interface Listener {
        /** Box in normalised video coordinates (0..1), or null to clear. */
        fun onTrackBox(box: RectF?, searching: Boolean, score: Float, fps: Float)
        fun onTrackEnded(message: String)
    }

    /** 0.2 … 1.0 – how aggressively the camera follows (Settings → Tracking response). */
    var gain = 0.6f

    private var job: Job? = null
    val isRunning get() = job?.isActive == true

    /** Starts tracking the object inside [selection] (normalised 0..1 video coordinates). */
    fun start(selection: RectF) {
        stop()
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            var reason = "Tracking stopped"
            val tracker = VisualTracker()
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
                val ok = withContext(Dispatchers.Default) {
                    first.getPixels(argb, 0, w, 0, 0, w, h)
                    first.recycle()
                    VisualTracker.luma(argb, luma)
                    tracker.init(
                        luma, w, h,
                        (selection.left * w).toInt(), (selection.top * h).toInt(),
                        (selection.width() * w).toInt(), (selection.height() * h).toInt(),
                    )
                }
                if (!ok) {
                    reason = "Nothing to lock onto there – select an object with visible detail"
                    return@launch
                }

                var smPan = 0f
                var smTilt = 0f
                var iPan = 0f                      // integral terms remove the steady lag behind a moving target
                var iTilt = 0f
                var weak = 0
                var lostSince = 0L
                var fps = 0f
                var lastFrameAt = 0L
                while (isActive) {
                    val t0 = SystemClock.elapsedRealtime()
                    if (ptz.manualActive) { reason = "Tracking stopped (manual control)"; break }

                    val bmp = grabFrame(FRAME_WIDTH)
                    if (bmp == null) { delay(120); continue }
                    if (bmp.width != w || bmp.height != h) {
                        bmp.recycle()
                        reason = "Tracking stopped – video layout changed"
                        break
                    }
                    val searching = lostSince != 0L
                    val radius = if (searching) w / 4 else max(w / 10, 16)
                    val box = withContext(Dispatchers.Default) {
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        VisualTracker.luma(argb, luma)
                        tracker.update(luma, radius)
                    } ?: break

                    if (box.score < LOST_SCORE) weak++ else weak = 0
                    val now = SystemClock.elapsedRealtime()
                    if (weak >= LOST_FRAMES) {
                        // target lost: hold the camera still and search a wider area for a while
                        if (lostSince == 0L) lostSince = now
                        ptz.autoPan = 0f
                        ptz.autoTilt = 0f
                        smPan = 0f; smTilt = 0f
                        iPan = 0f; iTilt = 0f
                        if (now - lostSince > SEARCH_MS) { reason = "Target lost"; break }
                    } else {
                        lostSince = 0L
                        // error of the target centre from the frame centre, -1..1
                        val ex = (box.cx / w - 0.5f) * 2f
                        val ey = (box.cy / h - 0.5f) * 2f
                        // corrections scale with the real angle the screen covers (v3.1 used only
                        // sqrt(zoom) and ignored the narrower thermal lens → hunting in thermal / zoom)
                        val z = 1f / angleScale().coerceIn(0.02f, 2f)
                        val dt = FRAME_MS / 1000f
                        if (abs(ex) >= DEAD_BAND) iPan = (iPan + ex * dt).coerceIn(-I_LIMIT, I_LIMIT)
                        if (abs(ey) >= DEAD_BAND) iTilt = (iTilt + ey * dt).coerceIn(-I_LIMIT, I_LIMIT)
                        val ki = gain * KI_RATIO
                        // PI control (tuned in a closed-loop simulation with 300 ms video latency)
                        val pan = ((if (abs(ex) < DEAD_BAND) 0f else gain * ex) + ki * iPan) / z
                        val tilt = -((if (abs(ey) < DEAD_BAND) 0f else gain * ey) + ki * iTilt) / z
                        smPan = smPan * SMOOTH + pan.coerceIn(-1f, 1f) * (1 - SMOOTH)
                        smTilt = smTilt * SMOOTH + tilt.coerceIn(-1f, 1f) * (1 - SMOOTH)
                        ptz.autoPan = smPan
                        ptz.autoTilt = smTilt
                    }
                    if (lastFrameAt != 0L) {
                        val inst = 1000f / max(1L, now - lastFrameAt)
                        fps = if (fps == 0f) inst else fps * 0.8f + inst * 0.2f
                    }
                    lastFrameAt = now
                    listener.onTrackBox(
                        RectF(box.x / w, box.y / h, (box.x + box.w) / w, (box.y + box.h) / h),
                        lostSince != 0L, box.score, fps,
                    )
                    val spent = SystemClock.elapsedRealtime() - t0
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
        private const val LOST_SCORE = 0.45f
        private const val LOST_FRAMES = 3
        private const val SEARCH_MS = 3000L
        private const val DEAD_BAND = 0.06f
        private const val SMOOTH = 0.5f
        private const val KI_RATIO = 0.8f          // integral gain relative to the response gain
        private const val I_LIMIT = 1.5f           // anti wind-up
    }
}
