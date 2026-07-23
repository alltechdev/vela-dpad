package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Driving-alert toggles (Settings -> Navigation -> Driving alerts), both OFF by default and both
 * Android Auto-side today: [cameras] speaks a heads-up approaching a mapped license-plate camera
 * (the bundled DeFlock dataset - the same one the map layer and route re-rank read); [speeding]
 * turns the car speed badge red when driving over the posted limit. Process-wide reactive holder,
 * same shape as [Units]/[VoiceSearch]; `init()`-ed in [app.vela.VelaApp].
 */
object DriveAlerts {
    val cameras = mutableStateOf(false)
    val speeding = mutableStateOf(false)

    fun init(context: Context) {
        cameras.value = prefs(context).getBoolean(KEY_CAMERAS, false)
        speeding.value = prefs(context).getBoolean(KEY_SPEEDING, false)
    }

    fun setCameras(context: Context, value: Boolean) {
        cameras.value = value
        prefs(context).edit().putBoolean(KEY_CAMERAS, value).apply()
    }

    fun setSpeeding(context: Context, value: Boolean) {
        speeding.value = value
        prefs(context).edit().putBoolean(KEY_SPEEDING, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_CAMERAS = "drive_alert_cameras"
    private const val KEY_SPEEDING = "drive_alert_speeding"
}
