package app.cactusai.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.cactusai.assistant.MainActivity
import app.cactusai.core.ask.PageSnapshot
import app.cactusai.core.exec.TargetSafety
import app.cactusai.core.match.NameMatcher
import app.cactusai.core.web.ReadNode
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

    // ---- reading a search page aloud, and coming back ----

    override suspend fun readPage(): PageSnapshot? = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext null
        val pkg = root.packageName?.toString()
        // Never read Android's permission and installer screens or anything that looks like a payment or banking app.
        if (TargetSafety.isProtectedPackage(pkg) || TargetSafety.looksFinancial(pkg)) return@withContext PageSnapshot(pkg, emptyList())
        PageSnapshot(pkg, readableText(root, pkg))
    }

    /**
     * The window's text in reading order. When the browser shows its web page inside a WebView, only that part is read, so the
     * address bar and tab buttons are left out; otherwise everything visible is read except the app's own controls (ids in its
     * own namespace). Editable fields are skipped altogether, which also keeps anything typed into a form out of the answer.
     */
    private fun readableText(root: AccessibilityNodeInfo, pkg: String?): List<ReadNode> {
        val webViews = ArrayList<AccessibilityNodeInfo>()
        findWebViews(root, webViews, 0)
        val starts = webViews.ifEmpty { listOf(root) }
        val ownIds = pkg?.let { "$it:id/" }
        val out = ArrayList<ReadNode>()
        var visited = 0
        // Depth-first with an explicit stack, children pushed in reverse so they come out in reading order.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (start in starts.asReversed()) stack.addFirst(start)
        while (stack.isNotEmpty() && visited < READ_NODE_LIMIT && out.size < READ_TEXT_LIMIT) {
            val n = stack.removeFirst()
            visited++
            if (!n.isVisibleToUser) continue
            if (n.isEditable || isSensitiveField(n)) continue
            val chromeId = webViews.isEmpty() && ownIds != null && n.viewIdResourceName?.startsWith(ownIds) == true
            if (!chromeId) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    out += ReadNode(
                        text = text.take(READ_TEXT_CHARS),
                        heading = n.isHeading,
                        link = n.extras?.getString(EXTRA_TARGET_URL)?.takeIf { it.isNotBlank() },
                        clickable = n.isClickable,
                    )
                }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addFirst(it) }
        }
        return out
    }

    private fun findWebViews(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40 || out.size >= 4) return
        if (n.className?.toString() == "android.webkit.WebView") {
            out += n
            return
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { findWebViews(it, out, depth + 1) }
    }

    override suspend fun returnToApp(): Boolean = withContext(Dispatchers.Default) {
        if (ownWindowInFront()) return@withContext true
        // Android limits starting activities from the background; this service is the app's best chance of being allowed.
        try {
            startActivity(
                Intent(this@CactusAccessibilityService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (_: RuntimeException) {
        }
        if (awaitOwnWindow(1500)) return@withContext true
        // One Back press, only while a browser is still in front; if that does not bring the app back, the browser stays as it is.
        if (rootInActiveWindow != null) performGlobalAction(GLOBAL_ACTION_BACK)
        awaitOwnWindow(1500)
    }

    private fun ownWindowInFront(): Boolean = rootInActiveWindow?.packageName?.toString() == packageName

    private suspend fun awaitOwnWindow(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ownWindowInFront()) return true
            delay(100)
        }
        return ownWindowInFront()
    }

    // ---- clicking ----

    /**
     * Looks at the control that is about to be tapped (its label, its id, the text around it, the app it is in) and decides with
     * the core's [TargetSafety] rules: go, ask first, or never. This is the second check; the first was made on the words the
     * user spoke, before anything was looked at. A tap that could pay, send, delete, install or grant access always waits for the
     * user's OK, however confident the model was and whether or not automatic execution is on.
     */
    private suspend fun clickOrAsk(node: AccessibilityNodeInfo, label: String): ScreenResult {
        val verdict = TargetSafety.assessTap(
            TargetSafety.Target(
                label = label,
                viewId = node.viewIdResourceName,
                nearbyText = nearbyText(node),
                packageName = node.packageName?.toString(),
                sensitiveField = isSensitiveField(node),
            ),
        )
        return when (verdict.verdict) {
            TargetSafety.Verdict.BLOCKED -> ScreenResult.Blocked(verdict.reason ?: "I do not tap that.")
            TargetSafety.Verdict.NEEDS_APPROVAL -> ScreenResult.NeedsApproval(verdict.reason ?: "Tap “$label”? This may be hard to undo.") { click(node, label) }
            TargetSafety.Verdict.SAFE -> click(node, label)
        }
    }

    /** Short labels of the neighbours and the first few ancestors: the question or dialog message an "OK" button belongs to. */
    private fun nearbyText(node: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3 && out.size < 16) {
            shortText(parent)?.let { out += it }
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if (child == node) continue
                collectShortText(child, out, 2)
            }
            parent = parent.parent
            depth++
        }
        return out
    }

    private fun collectShortText(n: AccessibilityNodeInfo, out: MutableList<String>, depthLeft: Int) {
        if (out.size >= 16 || isSensitiveField(n)) return
        shortText(n)?.let { out += it }
        if (depthLeft <= 0) return
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectShortText(it, out, depthLeft - 1) }
    }

    private fun shortText(n: AccessibilityNodeInfo): String? {
        if (n.isPassword) return null
        return (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
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
        return TargetSafety.isSensitiveFieldHint(listOfNotNull(n.hintText, n.contentDescription).joinToString(" "))
    }

    /** Android's permission and installer screens are never driven by an assistant. */
    private fun blocked(root: AccessibilityNodeInfo): ScreenResult? {
        val pkg = root.packageName?.toString() ?: return null
        return if (TargetSafety.isProtectedPackage(pkg)) {
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
}

private const val READ_NODE_LIMIT = 1500
private const val READ_TEXT_LIMIT = 400
private const val READ_TEXT_CHARS = 500

/** Chrome (and browsers built on it) put the address a link goes to under this key. Where a browser does not, the link is simply missing. */
private const val EXTRA_TARGET_URL = "AccessibilityNodeInfo.targetUrl"
