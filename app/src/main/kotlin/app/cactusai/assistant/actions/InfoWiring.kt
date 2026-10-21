package app.cactusai.assistant.actions

import android.content.Context
import android.text.format.DateFormat
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.core.ask.BrowserSearch
import app.cactusai.core.ask.InfoExecutor
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The phone's clock, read each time: the time zone follows the phone if it changes while the app is running. */
object LiveClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
}

/** Builds the information executor from the phone's own services. One place, so the app and the tests wire it the same way. */
object InfoWiring {
    fun create(context: Context, settings: AppSettings, network: NetworkMonitor, device: DeviceActions): InfoExecutor {
        val app = context.applicationContext
        val search = BrowserSearch(AndroidBrowserOps(app, network), app.packageName, LiveClock)
        return InfoExecutor(
            clock = LiveClock,
            locale = { Locale.getDefault() },
            device = { device.snapshot() },
            // The 12 or 24 hour clock follows the phone's own setting.
            settings = { settings.current.assistant.infoSettings().copy(use24Hour = DateFormat.is24HourFormat(app)) },
            web = { search },
        )
    }
}
