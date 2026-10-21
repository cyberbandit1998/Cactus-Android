package app.cactusai.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import app.cactusai.core.info.DeviceSnapshot
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

    /**
     * The facts the information tools answer from, read from public Android APIs that need no permission: the battery broadcast,
     * [Build] and the free space of the app's own storage volume. A value Android does not give is left null, never guessed.
     */
    fun snapshot(): DeviceSnapshot {
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var percent: Int? = null
        var charging: Boolean? = null
        var source: String? = null
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) percent = (level * 100f / scale).roundToInt()
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status != -1) charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            source = when (sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> null
            }
        }
        var free: Long? = null
        var total: Long? = null
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            free = stat.availableBytes
            total = stat.totalBytes
        } catch (_: IllegalArgumentException) {
        }
        return DeviceSnapshot(
            batteryPercent = percent,
            charging = charging,
            chargingSource = if (charging == true) source else null,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            storageFreeBytes = free,
            storageTotalBytes = total,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
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
