package com.xera.ptzcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Compass-style preset dial: FRONT top, RIGHT, REAR bottom, LEFT; UP / DOWN in the centre.
 * Tap a segment = go, long-press = save. Touches outside the dial are not consumed.
 */
class PresetDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onGo: ((String) -> Unit)? = null
    var onSave: ((String) -> Unit)? = null

    var saved: Set<String> = emptySet()
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private var pressed: String? = null

    private val sectors = listOf("FRONT" to -90f, "RIGHT" to 0f, "REAR" to 90f, "LEFT" to 180f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = 0x40FFFFFF
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15 * dp; textAlign = Paint.Align.CENTER; letterSpacing = 0.08f
        typeface = ResourcesCompat.getFont(context, R.font.rajdhani_bold)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val path = Path()
    private val outer = RectF()
    private val inner = RectF()

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pressed?.let { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onGo?.invoke(it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            pressed?.let { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onSave?.invoke(it) }
        }
    })

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val rOuter get() = min(width, height) / 2f - 4 * dp
    private val rInner get() = rOuter * 0.42f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val ro = rOuter
        val ri = rInner
        val gap = 2.5f
        outer.set(cx - ro, cy - ro, cx + ro, cy + ro)
        inner.set(cx - ri - 4 * dp, cy - ri - 4 * dp, cx + ri + 4 * dp, cy + ri + 4 * dp)

        for ((name, a) in sectors) {
            path.reset()
            path.arcTo(outer, a - 45 + gap, 90 - 2 * gap, true)
            path.arcTo(inner, a + 45 - gap, -(90 - 2 * gap))
            path.close()
            paintFor(name)
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
            val rm = (ro + ri) / 2f + 2 * dp
            val rad = Math.toRadians(a.toDouble())
            val tx = cx + (cos(rad) * rm).toFloat()
            val ty = cy + (sin(rad) * rm).toFloat()
            drawLabel(c, name, tx, ty)
        }

        // centre: UP (top half) / DOWN (bottom half)
        val rc = ri - 2 * dp
        val centre = RectF(cx - rc, cy - rc, cx + rc, cy + rc)
        for ((name, startAngle) in listOf("UP" to 180f, "DOWN" to 0f)) {
            path.reset()
            path.arcTo(centre, startAngle + 4, 180f - 8, true)
            path.close()
            paintFor(name)
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
            drawLabel(c, name, cx, if (name == "UP") cy - rc * 0.42f else cy + rc * 0.42f)
        }
    }

    private fun paintFor(name: String) {
        fill.color = when {
            pressed == name -> 0x9929B6F6.toInt()
            name in saved -> 0x3329B6F6
            else -> 0xB30B111C.toInt()
        }
    }

    private fun drawLabel(c: Canvas, name: String, x: Float, y: Float) {
        text.color = if (name in saved || pressed == name) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        c.drawText(name, x, y + 5 * dp, text)
        if (name in saved) c.drawCircle(x, y + 13 * dp, 2.2f * dp, dot)
    }

    private fun hit(x: Float, y: Float): String? {
        val dx = x - cx
        val dy = y - cy
        val d = hypot(dx, dy)
        if (d < rInner) return if (dy < 0) "UP" else "DOWN"
        if (d > rOuter) return null
        val deg = Math.toDegrees(atan2(dy, dx).toDouble())   // 0 = right, 90 = down
        return when {
            deg >= -135 && deg < -45 -> "FRONT"
            deg >= -45 && deg < 45 -> "RIGHT"
            deg >= 45 && deg < 135 -> "REAR"
            else -> "LEFT"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            pressed = hit(e.x, e.y)
            if (pressed == null) return false      // let the backdrop close the dial
            invalidate()
        }
        detector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = null
            invalidate()
        }
        return true
    }
}
