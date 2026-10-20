package app.cactusai.core.metrics

/**
 * Wall-clock timings measured on the device, one field per stage, never estimated.
 * Engine-reported figures (time to first token, tokens per second) are kept apart from our own clock.
 */
data class Timings(
    /** Time spent loading a model into the engine (first use after the app starts). */
    val modelLoadMs: Long? = null,
    val recordMs: Long? = null,
    val transcribeMs: Long? = null,
    val interpretMs: Long? = null,
    val executeMs: Long? = null,
    /** From the end of recording (or the typed command) to the action finishing. */
    val totalMs: Long? = null,
    val whistleTtftMs: Double? = null,
    val whistleDecodeTps: Double? = null,
    val needlePrefillTps: Double? = null,
    val needleDecodeTps: Double? = null,
) {
    fun summary(): String = listOfNotNull(
        modelLoadMs?.let { "load $it ms" },
        transcribeMs?.let { "speech $it ms" },
        interpretMs?.let { "command $it ms" },
        executeMs?.let { "action $it ms" },
        totalMs?.let { "total $it ms" },
    ).joinToString(" · ")
}

/** Small stopwatch around a block so every stage is timed the same way. */
inline fun <T> timed(onElapsedMs: (Long) -> Unit, block: () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        onElapsedMs((System.nanoTime() - start) / 1_000_000)
    }
}
