package com.xera.ptzcam

import android.content.SharedPreferences
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/** One of the four gimbal movement directions that can be learned from a joystick axis. */
enum class AxisRole(val label: String, val learnHint: String) {
    LEFT("Move left", "push the stick fully LEFT"),
    RIGHT("Move right", "push the stick fully RIGHT"),
    UP("Move up", "push the stick fully UP"),
    DOWN("Move down", "push the stick fully DOWN"),
    ZOOM("Zoom", "move the zoom slider toward zoom in"),
}

/**
 * One joystick channel bound to a role.
 * [axis] is an Android MotionEvent axis (AXIS_X, AXIS_RZ, AXIS_GENERIC_1 …), -1 = not used.
 * [center] is the value the channel reports at rest (NaN = middle of the channel's range).
 */
data class AxisBinding(
    val axis: Int,
    val invert: Boolean,
    val center: Float = Float.NaN,
    val skydroid: Boolean = false,
) {
    val used get() = axis >= 0
}

/**
 * Maps joystick axes to the four movement directions. Works with anything Android sees as a joystick:
 * USB / Bluetooth gamepads, USB RC transmitters in joystick mode, and controller sticks the
 * ground station reports to Android. Stored globally (the same sticks drive every camera model).
 */
object AxisMapping {
    private const val PREF = "axismap_v1"
    private const val PREF_DEAD = "axis_dead"
    const val DEFAULT_DEADZONE = 0.08f

    fun defaults(): Map<AxisRole, AxisBinding> = linkedMapOf(
        AxisRole.LEFT to AxisBinding(MotionEvent.AXIS_X, invert = true),
        AxisRole.RIGHT to AxisBinding(MotionEvent.AXIS_X, invert = false),
        AxisRole.UP to AxisBinding(MotionEvent.AXIS_Y, invert = true),
        AxisRole.DOWN to AxisBinding(MotionEvent.AXIS_Y, invert = false),
        AxisRole.ZOOM to AxisBinding(-1, invert = false),
    )

    fun load(p: SharedPreferences): MutableMap<AxisRole, AxisBinding> {
        val out = defaults().toMutableMap()
        val raw = p.getString(PREF, null) ?: return out
        for (part in raw.split(';')) {
            val f = part.split(':')
            if (f.size < 3) continue
            val role = runCatching { AxisRole.valueOf(f[0]) }.getOrNull() ?: continue
            val axis = f[1].toIntOrNull() ?: continue
            val inv = f[2] == "1"
            val center = f.getOrNull(3)?.toFloatOrNull() ?: Float.NaN
            out[role] = AxisBinding(axis, inv, center, f.getOrNull(4) == "S")
        }
        return out
    }

    fun save(p: SharedPreferences, map: Map<AxisRole, AxisBinding>) {
        val raw = map.entries.joinToString(";") { (r, b) ->
            "${r.name}:${b.axis}:${if (b.invert) 1 else 0}:${if (b.center.isNaN()) "" else b.center}:${if (b.skydroid) "S" else "A"}"
        }
        p.edit().putString(PREF, raw).apply()
    }

    fun reset(p: SharedPreferences) {
        p.edit().remove(PREF).remove(PREF_DEAD).apply()
    }

    fun deadzone(p: SharedPreferences) = p.getFloat(PREF_DEAD, DEFAULT_DEADZONE)
    fun setDeadzone(p: SharedPreferences, v: Float) = p.edit().putFloat(PREF_DEAD, v.coerceIn(0f, 0.5f)).apply()

    fun axisName(axis: Int): String {
        if (axis < 0) return "—"
        val n = MotionEvent.axisToString(axis) ?: return "AXIS $axis"
        return n.removePrefix("AXIS_").replace("GENERIC_", "CH ")
    }

