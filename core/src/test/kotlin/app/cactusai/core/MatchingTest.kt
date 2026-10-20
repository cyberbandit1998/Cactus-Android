package app.cactusai.core

import app.cactusai.core.match.AppRole
import app.cactusai.core.match.AppRoles
import app.cactusai.core.match.Candidate
import app.cactusai.core.match.MatchResult
import app.cactusai.core.match.NameMatcher
import app.cactusai.core.match.TextNorm
import app.cactusai.core.phone.PhoneNumbers
import app.cactusai.core.web.SearchSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private val apps = listOf(
        Candidate("com.spotify.music", "Spotify"),
        Candidate("com.google.android.youtube", "YouTube"),
        Candidate("com.google.android.apps.youtube.music", "YT Music", listOf("YouTube Music")),
        Candidate("com.google.android.apps.maps", "Maps"),
        Candidate("com.google.android.apps.photos", "Photos"),
        Candidate("com.android.chrome", "Chrome"),
        Candidate("com.reddit.frontpage", "Reddit"),
        Candidate("com.a.cam", "Camera"),
        Candidate("com.b.cam", "Camera"),
    )

    private fun single(q: String): String = (NameMatcher.find(q, apps) as MatchResult.Single).match.candidate.item

    @Test
    fun normalisation() {
        assertEquals("cafe and co", TextNorm.normalize("Café & Co."))
        assertEquals("you tube", TextNorm.normalize("  You-Tube "))
    }

    @Test
    fun exactAndSpokenForms() {
        assertEquals("com.spotify.music", single("Spotify"))
        assertEquals("com.spotify.music", single("open my spotify app".removePrefix("open ")))
        assertEquals("com.google.android.youtube", single("youtube"))
        assertEquals("com.google.android.apps.maps", single("maps"))
    }

    @Test
    fun prefixesAndSmallTypos() {
        assertEquals("com.spotify.music", single("spotif"))
        assertEquals("com.spotify.music", single("spotifi"))
        assertEquals("com.google.android.youtube", single("you tube"))
    }

    @Test
    fun exactBeatsLongerNames() {
        val list = listOf(Candidate(1, "Photos"), Candidate(2, "Google Photos"))
        assertEquals(1, (NameMatcher.find("photos", list) as MatchResult.Single).match.candidate.item)
    }

    @Test
    fun duplicateLabelsAreAmbiguousNeverGuessed() {
        val r = NameMatcher.find("camera", apps)
        assertTrue(r is MatchResult.Ambiguous)
        assertEquals(setOf("com.a.cam", "com.b.cam"), (r as MatchResult.Ambiguous).matches.map { it.candidate.item }.toSet())
    }

    @Test
    fun closeMatchesAreAmbiguous() {
        val list = listOf(Candidate(1, "Google Maps"), Candidate(2, "Google Meet"), Candidate(3, "Google Docs"))
        assertTrue(NameMatcher.find("google", list) is MatchResult.Ambiguous)
    }

    @Test
    fun unrelatedNamesMatchNothing() {
        assertTrue(NameMatcher.find("zzzxq", apps) is MatchResult.None)
        assertTrue(NameMatcher.find("", apps) is MatchResult.None)
        assertTrue(NameMatcher.find("   ", apps) is MatchResult.None)
    }

    @Test
    fun cleanQueryDropsFiller() {
        assertEquals("spotify", NameMatcher.cleanQuery("my Spotify app"))
        assertEquals("camera", NameMatcher.cleanQuery("the camera"))
    }

    @Test
    fun rolesByPurpose() {
        assertEquals(AppRole.CAMERA, AppRoles.roleFor("my camera"))
        assertEquals(AppRole.MESSAGING, AppRoles.roleFor("Messages"))
        assertEquals(AppRole.CLOCK, AppRoles.roleFor("the clock app"))
        assertNull(AppRoles.roleFor("Spotify"))
    }

    @Test
    fun phoneNumbers() {
        assertEquals("5551234567", PhoneNumbers.sanitize("555-123-4567"))
        assertEquals("+15551234567", PhoneNumbers.sanitize("+1 (555) 123-4567"))
        assertEquals("5551234", PhoneNumbers.sanitize("five five five one two three four"))
        assertEquals("5550100", PhoneNumbers.sanitize("five five five oh one oh oh"))
        assertNull(PhoneNumbers.sanitize("*#06#"))
        assertNull(PhoneNumbers.sanitize("call me"))
        assertNull(PhoneNumbers.sanitize("12"))
        assertNull(PhoneNumbers.sanitize("٥٥٥١٢٣٤"))
        assertNull(PhoneNumbers.sanitize("555-123-4567; rm -rf"))
    }

    @Test
    fun searchUrlsAreFixedHostsWithEncodedQueries() {
        assertEquals("https://www.google.com/search?q=3d+printing+ideas", SearchSite.GOOGLE.searchUrl("3d printing ideas"))
        assertEquals("https://www.youtube.com/results?search_query=bambu+lab+p1s+tips", SearchSite.YOUTUBE.searchUrl("bambu lab p1s tips"))
        val evil = SearchSite.GOOGLE.searchUrl("x&q=y#frag http://evil.example")
        assertTrue(evil.startsWith("https://www.google.com/search?q="))
        assertTrue(!evil.contains("#") && !evil.contains(" ") && !evil.substringAfter("?q=").contains("&"))
        assertEquals(SearchSite.GOOGLE, SearchSite.fromKey(null))
        assertEquals(SearchSite.REDDIT, SearchSite.fromSpokenName("Reddit"))
        assertNull(SearchSite.fromSpokenName("evil.example.com"))
    }
}
