package com.xera.ptzcam

import android.content.SharedPreferences
import android.view.KeyEvent

/** What a hardware button can do: move the gimbal while it is held. */
enum class CamAction(val label: String, val pan: Int, val tilt: Int) {
    MOVE_LEFT("Move left", -1, 0),
    MOVE_RIGHT("Move right", 1, 0),
    MOVE_UP("Move up", 0, 1),
    MOVE_DOWN("Move down", 0, -1);

    /** Every action is active while the button is held. */
    val hold get() = true
}

/**
 * Button → movement mapping, shared by all camera models, stored as "id:ACTION,id:ACTION".
 *
 * A button is identified by its Android key code. Some ground-station buttons (Skydroid) arrive with
 * key code 0 (UNKNOWN) and only a hardware scan code – those are stored as a NEGATIVE id (−scanCode),
 * so they can be mapped as well.
 */
object KeyMapping {

    private const val PREF = "keymap_v2"      // new key: old 14-action mappings are dropped

    /** Keys that must keep their system meaning and can never be mapped. */
    private val RESERVED = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH,
    )

    /** Mapping id of a key event: key code, or −scanCode when the key code is unknown. 0 = unusable. */
    fun idOf(e: KeyEvent): Int = when {
        e.keyCode != KeyEvent.KEYCODE_UNKNOWN -> e.keyCode
        e.scanCode > 0 -> -e.scanCode
        else -> 0
    }

    fun isMappable(id: Int) = id != 0 && id !in RESERVED

    fun defaults(): Map<Int, CamAction> = linkedMapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to CamAction.MOVE_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to CamAction.MOVE_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP to CamAction.MOVE_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to CamAction.MOVE_DOWN,
    )

    @Suppress("UNUSED_PARAMETER")
    fun load(p: SharedPreferences, model: String = ""): MutableMap<Int, CamAction> {
        val raw = p.getString(PREF, null) ?: return defaults().toMutableMap()
        val map = linkedMapOf<Int, CamAction>()
        for (entry in raw.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val id = parts[0].toIntOrNull() ?: continue
            val action = runCatching { CamAction.valueOf(parts[1]) }.getOrNull() ?: continue
            if (isMappable(id)) map[id] = action
        }
        return map
    }

    @Suppress("UNUSED_PARAMETER")
    fun save(p: SharedPreferences, model: String, map: Map<Int, CamAction>) {
        p.edit().putString(PREF, map.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun reset(p: SharedPreferences, model: String = "") {
        p.edit().remove(PREF).apply()
    }

    /** "DPAD LEFT", "R1", "SCAN 248" … */
    fun keyName(id: Int): String =
        if (id < 0) "SCAN ${-id}"
        else KeyEvent.keyCodeToString(id).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')
}
