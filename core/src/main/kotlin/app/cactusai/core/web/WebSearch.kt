package app.cactusai.core.web

/** One search hit as the browser showed it, already cleaned. The text is the page's own words and is untrusted. */
data class WebResult(val title: String, val url: String, val content: String) {
    val host: String get() = app.cactusai.core.info.Source.hostOf(url)
}

enum class WebFailure {
    /** The user switched online lookups off in Settings. */
    DISABLED,

    /** The Accessibility service is off, so the page can be opened but not read. */
    ACCESSIBILITY_OFF,

    /** The browser showed a page that exposes no readable results (or none that could be told from menus and ads). */
    NOT_READABLE,
    TIMEOUT,
    NO_BROWSER,
    BAD_REQUEST,
}

sealed interface SearchOutcome {
    /**
     * [featured] is the highlighted answer box at the top of the page, if any; [header] is the other text above the first result
     * (a live scoreboard card, for example). [pageUrl] is the search page the text was read from.
     */
    data class Results(
        val results: List<WebResult>,
        val retrievedAtMs: Long,
        val featured: String? = null,
        val header: String? = null,
        val pageUrl: String? = null,
    ) : SearchOutcome

    /** Nothing could be read. [browserShowsResults] is true when the search page is open and visible anyway. */
    data class Failed(val kind: WebFailure, val message: String, val browserShowsResults: Boolean = false) : SearchOutcome
}

/**
 * Where search results come from. In the app this opens the user's browser on a fixed search address and reads the result page
 * through the Accessibility service; nothing is sent to any other service and no key is involved. Implementations must never
 * pass what they read to the command model or to an action.
 */
interface WebSearchProvider {
    val name: String

    suspend fun search(query: String, maxResults: Int = 5): SearchOutcome
}
