package com.xera.ptzcam

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

/**
 * Full-screen transparent touch layer over the video.
 *  - Drag:  camera moves in the drag direction; speed grows with drag distance
 *           (full speed after ~60 dp). Keep the finger down to keep moving.
 *  - Flick: a fast swipe keeps the camera moving briefly after release (momentum).
 *  - Pinch: two fingers apart = zoom in, together = zoom out.
 */
class SwipePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPanTilt: ((Float, Float) -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null
    var invert = false

    private val dp = resources.displayMetrics.density
    private val fullSpeedDist = 60f * dp
    private val deadZone = 5f * dp

    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var tracker: VelocityTracker? = null
    private var fling: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * dp
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA29B6F6.toInt(); strokeWidth = 4f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD29B6F6.toInt(); style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
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
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            if (tracking) endDrag()
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fling?.cancel()
                downX = e.x; downY = e.y; curX = e.x; curY = e.y
                tracking = true
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                tracker?.addMovement(e)
                curX = e.x; curY = e.y
                send(map(curX - downX), -map(curY - downY))
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = (tracker?.xVelocity ?: 0f) / dp   // dp per second
                val vy = (tracker?.yVelocity ?: 0f) / dp
                tracker?.recycle(); tracker = null
                tracking = false
                invalidate()
                startFling(vx, vy)
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
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
        invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!tracking) return
        canvas.drawCircle(downX, downY, fullSpeedDist, ringPaint)
        canvas.drawLine(downX, downY, curX, curY, linePaint)
        canvas.drawCircle(curX, curY, 10f * dp, dotPaint)
    }

    override fun onDetachedFromWindow() {
        fling?.cancel()
        tracker?.recycle(); tracker = null
        super.onDetachedFromWindow()
    }
}
