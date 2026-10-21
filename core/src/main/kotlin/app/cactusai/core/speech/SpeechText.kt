package app.cactusai.core.speech

import app.cactusai.core.web.UntrustedText

/** Turns answer text into text that sounds right when read by a speech engine, in pieces the engine accepts. */
object SpeechText {
    private val bullets = Regex("""(?m)^\s*(?:\d+\.|[-•*])\s+""")
    private val domain = Regex("""\b([a-z0-9-]+(?:\.[a-z0-9-]+)*)\.(com|org|net|gov|edu|io|co|tv|us|uk)\b""", RegexOption.IGNORE_CASE)
    private val scoreDash = Regex("""(\d)\s?[–-]\s?(\d)""")
    private val spaces = Regex("""[ \t]+""")

    /**
     * One string for the speaker: no addresses, no markup, symbols said as words, at most [maxChars] characters (cut at a sentence end),
     * so a long page is never read out in full.
     */
    fun clean(raw: String, maxChars: Int = 900): String {
        // each line is cleaned on its own so a list read aloud keeps its pauses
        val lines = raw.lines().map { UntrustedText.forSpeech(bullets.replace(it, ""), maxChars * 3) }.filter { it.isNotBlank() }
        var s = lines.mapIndexed { i, line -> if (i < lines.lastIndex && line.last() !in ".!?:;…") "$line." else line }.joinToString(" ")
        s = domain.replace(s) { m -> "${m.groupValues[1].replace(".", " dot ")} dot ${m.groupValues[2]}" }
        s = scoreDash.replace(s) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" }
        s = s.replace("°F", " degrees Fahrenheit").replace("°C", " degrees Celsius").replace("°", " degrees")
            .replace("%", " percent").replace("&", " and ").replace("“", "").replace("”", "").replace("…", "...")
        s = s.replace(Regex("""\.\s*\.+"""), ".").replace(spaces, " ").trim()
        if (s.length <= maxChars) return s
        val cut = s.take(maxChars)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return (if (end > maxChars / 2) cut.take(end + 1) else cut.trimEnd() + "…").trim()
    }

    /** Splits [text] into pieces of at most [limit] characters, ending at a sentence or word boundary, in order. */
    fun chunks(text: String, limit: Int): List<String> {
        require(limit >= 20) { "limit too small" }
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val window = rest.take(limit)
            var cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ':')).let { if (it >= limit / 3) it + 1 else -1 }
            if (cut < 0) cut = window.lastIndexOf(' ').let { if (it > 0) it else limit }
            out += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }
}

/** The user's speech settings, kept in range whatever was stored. */
data class SpeechSettings(
    val enabled: Boolean = true,
    /** Speak information answers as soon as they are ready. */
    val autoSpeak: Boolean = true,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** The installed voice's name, or null for the system default. */
    val voiceName: String? = null,
) {
    val safeRate: Float get() = rate.coerceIn(MIN_RATE, MAX_RATE)
    val safePitch: Float get() = pitch.coerceIn(MIN_PITCH, MAX_PITCH)

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}
