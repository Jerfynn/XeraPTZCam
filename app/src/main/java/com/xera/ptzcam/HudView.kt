package com.xera.ptzcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Centre crosshair with corner brackets, drawn over the video (never intercepts touches). */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val fontUi = ResourcesCompat.getFont(context, R.font.rajdhani_semibold)
    private val fontMono = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
    private val shadow = 0xB3000000.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt(); strokeWidth = 1.5f * dp; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val tick = Paint(line).apply { strokeWidth = 1.2f * dp; color = 0xB3FFFFFF.toInt() }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.FILL; setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); textSize = 11.5f * dp; typeface = fontUi
        textAlign = Paint.Align.CENTER; setShadowLayer(3 * dp, 0f, 0f, shadow)
    }
    private val labelAccent = Paint(label).apply { color = accent; textSize = 13f * dp }
    private val labelLeft = Paint(label).apply { textAlign = Paint.Align.LEFT; textSize = 10.5f * dp }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = 12f * dp; typeface = fontMono; textAlign = Paint.Align.CENTER
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB30B111C.toInt(); style = Paint.Style.FILL }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 1.2f * dp
    }
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCrosshair(canvas)
    }

    private fun drawCrosshair(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val gap = 7 * dp
        val len = 15 * dp
        c.drawLine(cx - gap - len, cy, cx - gap, cy, line)
        c.drawLine(cx + gap, cy, cx + gap + len, cy, line)
        c.drawLine(cx, cy - gap - len, cx, cy - gap, line)
        c.drawLine(cx, cy + gap, cx, cy + gap + len, line)
        c.drawCircle(cx, cy, 1.8f * dp, accentFill)

        val bx = 74 * dp
        val by = 46 * dp
        val arm = 13 * dp
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val x = cx + sx * bx
            val y = cy + sy * by
            c.drawLine(x, y, x - sx * arm, y, tick)
            c.drawLine(x, y, x, y - sy * arm, tick)
        }
    }
}
