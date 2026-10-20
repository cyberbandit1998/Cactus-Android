package app.cactusai.core.exec

import app.cactusai.core.exec.TargetSafety.Target
import app.cactusai.core.exec.TargetSafety.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSafetyTest {
    private fun tap(label: String?, id: String? = null, near: List<String> = emptyList(), pkg: String? = "com.spotify.music", sensitive: Boolean = false) =
        TargetSafety.assessTap(Target(label, id, near, pkg, sensitive)).verdict

    @Test
    fun paymentDeletionSubmissionAndSimilarControlsNeedApproval() {
        for (label in listOf(
            "Pay now", "Buy", "Buy now", "Purchase", "Place order", "Delete", "Delete photo", "Remove", "Send", "Submit", "Confirm payment",
            "Transfer", "Checkout", "Subscribe", "Install", "Uninstall", "Allow", "Grant permission", "Sign out", "Log out", "Factory reset",
            "Erase all data", "Add card", "Withdraw", "Donate", "Start free trial", "Authorize", "Approve",
        )) {
            assertEquals(label, Verdict.NEEDS_APPROVAL, tap(label))
        }
    }

    @Test
    fun everydayControlsAreSafe() {
        for (label in listOf("Search", "Settings", "Home", "Play", "Pause", "Profile", "Share", "Add to playlist", "Display settings", "Back", "Scroll", "Sender name", "Spaying")) {
            assertEquals(label, Verdict.SAFE, tap(label))
        }
    }

    @Test
    fun wholeWordsAreMatchedSoSimilarWordsAreNotFlagged() {
        assertFalse(TargetSafety.isRisky("Display"))
        assertFalse(TargetSafety.isRisky("Sender"))
        assertFalse(TargetSafety.isRisky("Payment history"))
        assertTrue(TargetSafety.isRisky("PAY"))
        assertTrue(TargetSafety.isRisky("Pay-now"))
    }

    @Test
    fun anIconOnlyButtonIsJudgedByItsId() {
        assertEquals(Verdict.NEEDS_APPROVAL, tap(null, "com.chat:id/send_button"))
        assertEquals(Verdict.NEEDS_APPROVAL, tap(null, "com.shop:id/btn_buy_now"))
        assertEquals(Verdict.SAFE, tap(null, "com.app:id/search_button"))
    }

    @Test
    fun aGenericOkTakesItsMeaningFromTheQuestionBesideIt() {
        assertEquals(Verdict.NEEDS_APPROVAL, tap("OK", near = listOf("Delete this photo?", "This cannot be undone")))
        assertEquals(Verdict.NEEDS_APPROVAL, tap("Continue", near = listOf("Pay $49.99 with Visa ending 4242")))
        assertEquals(Verdict.NEEDS_APPROVAL, tap("Yes", near = listOf("Allow Maps to access your location?")))
        assertEquals(Verdict.NEEDS_APPROVAL, tap(null, near = listOf("Uninstall this app?")))
        assertEquals(Verdict.SAFE, tap("OK", near = listOf("Rate this app?")))
        assertEquals(Verdict.SAFE, tap("OK"))
        // a specific, harmless label is not judged by what surrounds it
        assertEquals(Verdict.SAFE, tap("Play", near = listOf("Delete playlist")))
    }

    @Test
    fun passwordAndPaymentFieldsAreNeverTouched() {
        assertEquals(Verdict.BLOCKED, tap("Password", sensitive = true))
        assertEquals(Verdict.BLOCKED, tap("Search", sensitive = true))
    }

    @Test
    fun androidsPermissionAndInstallerScreensAreNeverDriven() {
        for (pkg in listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.google.android.packageinstaller", "com.android.packageinstaller")) {
            assertEquals(pkg, Verdict.BLOCKED, tap("Next", pkg = pkg))
            assertTrue(TargetSafety.isProtectedPackage(pkg))
        }
        assertFalse(TargetSafety.isProtectedPackage("com.spotify.music"))
        assertFalse(TargetSafety.isProtectedPackage(null))
    }

    @Test
    fun anythingInAPaymentOrBankingAppNeedsApproval() {
        for (pkg in listOf("com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile", "com.venmo", "com.chase.bank.mobile", "com.bankofamerica.mobile", "com.squareup.cash.payments")) {
            assertTrue(pkg, TargetSafety.looksFinancial(pkg))
            assertEquals(pkg, Verdict.NEEDS_APPROVAL, tap("Home", pkg = pkg))
        }
        for (pkg in listOf("com.android.chrome", "com.spotify.music", "com.google.android.apps.maps", null)) {
            assertFalse(pkg ?: "null", TargetSafety.looksFinancial(pkg))
        }
    }

    @Test
    fun theRequestedLabelIsCheckedBeforeAnythingIsLookedAt() {
        assertEquals(Verdict.NEEDS_APPROVAL, TargetSafety.assessRequestedLabel("pay now").verdict)
        assertEquals(Verdict.SAFE, TargetSafety.assessRequestedLabel("search").verdict)
        assertTrue(TargetSafety.assessRequestedLabel("Delete everything").reason!!.contains("hard to undo"))
    }

    @Test
    fun sensitiveFieldHintsAreRecognised() {
        for (hint in listOf("Password", "Enter your passcode", "Card number", "Security code (CVV)", "PIN code", "Expiry date")) {
            assertTrue(hint, TargetSafety.isSensitiveFieldHint(hint))
        }
        assertFalse(TargetSafety.isSensitiveFieldHint("Search"))
        assertFalse(TargetSafety.isSensitiveFieldHint(null))
        assertFalse(TargetSafety.isSensitiveFieldHint(""))
    }
}
