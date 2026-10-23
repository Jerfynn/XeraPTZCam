package com.xera.ptzcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** On-screen joystick. Reports x (right +) and y (up +) in -1..1. */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000; style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF; strokeWidth = 2f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDE53935.toInt(); style = Paint.Style.FILL
    }

    private var kx = 0f
    private var ky = 0f

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val radius get() = min(width, height) / 2f * 0.92f
    private val knobRadius get() = radius * 0.34f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)
        val travel = radius - knobRadius
        canvas.drawCircle(cx + kx * travel, cy + ky * travel, knobRadius, knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val travel = radius - knobRadius
                var dx = (event.x - cx) / travel
                var dy = (event.y - cy) / travel
                val mag = hypot(dx, dy)
                if (mag > 1f) { dx /= mag; dy /= mag }
                kx = dx; ky = dy
                onMove?.invoke(kx, -ky)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f; ky = 0f
                onMove?.invoke(0f, 0f)
                invalidate()
            }
        }
        return true
    }
}
