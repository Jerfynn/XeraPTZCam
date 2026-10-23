package com.xera.ptzcam

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/** Deadzone + curve: fine control near centre, full speed at the edge. */
fun shapeAxis(v: Float, dead: Float = 0.10f): Float {
    if (abs(v) < dead) return 0f
    val s = ((abs(v) - dead) / (1f - dead)).coerceIn(0f, 1f).pow(1.6f)
    return if (v < 0) -s else s
}

/**
 * Merges all inputs (on-screen stick, gamepad, zoom buttons) and sends ONVIF
 * ContinuousMove / Stop at a limited rate. Runs on Dispatchers.IO.
 */
class PtzController(
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    @Volatile var client: PtzMover? = null

    @Volatile var stickPan = 0f
    @Volatile var stickTilt = 0f
    @Volatile var padPan = 0f
    @Volatile var padTilt = 0f
    @Volatile var padZoom = 0f
    @Volatile var buttonZoom = 0f
    @Volatile var swipePan = 0f
    @Volatile var swipeTilt = 0f
    @Volatile var swipeZoom = 0f
    @Volatile var rockerZoom = 0f
    /** Automatic aiming (ZT6 presets) – overridden by any manual input. */
    @Volatile var autoPan = 0f
    @Volatile var autoTilt = 0f
    @Volatile var maxSpeed = 1f

    /** True while pan/tilt commands are being sent (used to detect cameras that never report position). */
    @Volatile var isMoving = false
        private set

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var last = floatArrayOf(0f, 0f, 0f)
            var moving = false
            var lastSend = 0L
            var hadError = false

            while (isActive) {
                val c = client
                if (c == null) {
                    moving = false
                    delay(100)
                    continue
                }

                val pan = q((stickPan + padPan + swipePan + autoPan).coerceIn(-1f, 1f) * maxSpeed)
                val tilt = q((stickTilt + padTilt + swipeTilt + autoTilt).coerceIn(-1f, 1f) * maxSpeed)
                val zoom = q((padZoom + buttonZoom + swipeZoom + rockerZoom).coerceIn(-1f, 1f))
                val now = SystemClock.elapsedRealtime()

                try {
                    if (pan == 0f && tilt == 0f && zoom == 0f) {
                        if (moving) {
                            c.stop()
                            moving = false
                            isMoving = false
                            last = floatArrayOf(0f, 0f, 0f)
                        }
                    } else {
                        val changed = abs(pan - last[0]) > 0.04f ||
                            abs(tilt - last[1]) > 0.04f ||
                            abs(zoom - last[2]) > 0.04f
                        val keepAlive = now - lastSend >= 1000
                        if ((changed || !moving || keepAlive) && now - lastSend >= 60) {
                            c.continuousMove(pan, tilt, zoom)
                            moving = true
                            isMoving = pan != 0f || tilt != 0f
                            last = floatArrayOf(pan, tilt, zoom)
                            lastSend = now
                        }
                    }
                    if (hadError) {
                        hadError = false
                        onStatus("ready")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    hadError = true
                    onStatus(e.message ?: "command failed")
                    delay(500)
                }
                delay(20)
            }
        }
    }

    /** True while the user is steering manually (cancels automatic aiming). */
    val manualActive: Boolean
        get() = stickPan != 0f || stickTilt != 0f || padPan != 0f || padTilt != 0f ||
            swipePan != 0f || swipeTilt != 0f

    /** Stops the loop and the camera. */
    fun halt() {
        job?.cancel()
        job = null
        stickPan = 0f; stickTilt = 0f; padPan = 0f; padTilt = 0f; padZoom = 0f; buttonZoom = 0f
        swipePan = 0f; swipeTilt = 0f; swipeZoom = 0f; rockerZoom = 0f
        autoPan = 0f; autoTilt = 0f
        val c = client ?: return
        scope.launch(Dispatchers.IO) { runCatching { c.stop() } }
    }

    private fun q(v: Float) = round(v * 20f) / 20f
}
