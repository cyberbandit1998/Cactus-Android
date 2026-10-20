package app.cactusai.core.web

/**
 * Lines that must never be read aloud or shown from a page, however the page came to contain them: contact details, long
 * numbers that could be cards or accounts, credentials, and the page's idea of where the user is.
 */
object SensitiveText {
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?:\+?\d[\s().-]{0,2}){10,}""")
    private val longDigits = Regex("""\b\d{9,}\b""")
    private val cardLike = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val ssn = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val words = Regex(
        """\b(password|passcode|one[- ]time code|verification code|security code|cvv|cvc|api key|secret key|access token|bearer|account number|routing number|iban|your location|precise location|from your internet address|signed in as|your account|sign in to|log in to)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun looksSensitive(text: String): Boolean =
        email.containsMatchIn(text) || phone.containsMatchIn(text) || longDigits.containsMatchIn(text) || cardLike.containsMatchIn(text) ||
            ssn.containsMatchIn(text) || words.containsMatchIn(text)
}
