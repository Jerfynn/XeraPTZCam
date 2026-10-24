package com.xera.ptzcam

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Full-screen transparent touch layer over the video. Nothing is drawn here.
 *
 * NORMAL mode
 *  - Drag:  camera moves in the drag direction; speed grows with drag distance.
 *  - Flick: a fast swipe keeps the camera moving briefly after release.
 *  - Pinch: zoom.
 *  - Tap:   [onTap] (show / hide controls).  Double-tap: [onDoubleTap] (tap-to-aim, if enabled).
 *
 * SELECT mode (choosing a tracking target)
 *  - Drag a box or tap on the object → [onSelected] with the box in view pixels.
 *    The camera does not move while selecting.
 */
class SwipePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPanTilt: ((Float, Float) -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDoubleTap: ((Float, Float) -> Unit)? = null
    /** Live selection box while dragging (null when finished / cancelled). */
    var onSelecting: ((RectF?) -> Unit)? = null
    var onSelected: ((RectF) -> Unit)? = null
    var invert = false

    /** Double-tap is only detected when enabled (otherwise single taps react instantly). */
    var doubleTapEnabled = false
        set(v) {
            field = v
            tapDetector.setOnDoubleTapListener(if (v) tapListener else null)
        }

    var selectMode = false
        set(v) {
            field = v
            if (!v) { selecting = false; onSelecting?.invoke(null) }
            endDrag()
        }

    private val dp = resources.displayMetrics.density
    private val fullSpeedDist = 60f * dp
    private val deadZone = 5f * dp
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapBoxSize = 72f * dp

    private var tracking = false
    private var selecting = false
    private var downX = 0f
    private var downY = 0f
    private var tracker: VelocityTracker? = null
    private var fling: ValueAnimator? = null
    private val selRect = RectF()

    private val tapListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!doubleTapEnabled && !selectMode) onTap?.invoke()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (doubleTapEnabled && !selectMode) onTap?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!selectMode) onDoubleTap?.invoke(e.x, e.y)
            return true
        }
    }
    private val tapDetector = GestureDetector(context, tapListener).apply { setOnDoubleTapListener(null) }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (selectMode) return false
                endDrag()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = ((d.scaleFactor - 1f) * 30f).coerceIn(-1f, 1f)
                onZoom?.invoke(if (abs(z) < 0.05f) 0f else z)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                onZoom?.invoke(0f)
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (selectMode) return handleSelect(e)

        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            if (tracking) endDrag()
            return true
        }
        tapDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fling?.cancel()
                downX = e.x; downY = e.y
                tracking = true
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                tracker?.addMovement(e)
                send(map(e.x - downX), -map(e.y - downY))
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = (tracker?.xVelocity ?: 0f) / dp   // dp per second
                val vy = (tracker?.yVelocity ?: 0f) / dp
                tracker?.recycle(); tracker = null
                tracking = false
                val isTap = hypot(e.x - downX, e.y - downY) < touchSlop
                if (isTap) send(0f, 0f) else startFling(vx, vy)
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun handleSelect(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                selecting = true
            }
            MotionEvent.ACTION_MOVE -> if (selecting) {
                selRect.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                onSelecting?.invoke(RectF(selRect))
            }
            MotionEvent.ACTION_UP -> if (selecting) {
                selecting = false
                onSelecting?.invoke(null)
                val dragged = hypot(e.x - downX, e.y - downY) >= touchSlop * 2
                val box = if (dragged) {
                    RectF(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                } else {
                    val h = tapBoxSize / 2
                    RectF(e.x - h, e.y - h, e.x + h, e.y + h)
                }
                onSelected?.invoke(box)
            }
            MotionEvent.ACTION_CANCEL -> {
                selecting = false
                onSelecting?.invoke(null)
            }
        }
        return true
    }

    /** Distance → speed, linear after a small dead zone (snappy). */
    private fun map(d: Float): Float {
        val a = abs(d)
        if (a < deadZone) return 0f
        return sign(d) * ((a - deadZone) / (fullSpeedDist - deadZone)).coerceIn(0f, 1f)
    }

    private fun send(x: Float, y: Float) {
        val s = if (invert) -1f else 1f
        onPanTilt?.invoke(x * s, y * s)
    }

    private fun endDrag() {
        tracking = false
        tracker?.recycle(); tracker = null
        send(0f, 0f)
    }

    /** Fast flick: keep moving for a moment, slowing down. */
    private fun startFling(vx: Float, vy: Float) {
        val speed = hypot(vx, vy)
        if (speed < 500f) {
            send(0f, 0f)
            return
        }
        val fx = (vx / 1200f).coerceIn(-1f, 1f)
        val fy = (-vy / 1200f).coerceIn(-1f, 1f)
        val duration = min(250f + speed / 5f, 900f).toLong()

        fling = ValueAnimator.ofFloat(1f, 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                send(fx * v, fy * v)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = send(0f, 0f)
                override fun onAnimationCancel(animation: Animator) = send(0f, 0f)
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        fling?.cancel()
        tracker?.recycle(); tracker = null
        super.onDetachedFromWindow()
    }
}
