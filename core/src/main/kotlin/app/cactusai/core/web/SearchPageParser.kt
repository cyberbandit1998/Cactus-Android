package app.cactusai.core.web

import app.cactusai.core.info.Source

/**
 * One piece of text from the browser's window, in reading order, as the Accessibility service saw it. Only text the page
 * itself exposes: nothing is guessed, nothing from password or payment fields is ever included.
 */
data class ReadNode(
    val text: String,
    /** The page marks it as a heading (a result title is one). */
    val heading: Boolean = false,
    /** The address the link goes to, when the browser exposes it. */
    val link: String? = null,
    val clickable: Boolean = false,
)

data class ParsedPage(
    val featured: String?,
    val header: String?,
    val results: List<WebResult>,
    /** How many advertisements, menus and sections were left out, for the details view. */
    val skipped: Int,
)

/**
 * Turns the flat text of a search-result page into titles, snippets and web addresses, leaving out what a person would not
 * want read to them: advertisements, menus and filters, "people also ask" and "related searches" boxes, cookie notices, and
 * anything that looks like contact or account details.
 *
 * This works on the text the browser exposes. How a search engine lays its page out changes without notice, so the rules are
 * written to leave something out rather than read the wrong thing; whether they hold on the real page is checked on the phone
 * (docs/DEVICE_TEST_CHECKLIST.md). When nothing usable is found the caller says reading is unavailable.
 */
object SearchPageParser {
    private val menuWords = setOf(
        "all", "images", "videos", "news", "shopping", "maps", "books", "flights", "finance", "more", "tools", "settings", "sign in", "web", "short videos",
        "forums", "search", "filters and topics", "search modes", "skip to main content", "accessibility links", "accessibility help", "send feedback",
        "privacy", "terms", "next", "previous", "page navigation", "cached", "translate this page", "more results", "search results", "web results",
        "ai mode", "tools and filters", "past hour", "past 24 hours", "past week", "any time", "verbatim", "clear",
    )
    private val adMarkers = Regex("""^(ads?|sponsored|sponsored results?|ad ·.*|ads ·.*|sponsored ·.*)$""", RegexOption.IGNORE_CASE)
    private val skipSections = Regex(
        """^(people also ask|related searches|searches related to.*|top stories|videos|short videos|images for.*|things to know|related questions|more to ask|discussions and forums|from sources across the web|more places|places|find results on|explore more.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val featuredMarker = Regex("""^(featured snippet from the web|featured snippet|about featured snippets?)$""", RegexOption.IGNORE_CASE)
    private val consent = Regex("""cookie|consent|before you continue|we use|accept all|reject all|privacy reminder|your privacy""", RegexOption.IGNORE_CASE)
    private val breadcrumb = Regex("""^(?:https?://)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)(?:\s*[›»>/].*)?$""", RegexOption.IGNORE_CASE)

    /**
     * [query] is what was searched for: the search box shows it back, and that echo is not a result. When the page marks no
     * headings at all, links with a readable text stand in for them.
     */
    fun parse(rawNodes: List<ReadNode>, maxResults: Int = 8, query: String? = null): ParsedPage {
        val echo = query?.let { UntrustedText.clean(it, 300).lowercase() }
        val nodes = if (rawNodes.any { it.heading }) {
            rawNodes
        } else {
            rawNodes.map { n -> if (n.link != null && n.text.length in 10..140 && n.text.lowercase() !in menuWords) n.copy(heading = true) else n }
        }
        var skipped = 0
        var featured: String? = null
        val header = StringBuilder()
        val results = ArrayList<WebResult>()

        var title: String? = null
        var link: String? = null
        var crumbHost: String? = null
        val snippet = ArrayList<String>()
        var inAd = false
        var inSkippedSection = false
        var expectFeatured = 0

        fun flush() {
            val t = title ?: return
            val url = UntrustedText.safeUrl(link) ?: crumbHost?.let { UntrustedText.safeUrl("https://$it/") }
            val body = UntrustedText.clean(snippet.joinToString(" "), 400)
            title = null
            link = null
            crumbHost = null
            snippet.clear()
            if (url == null) {
                skipped++
                return
            }
            if (Source.hostOf(url).let { it == "google.com" || it.endsWith(".google.com") }) {
                skipped++
                return
            }
            if (results.none { it.url == url }) results += WebResult(UntrustedText.clean(t, 160), url, body)
        }

        for (node in nodes) {
            val raw = UntrustedText.clean(node.text, 400)
            if (raw.isEmpty()) continue
            val lower = raw.lowercase()
            if (SensitiveText.looksSensitive(raw)) {
                skipped++
                continue
            }
            if (consent.containsMatchIn(raw) && raw.length > 25) {
                skipped++
                continue
            }
            if (echo != null && lower == echo) continue
            if (lower in menuWords) {
                skipped++
                continue
            }
            if (adMarkers.matches(raw)) {
                flush()
                inAd = true
                skipped++
                continue
            }
            if (skipSections.matches(raw)) {
                flush()
                inSkippedSection = true
                inAd = false
                skipped++
                continue
            }
            if (featuredMarker.matches(raw)) {
                flush()
                expectFeatured = 3
                inAd = false
                inSkippedSection = false
                continue
            }

            if (node.heading) {
                flush()
                inSkippedSection = false
                if (expectFeatured > 0) expectFeatured = 0
                // a heading right after an ad marker is the ad's title
                if (inAd) {
                    inAd = false
                    skipped++
                    continue
                }
                title = raw
                link = node.link
                continue
            }

            if (inAd || inSkippedSection) {
                skipped++
                continue
            }
            if (expectFeatured > 0) {
                expectFeatured--
                featured = listOfNotNull(featured, raw).joinToString(" ").take(500)
                continue
            }
            if (title == null) {
                // text above the first result: a scoreboard, a weather card, a calculator box
                if (results.isEmpty() && header.length < 400) header.append(if (header.isEmpty()) raw else ". $raw")
                continue
            }
            if (link == null && node.link != null) link = node.link
            val crumb = breadcrumb.matchEntire(raw)
            if (crumb != null && crumbHost == null && snippet.isEmpty()) {
                crumbHost = crumb.groupValues[1].lowercase()
                continue
            }
            snippet += raw
        }
        flush()

        return ParsedPage(
            featured = featured?.let { UntrustedText.clean(it, 500) }?.ifBlank { null },
            header = header.toString().let { UntrustedText.clean(it, 400) }.ifBlank { null },
            results = results.take(maxResults),
            skipped = skipped,
        )
    }
}
