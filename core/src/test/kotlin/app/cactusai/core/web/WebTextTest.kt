package app.cactusai.core.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextTest {
    @Test
    fun webTextIsStrippedOfMarkupAndHiddenCharacters() {
        assertEquals("Hello world", UntrustedText.clean("<b>Hello</b>\n\t world"))
        assertEquals("Fish & chips", UntrustedText.clean("Fish &amp; chips"))
        assertEquals("see the page", UntrustedText.clean("see [the page](https://example.com/x)"))
        assertEquals("abc", UntrustedText.clean("a​b‮c\u0000"))
        assertEquals("ab", UntrustedText.clean("a﻿b"))
        assertEquals("", UntrustedText.clean(null))
        val long = UntrustedText.clean("x".repeat(1000), 50)
        assertEquals(50, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun linksAreRemovedFromTextThatIsReadAloud() {
        assertEquals("Read more at", UntrustedText.forSpeech("Read more at https://example.com/a?b=c"))
        assertEquals("Visit now", UntrustedText.forSpeech("Visit http://example.org/x/y/z now"))
    }

    @Test
    fun onlyOrdinaryPublicWebAddressesAreAccepted() {
        assertEquals("https://www.espn.com/mlb/", UntrustedText.safeUrl("https://www.espn.com/mlb/"))
        assertEquals("http://example.org/a", UntrustedText.safeUrl("http://example.org/a"))
        for (bad in listOf(
            "javascript:alert(1)", "intent://x#Intent;scheme=http;end", "file:///sdcard/x", "data:text/html,hi", "ftp://example.com/x",
            "http://localhost/x", "http://127.0.0.1/x", "http://192.168.1.1/x", "http://[::1]/x", "https://user:pw@example.com/", "https://intranet/x",
            "https://printer.local/x", "", "not a url", "https:///nohost", "tel:5551234", "sms:5551234",
        )) {
            assertNull(bad, UntrustedText.safeUrl(bad))
        }
        assertNull(UntrustedText.safeUrl("https://example.com/" + "a".repeat(2100)))
    }

    @Test
    fun contactDetailsAndAccountTextAreRecognisedAsSensitive() {
        for (s in listOf(
            "Contact us at help@example.com", "Call 313-555-0142 today", "+1 (313) 555 0142", "Card 4111 1111 1111 1111", "Account 123456789012",
            "SSN 123-45-6789", "Your password is hunter2", "Verification code 482913", "Signed in as someone", "Your location: Detroit, MI",
            "Detroit, MI - From your Internet address - Use precise location",
        )) {
            assertTrue(s, SensitiveText.looksSensitive(s))
        }
        for (s in listOf("The Tigers beat the Royals 5-3", "Population 1,234,567", "In 1876 Bell patented the telephone", "Open 9 to 5", "Call it a night")) {
            assertFalse(s, SensitiveText.looksSensitive(s))
        }
    }

    // ---- architecture: web text must never reach commands or actions ----

    @Test
    fun theCodeThatHandlesWebTextDoesNotTouchCommandsOrActions() {
        val forbidden = listOf("core.tools", "core.plan", "core.steps", "core.exec", "core.needle", "core.engine")
        val root = File("src/main/kotlin/app/cactusai/core")
        val dirs = listOf("web", "sports", "info").map { File(root, it) }.filter { it.isDirectory }
        assertEquals("source directories not found from ${File(".").absolutePath}", 3, dirs.size)
        var files = 0
        for (dir in dirs) {
            for (f in dir.walkTopDown().filter { it.extension == "kt" }) {
                files++
                for (line in f.readLines().filter { it.startsWith("import ") }) {
                    for (bad in forbidden) assertFalse("${f.name} imports $bad", line.contains(bad))
                }
            }
        }
        assertTrue(files > 10)
    }

    @Test
    fun noKeysOrCloudAddressesAreInTheShippedCode() {
        // The app talks to no search or AI service of its own: results come from the user's browser. A guard against one sneaking in.
        val roots = listOf(File("src/main"), File("../app/src/main"))
        val banned = listOf("ollama", "api.openai", "anthropic.com", "generativelanguage", "Bearer ")
        var scanned = 0
        for (root in roots) {
            for (f in root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "kts") }) {
                scanned++
                val text = f.readText()
                for (b in banned) assertFalse("${f.path} mentions $b", text.contains(b, ignoreCase = true))
            }
        }
        assertTrue("scanned $scanned files", scanned > 30)
    }
}
