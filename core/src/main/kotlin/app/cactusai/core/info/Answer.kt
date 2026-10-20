package app.cactusai.core.info

/** Where an answer came from. Shown to the user so an online answer is never mistaken for a local one. */
enum class AnswerOrigin { LOCAL, ONLINE }

/** A web page an online answer is based on. Treated as untrusted text: it is shown and opened by the user, never acted on. */
data class Source(val title: String, val url: String, val snippet: String? = null) {
    /** `espn.com` for `https://www.espn.com/nfl/...`, so the user can tell where it came from at a glance. */
    val host: String get() = hostOf(url)

    companion object {
        fun hostOf(url: String): String =
            url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#').removePrefix("www.").lowercase()
    }
}

/**
 * A finished answer for the screen and the speaker.
 *
 * [text] is shown; [spoken] is read aloud (shorter, no symbols or links). [note] carries a caution such as "could not be
 * verified" that must be shown, and spoken, together with the answer.
 */
data class InfoAnswer(
    val text: String,
    val spoken: String = text,
    val origin: AnswerOrigin = AnswerOrigin.LOCAL,
    val sources: List<Source> = emptyList(),
    /** When online information was fetched, in epoch milliseconds. Null for local answers. */
    val retrievedAtMs: Long? = null,
    val note: String? = null,
    /** True only when every fact in [text] was confirmed by the rules in docs/ASSISTANT_INFO.md. */
    val verified: Boolean = true,
)
