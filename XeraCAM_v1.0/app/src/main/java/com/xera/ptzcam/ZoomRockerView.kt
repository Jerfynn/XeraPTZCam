package com.xera.ptzcam

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * Spring-loaded vertical zoom rocker: push the knob up to zoom in, down to zoom out.
 * Speed follows how far the knob is pushed; it snaps back to centre on release.
 */
class ZoomRockerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onChange: ((Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private var value = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB30B111C.toInt() }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF; style = Paint.Style.STROKE; strokeWidth = 1 * dp
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D29B6F6 }
    private val centreLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = 1 * dp }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }
    private val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); textSize = 20 * dp; textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.rajdhani_bold)
    }

    private val knobR get() = width * 0.36f
    private val travel get() = height / 2f - knobR - 26 * dp

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        rect.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp)
        c.drawRoundRect(rect, w / 2, w / 2, track)
        c.drawRoundRect(rect, w / 2, w / 2, trackStroke)

        val ky = cy - value * travel
        if (abs(value) > 0.01f) {
            val inset = w * 0.30f
            rect.set(inset, min(cy, ky), w - inset, maxOf(cy, ky))
            c.drawRoundRect(rect, inset, inset, fillPaint)
        }
        c.drawLine(cx - w * 0.22f, cy, cx + w * 0.22f, cy, centreLine)
        c.drawText("+", cx, 22 * dp, sign)
        c.drawText("−", cx, h - 10 * dp, sign)

        knob.color = if (abs(value) > 0.01f) accent else 0xF2FFFFFF.toInt()
        c.drawCircle(cx, ky, knobR, knob)
        c.drawCircle(cx, ky, knobR + 3 * dp, knobRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    anim?.cancel()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                val t = travel
                var v = if (t > 0) ((height / 2f - e.y) / t).coerceIn(-1f, 1f) else 0f
                if (abs(v) < 0.08f) v = 0f
                if (v != value) {
                    value = v
                    onChange?.invoke(value)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onChange?.invoke(0f)
                anim?.cancel()
                anim = ValueAnimator.ofFloat(value, 0f).apply {
                    duration = 260
                    interpolator = OvershootInterpolator(1.6f)
                    addUpdateListener { value = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
