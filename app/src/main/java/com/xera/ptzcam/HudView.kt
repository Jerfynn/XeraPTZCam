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

/**
 * Instrument overlay drawn on top of the video (never intercepts touches):
 *  - centre crosshair with corner brackets
 *  - compass tape at the top: camera heading relative to the vehicle FRONT (F / R / B / L)
 *  - tilt scale on the left
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Heading in degrees 0..360 relative to FRONT; null = unknown. */
    var heading: Float? = null
        set(v) { field = v; invalidate() }

    /** Tilt in degrees (+ = up); null = unknown. */
    var tilt: Float? = null
        set(v) { field = v; invalidate() }

    var insetLeft = 0f
    var insetRight = 0f
    var insetTop = 0f

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
        drawCompass(canvas)
        drawTilt(canvas)
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

    private fun drawCompass(c: Canvas) {
        val avail = width - insetLeft - insetRight
        if (avail <= 0) return
        val w = min(avail * 0.55f, 440 * dp)
        val cx = insetLeft + avail / 2f
        val top = insetTop
        val h = 26 * dp
        val left = cx - w / 2
        val right = cx + w / 2
        val pxPerDeg = 2.6f * dp
        val hd = heading
        val centre = hd ?: 0f
        val alphaDim = if (hd == null) 0.45f else 1f

        c.save()
        c.clipRect(left, top - 4 * dp, right, top + h + 1 * dp)
        val span = (w / 2f / pxPerDeg).toInt() + 5
        val start = floor(centre - span).toInt()
        val end = (centre + span).toInt()
        for (d in start..end) {
            if (d % 5 != 0) continue
            val x = cx + (d - centre) * pxPerDeg
            val major = d % 15 == 0
            val th = if (major) 9 * dp else 5 * dp
            val edge = 1f - min(1f, abs(x - cx) / (w / 2f)) // fade toward the ends
            tick.alpha = (0xB3 * alphaDim * (0.25f + 0.75f * edge)).toInt()
            c.drawLine(x, top + h - th, x, top + h, tick)
            if (major) {
                val deg = ((d % 360) + 360) % 360
                val p = if (deg % 90 == 0) labelAccent else label
                p.alpha = (255 * alphaDim * (0.25f + 0.75f * edge)).toInt()
                c.drawText(compassLabel(deg), x, top + h - th - 3 * dp, p)
            }
        }
        c.restore()
        tick.alpha = 0xB3; label.alpha = 0xE6; labelAccent.alpha = 255

        // pointer + readout box
        path.reset()
        path.moveTo(cx, top + h + 2 * dp)
        path.lineTo(cx - 5 * dp, top + h + 9 * dp)
        path.lineTo(cx + 5 * dp, top + h + 9 * dp)
        path.close()
        c.drawPath(path, accentFill)

        val txt = hd?.let { String.format(Locale.US, "%03d°", ((it.roundToInt() % 360) + 360) % 360) } ?: "---°"
        val tw = readout.measureText(txt) + 14 * dp
        rect.set(cx - tw / 2, top + h + 11 * dp, cx + tw / 2, top + h + 30 * dp)
        c.drawRoundRect(rect, 5 * dp, 5 * dp, boxFill)
        c.drawRoundRect(rect, 5 * dp, 5 * dp, boxStroke)
        c.drawText(txt, cx, rect.bottom - 5.5f * dp, readout)
    }

    private fun drawTilt(c: Canvas) {
        val x = insetLeft + 10 * dp
        val cy = height / 2f
        val half = min(85 * dp, height * 0.25f)
        val pxPerDeg = 2.2f * dp
        val t = tilt
        val centre = t ?: 0f
        val alphaDim = if (t == null) 0.45f else 1f

        c.save()
        c.clipRect(x - 2 * dp, cy - half, x + 44 * dp, cy + half)
        val span = (half / pxPerDeg).toInt() + 5
        for (d in (centre - span).toInt()..(centre + span).toInt()) {
            if (d % 5 != 0 || d < -90 || d > 90) continue
            val y = cy - (d - centre) * pxPerDeg
            val major = d % 15 == 0
            val edge = 1f - min(1f, abs(y - cy) / half)
            tick.alpha = (0xB3 * alphaDim * (0.2f + 0.8f * edge)).toInt()
            c.drawLine(x, y, x + (if (major) 11 * dp else 6 * dp), y, tick)
            if (major) {
                labelLeft.alpha = (255 * alphaDim * (0.2f + 0.8f * edge)).toInt()
                c.drawText(if (d > 0) "+$d" else "$d", x + 14 * dp, y + 4 * dp, labelLeft)
            }
        }
        c.restore()
        tick.alpha = 0xB3; labelLeft.alpha = 0xE6

        path.reset()
        path.moveTo(x - 1 * dp, cy)
        path.lineTo(x - 8 * dp, cy - 5 * dp)
        path.lineTo(x - 8 * dp, cy + 5 * dp)
        path.close()
        c.drawPath(path, accentFill)

        val txt = t?.let { String.format(Locale.US, "%+d°", it.roundToInt()) } ?: "--°"
        val tw = readout.measureText(txt) + 14 * dp
        rect.set(x - 8 * dp, cy + half + 8 * dp, x - 8 * dp + tw, cy + half + 27 * dp)
        c.drawRoundRect(rect, 5 * dp, 5 * dp, boxFill)
        c.drawRoundRect(rect, 5 * dp, 5 * dp, boxStroke)
        c.drawText(txt, rect.centerX(), rect.bottom - 5.5f * dp, readout)
        c.drawText("TILT", rect.centerX(), cy - half - 6 * dp, label)
    }

    private fun compassLabel(deg: Int) = when (deg) {
        0 -> "F"
        90 -> "R"
        180 -> "B"
        270 -> "L"
        else -> deg.toString()
    }
}
