package app.cactusai.core.phone

object PhoneNumbers {
    private val allowed = Regex("^\\+?[0-9][0-9 ().\\-]*$")

    private val digitWords = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
        "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9',
    )

    /**
     * Returns digits (with a leading + when given) or null. Only 3 to 15 ASCII digits and the usual
     * separators are accepted. `*` and `#` are refused on purpose: they start carrier/USSD service codes.
     */
    fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) return null
        val text = fromSpokenDigits(trimmed) ?: trimmed
        if (!allowed.matches(text)) return null
        val digits = text.filter { it in '0'..'9' }
        if (digits.length !in 3..15) return null
        return (if (text.startsWith("+")) "+" else "") + digits
    }

    fun looksLikeNumber(raw: String): Boolean = sanitize(raw) != null

    /** "five five five one two three" -> "5551 23". Null unless every word is a digit word or digit. */
    fun fromSpokenDigits(text: String): String? {
        val tokens = text.lowercase().split(Regex("[\\s,\\-]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val out = StringBuilder()
        var sawWord = false
        for ((index, token) in tokens.withIndex()) {
            when {
                token == "plus" && index == 0 -> out.append('+')
                token in digitWords -> {
                    out.append(digitWords.getValue(token))
                    sawWord = true
                }
                token.all { it in '0'..'9' } -> out.append(token)
                else -> return null
            }
        }
        return if (sawWord) out.toString() else null
    }
}
