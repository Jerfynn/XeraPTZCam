package com.xera.ptzcam

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight single-target tracker: normalized cross-correlation (NCC) template matching
 * on a grayscale frame, with a coarse-to-fine local search and a slowly adapting template.
 *
 * Pure Kotlin (no Android / OpenCV) so it is small, fast enough at ~320 px frames, and unit-testable.
 * NCC is invariant to brightness/contrast changes, which suits both visible and thermal video.
 */
class VisualTracker {

    /** Result of one update, in full-frame pixel coordinates. */
    data class Box(
        val x: Float, val y: Float, val w: Float, val h: Float,
        /** NCC with the current (adapted) template, 1 = perfect. */
        val score: Float,
        /** NCC with the ORIGINAL selection – low means the box has slid onto something else. */
        val anchorScore: Float = 1f,
        /** Best score found clearly away from the peak – close to [score] means "looks alike, unsure". */
        val secondScore: Float = -1f,
    ) {
        val cx get() = x + w / 2f
        val cy get() = y + h / 2f
    }

    var box: Box? = null
        private set

    /** Centre-weighted matching (default on). Must be set before [init]. */
    var weighted = true

    private var level = 1            // downsample factor used for matching
    private var tw = 0               // template size (at matching level)
    private var th = 0
    private var tpl = FloatArray(0)  // zero-mean template (adapts slowly)
    private var tplNorm = 0f
    private var anchor = FloatArray(0)   // original selection, guards against drifting onto background
    private var anchorNorm = 0f
    private var wts = FloatArray(0)      // centre weights (Gaussian), 1 everywhere when not weighted
    private var wSum = 0f
    private var wtpl = FloatArray(0)     // weights × zero-mean template (for weighted NCC)
    private var wtplNorm = 0f
    private var px = 0               // template top-left (matching level)
    private var py = 0
    private var fullW = 0
    private var fullH = 0
    private var lowW = 0
    private var lowH = 0
    private var low = FloatArray(0)
    private var wAnchor = FloatArray(0)      // weighted anchor template (for anchorScore)
    private var wAnchorNorm = 0f
    private var prevPx = 0
    private var prevPy = 0
    private var coarse = FloatArray(0)

    // search limits in full-frame pixels (e.g. one half of a split RGB | thermal picture)
    private var bx0 = 0
    private var by0 = 0
    private var bx1 = Int.MAX_VALUE
    private var by1 = Int.MAX_VALUE

    /** Restricts matching to this rectangle (full-frame pixels). Call before [init]. */
    fun setBounds(x0: Int, y0: Int, x1: Int, y1: Int) {
        bx0 = x0; by0 = y0; bx1 = x1; by1 = y1
    }

    private fun minPx() = (bx0 + level - 1) / level
    private fun minPy() = (by0 + level - 1) / level
    private fun maxPx() = min(lowW, bx1 / level) - tw
    private fun maxPy() = min(lowH, by1 / level) - th

    val isInitialised get() = tw > 0

    /**
     * Starts tracking the region (x, y, w, h) of a [width]×[height] luma frame.
     * Returns false if the region has no texture (nothing to lock onto).
     */
    fun init(luma: FloatArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        fullW = width
        fullH = height
        val bw = w.coerceIn(MIN_BOX, width)
        val bh = h.coerceIn(MIN_BOX, height)
        level = max(1, ((max(bw, bh) + MAX_TEMPLATE - 1) / MAX_TEMPLATE))
        downsample(luma)
        tw = max(MIN_TEMPLATE, bw / level).coerceAtMost(lowW)
        th = max(MIN_TEMPLATE, bh / level).coerceAtMost(lowH)
        tw = tw.coerceAtMost(max(MIN_TEMPLATE, (min(lowW, bx1 / level) - minPx())))
        th = th.coerceAtMost(max(MIN_TEMPLATE, (min(lowH, by1 / level) - minPy())))
        px = ((x + bw / 2) / level - tw / 2).coerceIn(minPx(), max(minPx(), maxPx()))
        py = ((y + bh / 2) / level - th / 2).coerceIn(minPy(), max(minPy(), maxPy()))
        tpl = FloatArray(tw * th)
        extract(px, py, tpl)
        buildWeights()
        tplNorm = zeroMean(tpl)
        anchor = tpl.copyOf()
        anchorNorm = tplNorm
        prepareWeighted()
        prepareWeighted(anchor).let { (a, n) -> wAnchor = a; wAnchorNorm = n }
        if (tplNorm < MIN_TEXTURE * sqrt((tw * th).toFloat())) {
            tw = 0
            box = null
            return false
        }
        box = Box((px * level).toFloat(), (py * level).toFloat(), (tw * level).toFloat(), (th * level).toFloat(), 1f)
        return true
    }

