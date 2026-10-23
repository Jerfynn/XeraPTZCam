package com.xera.ptzcam

import android.content.SharedPreferences
import android.view.KeyEvent

/** Everything a hardware / gamepad button can be mapped to. */
enum class CamAction(
    val label: String,
    /** null = available on every camera model. */
    val model: String? = null,
    /** true = active while held (zoom), false = fires once on press. */
    val hold: Boolean = false,
) {
    ZOOM_IN("Zoom in", hold = true),
    ZOOM_OUT("Zoom out", hold = true),
    PHOTO("Take photo"),
    RECORD("Record start / stop"),
    PRESETS("Open presets"),
    PATROL("Patrol start / stop"),
    TRACK("Tracking start / stop"),
    HOME("Go to HOME", CamSettings.MODEL_SHJ),
    NIGHT("Night mode on / off", CamSettings.MODEL_SHJ),
    WIPE("Wiper", CamSettings.MODEL_SHJ),
    CENTER("Centre gimbal", CamSettings.MODEL_ZT6),
    VIEW("Next view (RGB / thermal / split)", CamSettings.MODEL_ZT6),
    PALETTE("Next thermal palette", CamSettings.MODEL_ZT6),
    MODE("Gimbal mode (lock / follow / FPV)", CamSettings.MODEL_ZT6);

    fun availableFor(cameraModel: String) = model == null || model == cameraModel

    companion object {
        fun forModel(cameraModel: String) = values().filter { it.availableFor(cameraModel) }
    }
}

/**
 * Per-camera-model mapping of key codes to actions, stored in SharedPreferences as
 * "keyCode:ACTION,keyCode:ACTION". Defaults reproduce the previous fixed gamepad layout.
 */
object KeyMapping {

    /** Keys that must keep their system meaning and can never be mapped. */
    private val RESERVED = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU,
    )

    fun isMappable(keyCode: Int) = keyCode != KeyEvent.KEYCODE_UNKNOWN && keyCode !in RESERVED

    private fun prefKey(model: String) = "keymap_" + if (model == CamSettings.MODEL_ZT6) "zt6" else "shj"

    fun defaults(model: String): Map<Int, CamAction> {
        val zt6 = model == CamSettings.MODEL_ZT6
        return linkedMapOf(
            KeyEvent.KEYCODE_BUTTON_R1 to CamAction.ZOOM_IN,
            KeyEvent.KEYCODE_BUTTON_L1 to CamAction.ZOOM_OUT,
            KeyEvent.KEYCODE_BUTTON_A to CamAction.RECORD,
            KeyEvent.KEYCODE_BUTTON_X to CamAction.PHOTO,
            KeyEvent.KEYCODE_BUTTON_Y to if (zt6) CamAction.CENTER else CamAction.HOME,
            KeyEvent.KEYCODE_BUTTON_B to if (zt6) CamAction.PALETTE else CamAction.NIGHT,
            KeyEvent.KEYCODE_BUTTON_START to CamAction.PATROL,
            KeyEvent.KEYCODE_BUTTON_SELECT to CamAction.TRACK,
        )
    }

    fun load(p: SharedPreferences, model: String): MutableMap<Int, CamAction> {
        val raw = p.getString(prefKey(model), null) ?: return defaults(model).toMutableMap()
        val map = linkedMapOf<Int, CamAction>()
        for (entry in raw.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val code = parts[0].toIntOrNull() ?: continue
            val action = runCatching { CamAction.valueOf(parts[1]) }.getOrNull() ?: continue
            if (isMappable(code) && action.availableFor(model)) map[code] = action
        }
        return map
    }

    fun save(p: SharedPreferences, model: String, map: Map<Int, CamAction>) {
        p.edit().putString(prefKey(model), map.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()
    }

    fun reset(p: SharedPreferences, model: String) {
        p.edit().remove(prefKey(model)).apply()
    }

    /** "R1", "VOLUME UP", "F3" … */
    fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')
}
