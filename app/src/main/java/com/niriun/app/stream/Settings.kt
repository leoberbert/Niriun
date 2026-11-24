package com.niriun.app.stream

import android.content.Context

/** User preferences that survive restarts. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("niriun_settings", Context.MODE_PRIVATE)

    var cameraId: String?
        get() = prefs.getString(KEY_CAMERA, null)
        set(value) = prefs.edit().putString(KEY_CAMERA, value).apply()

    var mirror: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, false)
        set(value) = prefs.edit().putBoolean(KEY_MIRROR, value).apply()

    /** The first-run explanation screen has been shown. */
    var welcomeSeen: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME, value).apply()


    var obsHost: String
        get() = prefs.getString(KEY_OBS_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OBS_HOST, value.trim()).apply()

    var obsPort: Int
        get() = prefs.getInt(KEY_OBS_PORT, 4455)
        set(value) = prefs.edit().putInt(KEY_OBS_PORT, value).apply()

    /** Stored in plain app-private preferences; it only protects a LAN-only OBS WebSocket. */
    var obsPassword: String
        get() = prefs.getString(KEY_OBS_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OBS_PASSWORD, value).apply()

    /** Name of the OBS source that carries this phone's video. */
    var obsSource: String
        get() = prefs.getString(KEY_OBS_SOURCE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OBS_SOURCE, value).apply()

    private companion object {
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_OBS_PASSWORD = "obs_password"
        const val KEY_OBS_SOURCE = "obs_source"
        const val KEY_CAMERA = "camera_id"
        const val KEY_MIRROR = "mirror"
        const val KEY_WELCOME = "welcome_seen"
    }
}
