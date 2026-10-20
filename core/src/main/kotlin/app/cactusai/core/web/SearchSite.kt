package app.cactusai.core.web

import app.cactusai.core.match.TextNorm
import java.net.URLEncoder

/**
 * The only web destinations the assistant will open. The model picks a [key]; the URL is always built
 * here, over https, from a fixed host, with the query percent-encoded. A model can never supply a URL.
 */
enum class SearchSite(
    val key: String,
    val label: String,
    /** Normalised spoken names, used when "open Reddit" has no installed app to fall back on. */
    val spokenNames: List<String>,
    val androidPackage: String?,
    private val searchBase: String,
    val homeUrl: String,
) {
    GOOGLE("google", "Google", listOf("google", "google search"), "com.google.android.googlequicksearchbox",
        "https://www.google.com/search?q=", "https://www.google.com/"),
    YOUTUBE("youtube", "YouTube", listOf("youtube", "you tube"), "com.google.android.youtube",
        "https://www.youtube.com/results?search_query=", "https://www.youtube.com/"),
    REDDIT("reddit", "Reddit", listOf("reddit"), "com.reddit.frontpage",
        "https://www.reddit.com/search/?q=", "https://www.reddit.com/"),
    WIKIPEDIA("wikipedia", "Wikipedia", listOf("wikipedia", "wiki"), null,
        "https://en.wikipedia.org/w/index.php?search=", "https://en.wikipedia.org/"),
    MAPS("maps", "Google Maps", listOf("maps", "google maps"), "com.google.android.apps.maps",
        "https://www.google.com/maps/search/?api=1&query=", "https://www.google.com/maps");

    fun searchUrl(query: String): String = searchBase + URLEncoder.encode(query, Charsets.UTF_8.name())

    companion object {
        fun fromKey(key: String?): SearchSite =
            entries.firstOrNull { it.key == key?.lowercase() } ?: GOOGLE

        /** "reddit" -> REDDIT. Only an exact spoken-name match counts, so arbitrary words never become sites. */
        fun fromSpokenName(name: String): SearchSite? {
            val n = TextNorm.normalize(name)
            return entries.firstOrNull { site -> site.spokenNames.any { it == n } }
        }
    }
}
