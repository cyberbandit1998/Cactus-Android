package app.cactusai.core.ask

import app.cactusai.core.web.ParsedPage
import app.cactusai.core.web.ReadNode
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.SearchPageParser
import app.cactusai.core.web.SearchSite
import app.cactusai.core.web.UntrustedText
import app.cactusai.core.web.WebFailure
import app.cactusai.core.web.WebSearchProvider
import java.time.Clock
import kotlinx.coroutines.delay

/** What the Accessibility service saw in the window in front: whose it is and the text of the page, in reading order. */
data class PageSnapshot(val packageName: String?, val nodes: List<ReadNode>)

/** The Android parts of reading a search page, behind an interface so the waiting and fallback rules can be tested. */
interface BrowserOps {
    /** Opens [url] in the user's browser. False when no app can show it. */
    fun open(url: String): Boolean

    /** True while the Accessibility service is connected and can read other apps' windows. */
    val canRead: Boolean

    /** The page in front right now, or null when nothing readable is there. */
    suspend fun readPage(): PageSnapshot?

    /** Brings Cactus AI back to the front, best effort. */
    suspend fun returnToApp(): Boolean
}

/**
 * Search without any service of ours: open Google's results page in the user's own browser on a fixed address, wait for it to
 * load, read the visible text through the Accessibility service, and bring Cactus AI back to the front. If the page cannot be
 * read, it stays on screen for the user and the answer says why.
 *
 * Only the typed or spoken words go into the address; nothing else leaves the phone, and no audio ever does.
 */
class BrowserSearch(
    private val ops: BrowserOps,
    private val ownPackage: String,
    private val clock: Clock,
    private val pollMs: Long = 600,
    private val firstReadDelayMs: Long = 1_200,
    private val timeoutMs: Long = 14_000,
) : WebSearchProvider {
    override val name: String = "your browser's Google results"

    override suspend fun search(query: String, maxResults: Int): SearchOutcome {
        val q = UntrustedText.clean(query, 200)
        if (q.isBlank()) return SearchOutcome.Failed(WebFailure.BAD_REQUEST, "There is nothing to search for.")
        val url = SearchSite.GOOGLE.searchUrl(q)
        if (!ops.open(url)) return SearchOutcome.Failed(WebFailure.NO_BROWSER, "I couldn't open a web browser to search.")
        if (!ops.canRead) {
            return SearchOutcome.Failed(
                WebFailure.ACCESSIBILITY_OFF,
                "I opened the results, but I can't read them aloud until you turn on the Accessibility service in Settings.",
                browserShowsResults = true,
            )
        }

        var waited = 0L
        var best: ParsedPage? = null
        var sawBrowser = false
        delay(firstReadDelayMs)
        waited += firstReadDelayMs
        while (true) {
            val snapshot = ops.readPage()
            if (snapshot != null && snapshot.packageName != ownPackage) {
                sawBrowser = true
                val parsed = SearchPageParser.parse(snapshot.nodes, query = q)
                if (parsed.results.isNotEmpty() || parsed.featured != null || parsed.header != null) {
                    // the page keeps filling in while it loads: done when two reads in a row agree
                    val stable = best != null && best.results.map { it.url } == parsed.results.map { it.url } && best.featured == parsed.featured
                    best = parsed
                    if (stable) break
                }
            }
            if (waited >= timeoutMs) break
            delay(pollMs)
            waited += pollMs
        }

        val page = best
        if (page == null) {
            val message = if (sawBrowser) {
                "The results are showing, but the page doesn't share its text with me, so I can't read them aloud."
            } else {
                "I couldn't read the results page in time. It is still on your screen."
            }
            return SearchOutcome.Failed(WebFailure.NOT_READABLE, message, browserShowsResults = true)
        }
        ops.returnToApp()
        return SearchOutcome.Results(page.results.take(maxResults), clock.millis(), page.featured, page.header, url)
    }
}