    /**
     * Finds the target in the next frame, searching ±[searchRadius] full-frame pixels around
     * the last position moved by the expected shift ([shiftX], [shiftY] in full-frame pixels,
     * e.g. the image motion caused by the camera turning). Returns the new box
     * (score = NCC peak, 1 = perfect match).
     */
    fun update(luma: FloatArray, searchRadius: Int, shiftX: Float = 0f, shiftY: Float = 0f): Box? {
        if (!isInitialised) return null
        downsample(luma)
        prevPx = px
        prevPy = py
        val lo = minPx()
        val hi = max(lo, maxPx())
        val loY = minPy()
        val hiY = max(loY, maxPy())
        px = (px + (shiftX / level).roundToInt()).coerceIn(lo, hi)
        py = (py + (shiftY / level).roundToInt()).coerceIn(loY, hiY)
        val r = max(2, searchRadius / level)
        val x0 = max(lo, px - r)
        val x1 = min(hi, px + r)
        val y0 = max(loY, py - r)
        val y1 = min(hiY, py + r)
        if (x1 < x0 || y1 < y0) return box

        // coarse pass (step 2) – scores kept to find a competing look-alike – then refine
        val cols = (x1 - x0) / 2 + 1
        val rows = (y1 - y0) / 2 + 1
        if (coarse.size < cols * rows) coarse = FloatArray(cols * rows)
        var best = -2f
        var bx = px
        var by = py
        for (j in 0 until rows) for (i in 0 until cols) {
            val xx = x0 + i * 2
            val yy = y0 + j * 2
            val s = ncc(xx, yy)
            coarse[j * cols + i] = s
            if (s > best) { best = s; bx = xx; by = yy }
        }
        // second best peak at least half a template away from the best one
        var second = -1f
        val sepX = max(2, tw / 2)
        val sepY = max(2, th / 2)
        for (j in 0 until rows) for (i in 0 until cols) {
            val xx = x0 + i * 2
            val yy = y0 + j * 2
            if (kotlin.math.abs(xx - bx) < sepX && kotlin.math.abs(yy - by) < sepY) continue
            val s = coarse[j * cols + i]
            if (s > second) second = s
        }
        val cx = bx
        val cy = by
        for (dy in -1..1) for (dx in -1..1) {
            val xx = cx + dx
            val yy2 = cy + dy
            if (xx < x0 || xx > x1 || yy2 < y0 || yy2 > y1) continue
            val s = ncc(xx, yy2)
            if (s > best) { best = s; bx = xx; by = yy2 }
        }

        px = bx
        py = by
        val anchorScore = nccWith(px, py, wAnchor, wAnchorNorm)
        // adapt the template slowly while the match is confident (handles gradual appearance change)
        if (best > UPDATE_THRESHOLD && second < best - 0.10f) adaptTemplate(anchorScore)

        box = Box(
            (px * level).toFloat(), (py * level).toFloat(), (tw * level).toFloat(), (th * level).toFloat(),
            best, anchorScore, second,
        )
        return box
    }

    /** Undo the last [update]: the match was not trusted, keep the previous position. */
    fun rollback() {
        px = prevPx
        py = prevPy
        box = box?.copy(x = (px * level).toFloat(), y = (py * level).toFloat())
    }

    fun reset() {
        tw = 0
        box = null
    }

    // ------------------------------------------------------------------ internals

    private fun downsample(src: FloatArray) {
        lowW = fullW / level
        lowH = fullH / level
        if (low.size != lowW * lowH) low = FloatArray(lowW * lowH)
        if (level == 1) {
            System.arraycopy(src, 0, low, 0, lowW * lowH)
            return
        }
        val inv = 1f / (level * level)
        for (y in 0 until lowH) {
            for (x in 0 until lowW) {
                var s = 0f
                val sy = y * level
                val sx = x * level
                for (j in 0 until level) {
                    val row = (sy + j) * fullW + sx
                    for (i in 0 until level) s += src[row + i]
                }
                low[y * lowW + x] = s * inv
            }
        }
    }

    private fun extract(x: Int, y: Int, out: FloatArray) {
        for (j in 0 until th) System.arraycopy(low, (y + j) * lowW + x, out, j * tw, tw)
    }

    /** Subtracts the mean in place, returns the L2 norm of the result. */
    private fun zeroMean(a: FloatArray): Float {
        var m = 0f
        for (v in a) m += v
        m /= a.size
        var n = 0f
        for (k in a.indices) { a[k] -= m; n += a[k] * a[k] }
        return sqrt(n)
    }

    /** NCC of the (centre-weighted) template against the image patch at (x, y). */
    private fun ncc(x: Int, y: Int): Float = nccWith(x, y, wtpl, wtplNorm)

