package com.xera.ptzcam

import android.content.SharedPreferences

/**
 * Settings. Connection fields are stored per camera model, so switching models
 * in the dropdown keeps each camera's own IP / ports / streams.
 */
data class CamSettings(
    val model: String = MODEL_SHJ,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val rtspOverride: String,
    val rtsp2: String = "",
    val rtspTcp: Boolean,
    val maxSpeed: Float,
    val cachingMs: Int,
    val swipeInvert: Boolean = false,
    val wiperPreset: String = "",
    val maxZoom: Int = 30,
    val sdRecord: Boolean = true,
    val debug: Boolean = false,
) {
    val isSiyi get() = model == MODEL_ZT6

    fun save(p: SharedPreferences) {
        val pre = prefix(model)
        p.edit()
            .putString("model", model)
            .putString(pre + "host", host)
            .putInt(pre + "port", port)
            .putString(pre + "user", user)
            .putString(pre + "pass", pass)
            .putString(pre + "rtsp", rtspOverride)
            .putString(pre + "rtsp2", rtsp2)
            .putInt(pre + "maxZoom", maxZoom)
            .putString("wiperPreset", wiperPreset)
            .putBoolean("tcp", rtspTcp)
            .putFloat("speed", maxSpeed)
            .putInt("cache", cachingMs)
            .putBoolean("swipeInv", swipeInvert)
            .putBoolean("sdRecord", sdRecord)
            .putBoolean("debug", debug)
            .apply()
    }

    companion object {
        const val MODEL_SHJ = "SHJ-TW10-IR"
        const val MODEL_ZT6 = "SIYI ZT6"
        val MODELS = listOf(MODEL_SHJ, MODEL_ZT6)

        private fun prefix(model: String) = if (model == MODEL_ZT6) "zt6_" else ""

        fun load(p: SharedPreferences) = loadFor(p, p.getString("model", MODEL_SHJ) ?: MODEL_SHJ)

        /** Settings for [model] (its saved values, or that model's defaults). */
        fun loadFor(p: SharedPreferences, model: String): CamSettings {
            val pre = prefix(model)
            val siyi = model == MODEL_ZT6
            val defHost = if (siyi) "192.168.144.25" else "192.168.144.68"
            return CamSettings(
                model = model,
                host = p.getString(pre + "host", defHost) ?: defHost,
                port = p.getInt(pre + "port", if (siyi) SiyiClient.DEFAULT_PORT else 80),
                user = p.getString(pre + "user", if (siyi) "" else "admin") ?: "",
                pass = p.getString(pre + "pass", "") ?: "",
                rtspOverride = p.getString(pre + "rtsp", if (siyi) "rtsp://$defHost:8554/video1" else "") ?: "",
                rtsp2 = p.getString(pre + "rtsp2", if (siyi) "rtsp://$defHost:8554/video2" else "") ?: "",
                rtspTcp = p.getBoolean("tcp", true),
                maxSpeed = p.getFloat("speed", 1f),
                cachingMs = p.getInt("cache", 200),
                swipeInvert = p.getBoolean("swipeInv", false),
                wiperPreset = p.getString("wiperPreset", "") ?: "",
                maxZoom = p.getInt(pre + "maxZoom", if (siyi) 6 else 30),
                sdRecord = p.getBoolean("sdRecord", true),
                debug = p.getBoolean("debug", false),
            )
        }
    }
}
