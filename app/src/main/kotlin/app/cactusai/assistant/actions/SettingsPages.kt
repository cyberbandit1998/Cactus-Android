package app.cactusai.assistant.actions

import android.content.Intent
import android.provider.Settings

/** The fixed set of Android settings screens the assistant can open. Intent actions are framework constants. */
object SettingsPages {
    fun intentFor(page: String): Intent {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "airplane_mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun label(page: String): String = when (page) {
        "wifi" -> "Wi-Fi settings"
        "bluetooth" -> "Bluetooth settings"
        "display" -> "Display settings"
        "battery" -> "Battery settings"
        "sound" -> "Sound settings"
        "location" -> "Location settings"
        "apps" -> "App settings"
        "storage" -> "Storage settings"
        "airplane_mode" -> "Airplane mode settings"
        "nfc" -> "NFC settings"
        "date_time" -> "Date and time settings"
        "accessibility" -> "Accessibility settings"
        else -> "Android settings"
    }
}