    private fun nccWith(x: Int, y: Int, t: FloatArray, tNorm: Float): Float {
        // t = w·(T − mean_w T): its weighted mean is zero, so the image mean cancels in the numerator
        var sI = 0f
        var sII = 0f
        var sIT = 0f
        var k = 0
        for (j in 0 until th) {
            var idx = (y + j) * lowW + x
            for (i in 0 until tw) {
                val v = low[idx++]
                val w = wts[k]
                sI += w * v
                sII += w * v * v
                sIT += v * t[k++]
            }
        }
        val varI = sII - sI * sI / wSum
        if (varI <= 1e-3f || tNorm <= 1e-3f) return 0f
        return sIT / (sqrt(varI) * tNorm)
    }

    /** Gaussian weights centred on the box (σ = 30 % of its size) so edge background matters little. */
    private fun buildWeights() {
        wts = FloatArray(tw * th)
        val cx = (tw - 1) / 2f
        val cy = (th - 1) / 2f
        val sx = max(1f, tw * 0.30f)
        val sy = max(1f, th * 0.30f)
        wSum = 0f
        for (j in 0 until th) for (i in 0 until tw) {
            val dx = (i - cx) / sx
            val dy = (j - cy) / sy
            val w = if (weighted) kotlin.math.exp(-0.5f * (dx * dx + dy * dy)) else 1f
            wts[j * tw + i] = w
            wSum += w
        }
    }

    /** Weighted, weighted-zero-mean template and its weighted norm for [nccWith]. */
    private fun prepareWeighted(src: FloatArray = tpl): Pair<FloatArray, Float> {
        var m = 0f
        for (k in src.indices) m += wts[k] * src[k]
        m /= wSum
        val out = FloatArray(src.size)
        var n = 0f
        for (k in src.indices) {
            val d = src[k] - m
            out[k] = wts[k] * d
            n += wts[k] * d * d
        }
        val norm = sqrt(n)
        if (src === tpl) { wtpl = out; wtplNorm = norm }
        return out to norm
    }

    /**
     * Blends the current patch into the template, but only while the patch still resembles the
     * ORIGINAL selection – this stops the box from gradually "becoming" background and sliding off.
     */
    private fun adaptTemplate(anchorScore: Float) {
        if (anchorScore < ANCHOR_MIN) return
        val patch = FloatArray(tw * th)
        extract(px, py, patch)
        zeroMean(patch)
        for (k in tpl.indices) tpl[k] = tpl[k] * (1f - ADAPT_RATE) + patch[k] * ADAPT_RATE
        tplNorm = zeroMean(tpl)
        prepareWeighted()
    }

    companion object {
        const val MIN_BOX = 8
        private const val MIN_TEMPLATE = 8
        private const val MAX_TEMPLATE = 40
        private const val MIN_TEXTURE = 2.0f      // minimum per-pixel std-dev of the template
        private const val UPDATE_THRESHOLD = 0.80f
        private const val ADAPT_RATE = 0.04f
        private const val ANCHOR_MIN = 0.65f      // patch must still match the original selection this well

        /** Mean colour saturation (max − min channel, 0..255) of columns [x0, x1). */
        fun saturation(argb: IntArray, w: Int, h: Int, x0: Int, x1: Int): Float {
            var sum = 0L
            var n = 0
            var y = 0
            while (y < h) {
                var x = x0
                while (x < x1) {
                    val c = argb[y * w + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    sum += (maxOf(r, g, b) - minOf(r, g, b))
                    n++
                    x += 3
                }
                y += 3
            }
            return if (n == 0) 0f else sum.toFloat() / n
        }

        /**
         * True if the picture is two images side by side (ZT6 "split": visible | thermal).
         * Looks for a colour-vs-grey difference between the halves, or a hard vertical seam
         * running down the whole middle column.
         */
        fun isSideBySide(argb: IntArray, luma: FloatArray, w: Int, h: Int): Boolean {
            val mid = w / 2
            val sl = saturation(argb, w, h, 0, mid)
            val sr = saturation(argb, w, h, mid, w)
            if ((sl > 14f && sr < 5f) || (sr > 14f && sl < 5f)) return true
            var bestSeam = 0f
            for (off in -2..1) {
                val c = mid + off
                var d = 0f
                for (y in 0 until h) d += kotlin.math.abs(luma[y * w + c] - luma[y * w + c + 1])
                bestSeam = max(bestSeam, d / h)
            }
            var ref = 0f
            var cnt = 0
            var x = w / 8
            while (x < w - w / 8) {
                if (kotlin.math.abs(x - mid) > 4) {
                    for (y in 0 until h step 2) ref += kotlin.math.abs(luma[y * w + x] - luma[y * w + x + 1])
                    cnt += (h + 1) / 2
                }
                x += 7
            }
            val avg = if (cnt == 0) 0f else ref / cnt
            return bestSeam > 10f && bestSeam > avg * 3.5f
        }

        /** Converts ARGB pixels to luma (0..255). */
        fun luma(argb: IntArray, out: FloatArray) {
            for (i in argb.indices) {
                val c = argb[i]
                out[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29 shr 8).toFloat()
            }
        }

    }
}
