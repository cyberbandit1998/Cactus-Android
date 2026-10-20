package app.cactusai.assistant.system

import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches Android's thermal status so inference can back off instead of making a hot phone hotter. */
class ThermalMonitor(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<Int> = _status.asStateFlow()

    init {
        try {
            powerManager?.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { _status.value = it }
        } catch (_: RuntimeException) {
            // Some builds do not report thermal status; the app then simply shows "unknown".
        }
    }

    private fun read(): Int = try {
        powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    } catch (_: RuntimeException) {
        PowerManager.THERMAL_STATUS_NONE
    }

    companion object {
        fun label(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Slightly warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE -> "Hot"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Very hot"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Overheating"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
            else -> "Unknown"
        }

        /** At this level new inference is postponed; the phone is already throttling. */
        fun shouldPause(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_CRITICAL

        fun shouldWarn(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
