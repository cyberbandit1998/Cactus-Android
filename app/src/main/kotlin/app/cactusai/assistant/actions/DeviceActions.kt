package app.cactusai.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import kotlin.math.roundToInt

/** Battery and media-volume actions that need no special permission. */
class DeviceActions(private val context: Context) {

    fun batteryStatus(): String {
        val manager = context.getSystemService(BatteryManager::class.java)
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (percent <= 0 && sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) percent = (level * 100f / scale).roundToInt()
        }
        if (percent < 0) return "Android did not report a battery level."
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at $percent%" + when {
            full -> " and fully charged."
            charging -> " and charging."
            else -> "."
        }
    }

    /** Changes the media volume. Throws SecurityException if Android refuses (for example Do Not Disturb rules). */
    fun changeVolume(action: String, level: Int?): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "This phone has no audio manager."
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        when (action) {
            "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> audio.setStreamVolume(stream, ((level ?: 0) * max / 100.0).roundToInt().coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        }
        if (audio.isStreamMute(stream)) return "Media volume is muted."
        val now = (audio.getStreamVolume(stream) * 100f / max).roundToInt()
        return "Media volume is now $now%."
    }
}
