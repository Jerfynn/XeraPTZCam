package com.xera.ptzcam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

/**
 * Non-interactive overlay for tracking and tap-to-aim:
 *  - dashed box while the user draws a selection
 *  - bracketed target box while tracking (cyan), searching (amber)
 *  - a short "aim" marker where the user double-tapped
 * All coordinates are in this view's pixels.
 */
class TrackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { TRACKING, SEARCHING }

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val warn = ContextCompat.getColor(context, R.color.warn)
    private val shadow = 0xB3000000.toInt()

    private var selection: RectF? = null
    private var target: RectF? = null
    private var targetState = State.TRACKING
    private var targetLabel = ""
    private var aimX = 0f
    private var aimY = 0f
    private var aimProgress = 1f
    private var aimAnim: ValueAnimator? = null

    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = accent
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
        setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2229B6F6 }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; strokeCap = Paint.Cap.ROUND
        setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * dp; letterSpacing = 0.1f
        typeface = ResourcesCompat.getFont(context, R.font.rajdhani_bold)
        setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = accent
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun showSelection(r: RectF?) {
        selection = r
        invalidate()
    }

    fun showTarget(r: RectF?, state: State = State.TRACKING, text: String = "") {
        target = r
        targetState = state
        targetLabel = text
        invalidate()
    }

    /** Brief expanding ring at (x, y) to confirm a tap-to-aim command. */
    fun flashAim(x: Float, y: Float) {
        aimX = x
        aimY = y
        aimAnim?.cancel()
        aimAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            addUpdateListener { aimProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        selection?.let {
            c.drawRect(it, selFill)
            c.drawRect(it, dashed)
        }
        target?.let { drawTarget(c, it) }
        if (aimProgress < 1f) {
            val a = ((1f - aimProgress) * 255).toInt()
            aimPaint.alpha = a
            val r = (14 + 22 * aimProgress) * dp
            c.drawCircle(aimX, aimY, r, aimPaint)
            c.drawLine(aimX - 6 * dp, aimY, aimX + 6 * dp, aimY, aimPaint)
            c.drawLine(aimX, aimY - 6 * dp, aimX, aimY + 6 * dp, aimPaint)
        }
    }

    private fun drawTarget(c: Canvas, r: RectF) {
        val col = if (targetState == State.TRACKING) accent else warn
        bracket.color = col
        label.color = col
        val arm = min(r.width(), r.height()) * 0.28f
        // four corner brackets
        c.drawLine(r.left, r.top, r.left + arm, r.top, bracket)
        c.drawLine(r.left, r.top, r.left, r.top + arm, bracket)
        c.drawLine(r.right, r.top, r.right - arm, r.top, bracket)
        c.drawLine(r.right, r.top, r.right, r.top + arm, bracket)
        c.drawLine(r.left, r.bottom, r.left + arm, r.bottom, bracket)
        c.drawLine(r.left, r.bottom, r.left, r.bottom - arm, bracket)
        c.drawLine(r.right, r.bottom, r.right - arm, r.bottom, bracket)
        c.drawLine(r.right, r.bottom, r.right, r.bottom - arm, bracket)
        // centre tick
        c.drawLine(r.centerX() - 4 * dp, r.centerY(), r.centerX() + 4 * dp, r.centerY(), bracket)
        c.drawLine(r.centerX(), r.centerY() - 4 * dp, r.centerX(), r.centerY() + 4 * dp, bracket)
        if (targetLabel.isNotEmpty()) c.drawText(targetLabel, r.left, r.top - 6 * dp, label)
    }

    override fun onDetachedFromWindow() {
        aimAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
