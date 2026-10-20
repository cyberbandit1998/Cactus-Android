package app.cactusai.assistant.a11y

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    /** True when the user has switched the service on in Android settings (it may take a moment to connect). */
    fun isEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val mine = ComponentName(context, CactusAccessibilityService::class.java).flattenToString()
        val short = ComponentName(context, CactusAccessibilityService::class.java).flattenToShortString()
        return enabled.split(':').any { it.equals(mine, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    /** True while the service is connected and ready to act. */
    val isConnected: Boolean get() = ScreenControl.isAvailable
}