    /** All axes the device exposes as joystick axes. */
    fun joystickAxes(dev: InputDevice?): List<InputDevice.MotionRange> =
        dev?.motionRanges?.filter { it.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) } ?: emptyList()

    /**
     * Channel value of [b] in [e], normalised to -1 … +1 around its rest value, with inversion.
     * Returns 0 if the axis is not present on this device.
     */
    fun value(e: MotionEvent, b: AxisBinding): Float {
        if (!b.used || b.skydroid) return 0f
        val range = e.device?.getMotionRange(b.axis, e.source) ?: return 0f
        val v = e.getAxisValue(b.axis)
        val min = range.min
        val max = range.max
        val c = if (b.center.isNaN()) (min + max) / 2f else b.center
        var n = when {
            v >= c && max - c > 1e-4f -> (v - c) / (max - c)
            v < c && c - min > 1e-4f -> (v - c) / (c - min)
            else -> 0f
        }
        if (abs(v - c) <= range.flat) n = 0f
        n = n.coerceIn(-1f, 1f)
        return if (b.invert) -n else n
    }

    /** Skydroid RCSDK channels are integer values; use the controller's configured limits when known. */
    fun value(channels: IntArray, b: AxisBinding, mins: IntArray, middles: IntArray, maxes: IntArray): Float {
        if (!b.used || !b.skydroid || b.axis !in channels.indices) return 0f
        val i = b.axis
        val lo = mins.getOrElse(i) { 0 }.toFloat()
        val hi = maxes.getOrElse(i) { 1000 }.toFloat()
        val c = if (b.center.isNaN()) middles.getOrElse(i) { ((lo + hi) / 2f).toInt() }.toFloat() else b.center
        val v = channels[i].toFloat()
        val n = when {
            v >= c && hi - c > 1e-4f -> (v - c) / (hi - c)
            v < c && c - lo > 1e-4f -> (v - c) / (c - lo)
            else -> 0f
        }.coerceIn(-1f, 1f)
        return if (b.invert) -n else n
    }
}

/**
 * "LEARN" helper: remembers each axis value at rest, then picks the channel that moves the most.
 * The direction the user was asked to push decides inversion automatically.
 */
class AxisLearner(val role: AxisRole) {
    private val rest = HashMap<Int, Float>()
    private val ranges = HashMap<Int, Float>()
    private val channelRest = HashMap<Int, Float>()

    /** Seeds rest values from the last known state of every axis (stick released). */
    fun seed(lastValues: Map<Int, Float>) {
        rest.putAll(lastValues)
    }

    /** Returns a binding once one channel has clearly been pushed, else null. */
    fun feed(e: MotionEvent): AxisBinding? {
        var best = -1
        var bestFrac = 0f
        var bestDelta = 0f
        for (r in AxisMapping.joystickAxes(e.device)) {
            val a = r.axis
            val v = e.getAxisValue(a)
            val base = rest.getOrPut(a) { v }
            val half = ((r.max - r.min) / 2f).coerceAtLeast(1e-4f)
            ranges[a] = half
            val frac = abs(v - base) / half
            if (frac > bestFrac) { bestFrac = frac; best = a; bestDelta = v - base }
        }
        if (best < 0 || bestFrac < 0.45f) return null
        // HAT axes are D-pads: they stay fixed to the D-pad, never learned as a stick channel
        if (best == MotionEvent.AXIS_HAT_X || best == MotionEvent.AXIS_HAT_Y) return null
        val center = rest[best] ?: 0f
        return AxisBinding(best, invert = bestDelta < 0, center = center)
    }

    /** Learn a Skydroid RCSDK channel while the user pushes one physical stick direction. */
    fun feedChannels(values: IntArray, mins: IntArray, middles: IntArray, maxes: IntArray): AxisBinding? {
        var best = -1
        var bestFrac = 0f
        var bestDelta = 0f
        for (i in values.indices) {
            val center = middles.getOrElse(i) { 500 }.toFloat()
            val base = channelRest.getOrPut(i) { center }
            val low = mins.getOrElse(i) { 0 }.toFloat()
            val high = maxes.getOrElse(i) { 1000 }.toFloat()
            val half = maxOf(center - low, high - center).coerceAtLeast(1f)
            val frac = abs(values[i] - base) / half
            if (frac > bestFrac) { bestFrac = frac; best = i; bestDelta = values[i] - base }
        }
        if (best < 0 || bestFrac < 0.35f) return null
        // Use the configured channel midpoint so a learned analog slider can zoom both in and out.
        val mid = middles.getOrElse(best) { 500 }.toFloat()
        return AxisBinding(best, invert = bestDelta < 0, center = mid, skydroid = true)
    }

    /** Seed learning at the live RC values, especially important when a slider rests off-center. */
    fun seedChannels(values: IntArray) {
        values.forEachIndexed { i, value -> channelRest[i] = value.toFloat() }
    }
}
