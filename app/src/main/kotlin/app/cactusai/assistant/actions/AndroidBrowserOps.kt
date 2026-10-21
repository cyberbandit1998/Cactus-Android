package app.cactusai.assistant.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.cactusai.assistant.a11y.ScreenControl
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.core.ask.BrowserOps
import app.cactusai.core.ask.PageSnapshot

/**
 * The Android side of [app.cactusai.core.ask.BrowserSearch]: open the user's own browser on the address it was given, read the
 * window in front through the Accessibility service, and ask the service to bring Cactus AI back. It talks to no server of its
 * own; the only thing that leaves the phone is the search the browser itself makes.
 */
class AndroidBrowserOps(
    private val context: Context,
    private val network: NetworkMonitor,
) : BrowserOps {
    override fun open(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override val canRead: Boolean get() = ScreenControl.isAvailable

    override val isOnline: Boolean get() = network.isOnline()

    override suspend fun readPage(): PageSnapshot? = ScreenControl.controller?.readPage()

    override suspend fun returnToApp(): Boolean = ScreenControl.controller?.returnToApp() ?: false
}
