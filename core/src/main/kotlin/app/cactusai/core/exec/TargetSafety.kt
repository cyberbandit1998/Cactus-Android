package app.cactusai.core.exec

import app.cactusai.core.match.TextNorm

/**
 * Decides whether the assistant may tap something on the screen by itself. The Accessibility service describes the
 * control it found (its label, where it is, what is written around it) and this object, which has no Android
 * dependency and is tested on its own, says go, ask first, or never.
 *
 * It is deliberately cautious and deliberately simple: a list of words that mean money, deletion, sending, installing,
 * granting access and similar. Words can be missed (a button that only shows an icon, a label in another language), so
 * the user's own confirmation prompt stays the real protection for anything this does not recognise as risky: see
 * docs/ASSISTANT_AND_ACCESSIBILITY.md.
 */
object TargetSafety {
    enum class Verdict { SAFE, NEEDS_APPROVAL, BLOCKED }

    data class Assessment(val verdict: Verdict, val reason: String? = null) {
        val isSafe: Boolean get() = verdict == Verdict.SAFE
    }

    /** What the Accessibility service saw. Every field may be missing; missing never means "safe to assume". */
    data class Target(
        /** The label the user would read: text, content description, hint, or a name made from the view id. */
        val label: String?,
        /** The resource id name, for icon-only buttons such as `send_button`. */
        val viewId: String? = null,
        /** Labels of the parent, its siblings and the dialog title or message the control sits in. */
        val nearbyText: List<String> = emptyList(),
        val packageName: String? = null,
        /** A password, card number or similar field. */
        val sensitiveField: Boolean = false,
    )

    private val riskyWords = setOf(
        "pay", "buy", "purchase", "order", "send", "delete", "remove", "confirm", "transfer", "submit",
        "checkout", "subscribe", "install", "uninstall", "erase", "reset", "withdraw", "donate",
        "allow", "grant", "authorize", "authorise", "approve", "wipe", "format", "forget", "unpair", "deactivate", "revoke",
    )
    private val riskyPhrases = listOf(
        "place order", "sign out", "log out", "factory reset", "clear data", "clear storage", "turn off", "add card", "add payment",
        "in app purchase", "free trial", "bank account", "credit card", "debit card", "security code",
    )

    /** Buttons that carry no meaning of their own: what they do depends on the question next to them. */
    private val genericConfirmWords = setOf("ok", "okay", "yes", "continue", "confirm", "accept", "agree", "proceed", "done", "next", "got it", "sure", "go")

    private val sensitiveFieldWords = listOf(
        "password", "passcode", "pin code", "card number", "credit card", "debit card", "cvv", "cvc", "security code", "expiry",
    )

    private val protectedPackages = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    private val financialPackageParts = listOf("wallet", "paypal", "venmo", "cashapp", "payment", "billing", "checkout", "stripe", "bank", "finance", "zelle")
    private val financialPackageWords = setOf("pay")

    fun isProtectedPackage(packageName: String?): Boolean = packageName != null && packageName in protectedPackages

    fun looksFinancial(packageName: String?): Boolean {
        if (packageName == null) return false
        return packageName.lowercase().split('.', '_', '-').any { token -> token in financialPackageWords || financialPackageParts.any { token.contains(it) } }
    }

    fun isSensitiveFieldHint(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val norm = TextNorm.normalize(text)
        return sensitiveFieldWords.any { norm.contains(it) }
    }

    /** True when the text contains a word or phrase that means money, deletion, sending, installing or granting access. */
    fun isRisky(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val norm = TextNorm.normalize(text)
        if (norm.isEmpty()) return false
        val words = norm.split(' ').toSet()
        return riskyWords.any { it in words } || riskyPhrases.any { norm.contains(it) }
    }

    private fun isGeneric(label: String): Boolean = TextNorm.normalize(label) in genericConfirmWords

    /**
     * The check made before anything is looked at, on the words the user spoke ("tap pay now"). A risky request is
     * confirmed up front; the check on the real control still happens when it is found.
     */
    fun assessRequestedLabel(label: String): Assessment =
        if (isRisky(label)) {
            Assessment(Verdict.NEEDS_APPROVAL, "Tapping “${label.trim().take(40)}” may be hard to undo.")
        } else {
            Assessment(Verdict.SAFE)
        }

    /** The check made on the control that is about to be tapped. */
    fun assessTap(target: Target): Assessment {
        if (target.sensitiveField) {
            return Assessment(Verdict.BLOCKED, "I do not tap or type into password or payment fields. Please do that yourself.")
        }
        if (isProtectedPackage(target.packageName)) {
            return Assessment(Verdict.BLOCKED, "I do not tap inside Android's permission and install screens. Please do that yourself.")
        }
        val shown = target.label?.trim()?.take(40).orEmpty().ifEmpty { "this control" }
        val own = listOfNotNull(target.label, target.viewId?.substringAfterLast('/')?.replace('_', ' '))
        if (own.any(::isRisky)) {
            return Assessment(Verdict.NEEDS_APPROVAL, "Tap “$shown”? This may be hard to undo.")
        }
        // "OK", "Yes" and "Continue" mean whatever the question beside them says.
        val label = target.label
        if ((label == null || isGeneric(label)) && target.nearbyText.any(::isRisky)) {
            return Assessment(Verdict.NEEDS_APPROVAL, "Tap “$shown”? The question around it mentions something that may be hard to undo.")
        }
        if (looksFinancial(target.packageName)) {
            return Assessment(Verdict.NEEDS_APPROVAL, "Tap “$shown” in what looks like a payment or banking app?")
        }
        return Assessment(Verdict.SAFE)
    }
}
