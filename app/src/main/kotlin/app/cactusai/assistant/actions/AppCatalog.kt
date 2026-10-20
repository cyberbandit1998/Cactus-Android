package app.cactusai.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

data class InstalledApp(val label: String, val packageName: String)

/** Launchable apps, via PackageManager and the MAIN/LAUNCHER `<queries>` entry in the manifest. */
class AppCatalog(private val context: Context) {
    @Volatile
    private var cache: Pair<Long, List<InstalledApp>>? = null

    fun launchable(): List<InstalledApp> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - it.first < CACHE_MS) return it.second }
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = queryLaunchers(pm, query)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) null else InstalledApp(info.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cache = now to apps
        return apps
    }

    fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Short app names for Whistle's keyword option, one per line, so "Spotify" is spelled as installed. */
    fun speechKeywords(max: Int = 30): String? =
        launchable().map { it.label }.filter { it.length in 3..24 }.take(max).joinToString("\n").ifBlank { null }

    @Suppress("DEPRECATION")
    private fun queryLaunchers(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    private companion object {
        const val CACHE_MS = 10_000L
    }
}
