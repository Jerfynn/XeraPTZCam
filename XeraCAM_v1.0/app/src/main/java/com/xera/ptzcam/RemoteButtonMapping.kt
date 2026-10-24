package com.xera.ptzcam

import android.content.SharedPreferences

enum class RemoteButtonAction(val label: String) {
    RECORD("Recording"),
    CAPTURE("Capture photo"),
    WIPE("Wipe"),
    AUTO("IR Auto"),
    HOME("Home"),
}

data class RemoteButtonBinding(
    val keyCode: Int? = null,
    val rcChannel: Int? = null,
    val direction: Int = 0,
)

/** Learned Android key codes from the G20's physical buttons. */
object RemoteButtonMapping {
    private const val PREF = "remote_button_map_v1"

    fun load(prefs: SharedPreferences): MutableMap<RemoteButtonAction, RemoteButtonBinding> {
        val out = linkedMapOf<RemoteButtonAction, RemoteButtonBinding>()
        prefs.getString(PREF, null)?.split(';')?.forEach { entry ->
            val fields = entry.split(':')
            if (fields.size != 2) return@forEach
            val action = runCatching { RemoteButtonAction.valueOf(fields[0]) }.getOrNull() ?: return@forEach
            val parts = fields[1].split(',')
            when (parts.firstOrNull()) {
                "K" -> parts.getOrNull(1)?.toIntOrNull()?.let { out[action] = RemoteButtonBinding(keyCode = it) }
                "C" -> {
                    val channel = parts.getOrNull(1)?.toIntOrNull()
                    val direction = parts.getOrNull(2)?.toIntOrNull()
                    if (channel != null && direction != null) {
                        out[action] = RemoteButtonBinding(rcChannel = channel, direction = direction)
                    }
                }
            }
        }
        return out
    }

    fun save(prefs: SharedPreferences, map: Map<RemoteButtonAction, RemoteButtonBinding>) {
        val value = map.entries.joinToString(";") { (action, binding) ->
            val source = binding.keyCode?.let { "K,$it" }
                ?: "C,${binding.rcChannel},${binding.direction}"
            "${action.name}:$source"
        }
        prefs.edit().putString(PREF, value).apply()
    }
}
