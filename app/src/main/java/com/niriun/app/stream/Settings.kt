package com.niriun.app.stream

import android.content.Context

/** When the controls hide: AUTO after 5 s of streaming, COMPACT keeps only the top bar, ALWAYS never hides them. */
enum class ControlsMode { AUTO, COMPACT, ALWAYS }

/** What OBS shows after the phone stops: a black picture or an "offline" card (sent as message 0x31). */
enum class StopScreen(val wire: Int) { BLACK(1), OFFLINE(2) }

enum class FramingGrid { OFF, THIRDS, CROSS }

/** Saves battery and heat: while streaming with the controls hidden the screen is dimmed or turned (almost) off. */
enum class ScreenPower { NORMAL, DIM, OFF }

/** Border and haptic pulse when the OBS tally changes. */
enum class TallyAlert { OFF, PROGRAM, PROGRAM_AND_PREVIEW }

/** Everything the settings sheet edits; saved as soon as it changes. */
data class Preferences(
    val audio: Boolean = true,
    val controls: ControlsMode = ControlsMode.AUTO,
    val stopScreen: StopScreen = StopScreen.OFFLINE,
    val framingGrid: FramingGrid = FramingGrid.OFF,
    val screenPower: ScreenPower = ScreenPower.NORMAL,
    val tallyAlert: TallyAlert = TallyAlert.PROGRAM_AND_PREVIEW,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
)

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

    var preferences: Preferences
        get() = Preferences(
            audio = prefs.getBoolean("audio", true),
            controls = enumOf("controls", ControlsMode.AUTO),
            stopScreen = enumOf("stop_screen", StopScreen.OFFLINE),
            framingGrid = enumOf("framing_grid", FramingGrid.OFF),
            screenPower = enumOf("screen_power", ScreenPower.NORMAL),
            tallyAlert = enumOf("tally_alert", TallyAlert.PROGRAM_AND_PREVIEW),
            brightness = prefs.getFloat("brightness", 0f).coerceIn(-1f, 1f),
            contrast = prefs.getFloat("contrast", 1f).coerceIn(0.5f, 1.5f),
            saturation = prefs.getFloat("saturation", 1f).coerceIn(0f, 2f),
        )
        set(v) {
            prefs.edit()
                .putBoolean("audio", v.audio)
                .putString("controls", v.controls.name)
                .putString("stop_screen", v.stopScreen.name)
                .putString("framing_grid", v.framingGrid.name)
                .putString("screen_power", v.screenPower.name)
                .putString("tally_alert", v.tallyAlert.name)
                .putFloat("brightness", v.brightness.coerceIn(-1f, 1f))
                .putFloat("contrast", v.contrast.coerceIn(0.5f, 1.5f))
                .putFloat("saturation", v.saturation.coerceIn(0f, 2f))
                .apply()
        }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == prefs.getString(key, default.name) } ?: default

    private companion object {
        const val KEY_CAMERA = "camera_id"
        const val KEY_MIRROR = "mirror"
        const val KEY_WELCOME = "welcome_seen"
    }
}
