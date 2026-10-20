package app.cactusai.assistant

import android.os.PowerManager
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.assistant.ui.screens.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFormatTest {
    @Test
    fun bytesAreShownInHumanUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("17.0 MB", formatBytes(16_950_000))
        assertEquals("35.0 MB", formatBytes(35_000_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
    }

    @Test
    fun thermalPolicyOnlyPausesWhenThePhoneIsCritical() {
        assertFalse(ThermalMonitor.shouldPause(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalMonitor.shouldPause(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalMonitor.shouldWarn(PowerManager.THERMAL_STATUS_SEVERE))
        assertFalse(ThermalMonitor.shouldWarn(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("Hot", ThermalMonitor.label(PowerManager.THERMAL_STATUS_SEVERE))
    }
}
