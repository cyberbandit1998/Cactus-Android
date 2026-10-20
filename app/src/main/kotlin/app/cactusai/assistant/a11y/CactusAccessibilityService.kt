package app.cactusai.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.cactusai.core.match.NameMatcher
import app.cactusai.core.match.TextNorm
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Optional screen control. Off until the user turns it on in Android's Accessibility settings, and
 * revocable there at any time. It acts only when a command asks for it:
 *  - it reads the screen only to find the thing the user named, and keeps nothing;
 *  - it never types into password or payment fields, and never reads their contents;
 *  - it does not act inside Android's permission and installer screens;
 *  - taps that are hard to undo (pay, send, delete...) wait for the user's OK.
 * Android's own protections (secure windows, authentication) are not bypassed: if the system hides a
 * window from accessibility, this service simply cannot see it.
 */
class CactusAccessibilityService : AccessibilityService(), ScreenController {

    @Volatile
    private var lastChangeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenControl.controller = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScreenControl.controller = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenControl.controller = null
        super.onDestroy()
    }

    /** Only used to notice that the screen changed after an action. The event contents are never read. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastChangeAt = SystemClock.elapsedRealtime()
    }

    override fun onInterrupt() = Unit

    // ---- ScreenController ----

    override suspend fun navigate(destination: String): ScreenResult = withContext(Dispatchers.Default) {
        val action = when (destination) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext ScreenResult.NotFound("I cannot press “$destination”.")
        }
        val before = SystemClock.elapsedRealtime()
        if (performGlobalAction(action)) {
            awaitChange(before)
            ScreenResult.Done(
                when (destination) {
                    "back" -> "Went back."
                    "home" -> "Went to the home screen."
                    "recents" -> "Opened recent apps."
                    else -> "Opened the notification shade."
                },
            )
        } else {
            ScreenResult.NotFound("Android did not let me press $destination.")
        }
    }

    override suspend fun scroll(down: Boolean): ScreenResult = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext hidden()
        blocked(root)?.let { return@withContext it }
        val before = SystemClock.elapsedRealtime()
        val scrollable = visibleNodes(root).filter { it.isScrollable }.maxByOrNull { area(it) }
        if (scrollable != null) {
            val directional = if (down) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            val supportsDirectional = scrollable.actionList.contains(directional)
            val ok = if (supportsDirectional) {
                scrollable.performAction(directional.id)
            } else {
                scrollable.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            return@withContext if (ok) {
                awaitChange(before)
                ScreenResult.Done(if (down) "Scrolled down." else "Scrolled up.")
            } else {
                ScreenResult.NotFound(if (down) "It cannot scroll down any further." else "It cannot scroll up any further.")
            }
        }
        // No scrollable element is exposed by this screen: fall back to a swipe in the middle of it.
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val x = bounds.exactCenterX()
        val from = bounds.top + bounds.height() * (if (down) 0.7f else 0.3f)
        val to = bounds.top + bounds.height() * (if (down) 0.3f else 0.7f)
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        if (gesture(path, 300)) {
            awaitChange(before)
            ScreenResult.Done(if (down) "Swiped to scroll down." else "Swiped to scroll up.")
        } else {
            ScreenResult.NotFound("This screen does not expose anything I can scroll.")
        }
    }

    override suspend fun tapText(text: String): ScreenResult = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext hidden()
        blocked(root)?.let { return@withContext it }
        val query = NameMatcher.cleanQuery(text)
        val scored = visibleNodes(root)
            .filter { !isSensitiveField(it) }
            .mapNotNull { node -> labelOf(node)?.let { label -> Triple(node, label, NameMatcher.score(query, label)) } }
            .filter { it.third >= NameMatcher.MIN_SCORE }
            .sortedByDescending { it.third }
        if (scored.isEmpty()) return@withContext ScreenResult.NotFound("I could not find “$text” on this screen.")

        val best = scored.first().third
        val near = scored.filter { it.third >= best - 0.06 }.distinctBy { it.second to boundsOf(it.first) }.take(4)
        if (near.size > 1) {
            val options = near.map { "${it.second} (${where(it.first)})" }
            return@withContext ScreenResult.Ambiguous("More than one thing on screen matches “$text”. Which one?", options) { index ->
                val chosen = near.getOrNull(index)
                if (chosen == null) ScreenResult.NotFound("That is no longer on the screen.") else clickOrAsk(chosen.first, chosen.second)
            }
        }
        clickOrAsk(near.first().first, near.first().second)
    }

    override suspend fun tapResult(position: Int): ScreenResult = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext hidden()
        blocked(root)?.let { return@withContext it }
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val items = visibleNodes(root)
            .filter { it.isClickable && it.isEnabled && !isSensitiveField(it) }
            .filter { n ->
                val b = boundsOf(n)
                b.height() >= 90 && b.top > screen.top + screen.height() * 0.12f && !(b.width() >= screen.width() * 0.95f && b.height() >= screen.height() * 0.6f)
            }
            .map { it to boundsOf(it) }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .map { it.first }
        val node = items.getOrNull(position - 1)
            ?: return@withContext ScreenResult.NotFound(if (items.isEmpty()) "I cannot see a list of results on this screen." else "I can only see ${items.size} item${if (items.size == 1) "" else "s"} on this screen.")
        clickOrAsk(node, summaryOf(node) ?: "result $position")
    }

    override suspend fun typeText(text: String): ScreenResult = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext hidden()
        blocked(root)?.let { return@withContext it }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        val target = focused ?: visibleNodes(root).firstOrNull { it.isEditable && !isSensitiveField(it) }
            ?: return@withContext ScreenResult.NotFound("I cannot see a text box. Tap one first, for example say “tap search”.")
        if (isSensitiveField(target)) return@withContext ScreenResult.Blocked("I do not type into password or payment fields. Please type that yourself.")

        val before = SystemClock.elapsedRealtime()
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
        val combined = when {
            existing.isEmpty() -> text
            existing.endsWith(" ") -> existing + text
            else -> "$existing $text"
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined) }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            awaitChange(before)
            ScreenResult.Done("Typed “$text”.")
        } else {
            ScreenResult.NotFound("That text box does not accept typed text from an assistant.")
        }
    }

    override suspend fun findOnScreen(text: String): ScreenResult = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext hidden()
        blocked(root)?.let { return@withContext it }
        val query = NameMatcher.cleanQuery(text)
        val hit = visibleNodes(root)
            .filter { !isSensitiveField(it) }
            .mapNotNull { node -> labelOf(node)?.let { it to NameMatcher.score(query, it) } }
            .filter { it.second >= NameMatcher.MIN_SCORE }
            .maxByOrNull { it.second }
        if (hit != null) ScreenResult.Done("Yes, “${hit.first}” is on the screen.") else ScreenResult.NotFound("I cannot see “$text” on this screen.")
    }

    // ---- clicking ----

    private suspend fun clickOrAsk(node: AccessibilityNodeInfo, label: String): ScreenResult {
        if (isRisky(label)) {
            return ScreenResult.NeedsApproval("Tap “$label”? This may be hard to undo.") { click(node, label) }
        }
        return click(node, label)
    }

    private suspend fun click(node: AccessibilityNodeInfo, label: String): ScreenResult {
        val before = SystemClock.elapsedRealtime()
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !(target.isClickable && target.isEnabled) && hops < 6) {
            target = target.parent
            hops++
        }
        var ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!ok) {
            // The element exposes no click action: tap the middle of the node's own bounds instead.
            val b = boundsOf(node)
            if (!b.isEmpty) ok = gesture(Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }, 60)
        }
        if (!ok) return ScreenResult.NotFound("I found “$label” but could not tap it.")
        val changed = awaitChange(before)
        return ScreenResult.Done(if (changed) "Tapped “$label”." else "Tapped “$label”, but the screen did not change.")
    }

    // ---- reading the screen (only to find what the user asked for) ----

    private fun visibleNodes(root: AccessibilityNodeInfo, limit: Int = 800): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            if (!n.isVisibleToUser) continue
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun labelOf(n: AccessibilityNodeInfo): String? {
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }?.let { return it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }?.let { return it }
        n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }?.let { return it }
        // Icon-only controls often have just an id such as ".../search_button".
        return n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
    }

    private fun summaryOf(n: AccessibilityNodeInfo): String? {
        labelOf(n)?.let { return it.take(40) }
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> summaryOf(c)?.let { return it } }
        return null
    }

    private fun boundsOf(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    private fun area(n: AccessibilityNodeInfo): Int = boundsOf(n).let { it.width() * it.height() }

    private fun where(n: AccessibilityNodeInfo): String {
        val b = boundsOf(n)
        val m = resources.displayMetrics
        val v = when {
            b.centerY() < m.heightPixels / 3 -> "top"
            b.centerY() > m.heightPixels * 2 / 3 -> "bottom"
            else -> "middle"
        }
        val h = when {
            b.centerX() < m.widthPixels / 3 -> "left"
            b.centerX() > m.widthPixels * 2 / 3 -> "right"
            else -> "centre"
        }
        return "$v $h"
    }

    // ---- safety rules ----

    /** Password and payment fields are never typed into or read. */
    private fun isSensitiveField(n: AccessibilityNodeInfo): Boolean {
        if (n.isPassword) return true
        val variation = n.inputType and InputType.TYPE_MASK_VARIATION
        val cls = n.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        ) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        if (!n.isEditable) return false
        val hint = TextNorm.normalize(listOfNotNull(n.hintText, n.contentDescription).joinToString(" "))
        return SENSITIVE_FIELD_WORDS.any { hint.contains(it) }
    }

    private fun isRisky(label: String): Boolean {
        val norm = TextNorm.normalize(label)
        val words = norm.split(' ').toSet()
        return RISKY_WORDS.any { it in words } || RISKY_PHRASES.any { norm.contains(it) }
    }

    /** Android's permission and installer screens are never driven by an assistant. */
    private fun blocked(root: AccessibilityNodeInfo): ScreenResult? {
        val pkg = root.packageName?.toString() ?: return null
        return if (pkg in PROTECTED_PACKAGES) {
            ScreenResult.Blocked("I do not tap inside Android's permission and install screens. Please do that yourself.")
        } else {
            null
        }
    }

    private fun hidden() = ScreenResult.NotFound("I cannot see the screen right now. It may be locked or protected by the app.")

    // ---- timing and gestures ----

    /** Waits briefly for any window change after [since]; returns whether one happened. */
    private suspend fun awaitChange(since: Long, timeoutMs: Long = 700): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (lastChangeAt > since) return true
            delay(40)
        }
        return lastChangeAt > since
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private companion object {
        val PROTECTED_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
        )
        val RISKY_WORDS = setOf(
            "pay", "buy", "purchase", "order", "send", "delete", "remove", "confirm", "transfer", "submit",
            "checkout", "subscribe", "install", "uninstall", "erase", "reset", "withdraw", "donate",
        )
        val RISKY_PHRASES = listOf("place order", "sign out", "log out", "factory reset", "clear data")
        val SENSITIVE_FIELD_WORDS = listOf(
            "password", "passcode", "pin code", "card number", "credit card", "debit card", "cvv", "cvc", "security code", "expiry",
        )
    }
}
