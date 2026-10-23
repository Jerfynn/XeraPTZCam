package com.xera.ptzcam

import android.content.SharedPreferences

data class CamSettings(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val rtspOverride: String,
    val rtspTcp: Boolean,
    val maxSpeed: Float,
    val cachingMs: Int,
    val swipeInvert: Boolean = false,
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .putString("rtsp", rtspOverride)
            .putBoolean("tcp", rtspTcp)
            .putFloat("speed", maxSpeed)
            .putInt("cache", cachingMs)
            .putBoolean("swipeInv", swipeInvert)
            .apply()
    }

    companion object {
        fun load(p: SharedPreferences) = CamSettings(
            host = p.getString("host", "192.168.144.68") ?: "192.168.144.68",
            port = p.getInt("port", 80),
            user = p.getString("user", "admin") ?: "admin",
            pass = p.getString("pass", "") ?: "",
            rtspOverride = p.getString("rtsp", "") ?: "",
            rtspTcp = p.getBoolean("tcp", true),
            maxSpeed = p.getFloat("speed", 1f),
            cachingMs = p.getInt("cache", 200),
            swipeInvert = p.getBoolean("swipeInv", false),
        )
    }
}
