package app.cactusai.core.ask

import app.cactusai.core.web.ReadNode
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.WebFailure
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSearchTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-20T19:42:00Z"), ZoneId.of("America/Detroit"))

    private class FakeOps(
        var opens: Boolean = true,
        override var canRead: Boolean = true,
        override var isOnline: Boolean = true,
        val pages: MutableList<PageSnapshot?> = mutableListOf(),
    ) : BrowserOps {
        val opened = mutableListOf<String>()
        var reads = 0
        var returns = 0

        override fun open(url: String): Boolean {
            opened += url
            return opens
        }

        override suspend fun readPage(): PageSnapshot? {
            reads++
            return if (pages.size > 1) pages.removeAt(0) else pages.firstOrNull()
        }

        override suspend fun returnToApp(): Boolean {
            returns++
            return true
        }
    }

    private fun n(text: String, heading: Boolean = false, link: String? = null) = ReadNode(text, heading, link, clickable = link != null)

    private val menusOnly = PageSnapshot("com.android.chrome", listOf(n("All"), n("Images"), n("Sign in")))
    private fun page(vararg titles: String) = PageSnapshot(
        "com.android.chrome",
        titles.flatMapIndexed { i, t -> listOf(n(t, heading = true, link = "https://example$i.org/page"), n("example$i.org"), n("Text about $t.")) },
    )

    private fun search(ops: FakeOps) = BrowserSearch(ops, "app.cactusai.assistant", clock)

    @Test
    fun theSearchPageIsOpenedOnAFixedGoogleAddress() = runTest {
        val ops = FakeOps(pages = mutableListOf(page("A", "B")))
        search(ops).search("who invented the telephone")
        assertEquals("https://www.google.com/search?q=who+invented+the+telephone", ops.opened.single())
    }

    @Test
    fun theQueryIsCleanedAndNothingIsOpenedForAnEmptyOne() = runTest {
        val ops = FakeOps(pages = mutableListOf(page("A")))
        search(ops).search("<b>weather</b> in‮ Detroit")
        assertEquals("https://www.google.com/search?q=weather+in+Detroit", ops.opened.single())
        val empty = FakeOps()
        val r = search(empty).search("  ​ ") as SearchOutcome.Failed
        assertEquals(WebFailure.BAD_REQUEST, r.kind)
        assertTrue(empty.opened.isEmpty())
    }

    @Test
    fun resultsAreReadWhenThePageIsStableAndControlGoesBackToTheApp() = runTest {
        val ops = FakeOps(pages = mutableListOf(menusOnly, page("A"), page("A", "B"), page("A", "B"), page("A", "B")))
        val r = search(ops).search("telephone") as SearchOutcome.Results
        assertEquals(listOf("A", "B"), r.results.map { it.title })
        assertEquals(clock.millis(), r.retrievedAtMs)
        assertEquals(1, ops.returns)
        assertTrue(r.pageUrl!!.startsWith("https://www.google.com/search?q="))
        assertEquals("a page that grew was read again until it settled", 4, ops.reads)
    }

    @Test
    fun withoutTheAccessibilityServiceTheResultsStayVisibleAndTheUserIsTold() = runTest {
        val ops = FakeOps(canRead = false)
        val r = search(ops).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.ACCESSIBILITY_OFF, r.kind)
        assertTrue(r.browserShowsResults)
        assertTrue(r.message.contains("Accessibility"))
        assertEquals(1, ops.opened.size)
        assertEquals(0, ops.reads)
        assertEquals(0, ops.returns)
    }

    @Test
    fun noBrowserIsReported() = runTest {
        val r = search(FakeOps(opens = false)).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.NO_BROWSER, r.kind)
        assertFalse(r.browserShowsResults)
    }

    @Test
    fun aPageThatSharesNothingIsGivenUpOnWithinTheTimeLimitAndLeftOnScreen() = runTest {
        val ops = FakeOps(pages = mutableListOf(null))
        val r = search(ops).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.NOT_READABLE, r.kind)
        assertTrue(r.browserShowsResults)
        assertTrue(r.message.contains("still on your screen"))
        assertEquals(0, ops.returns)
        assertTrue("took $currentTime ms", currentTime in 14_000..15_500)
    }

    @Test
    fun aPageWithOnlyMenusIsNotReadable() = runTest {
        val ops = FakeOps(pages = mutableListOf(menusOnly))
        val r = search(ops).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.NOT_READABLE, r.kind)
        assertTrue(r.message.contains("doesn't share its text"))
        assertEquals(0, ops.returns)
    }

    @Test
    fun ourOwnWindowIsNeverReadAsTheResultsPage() = runTest {
        val ours = PageSnapshot("app.cactusai.assistant", listOf(n("Telephone", heading = true, link = "https://example.org/x")))
        val ops = FakeOps(pages = mutableListOf(ours))
        val r = search(ops).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.NOT_READABLE, r.kind)
    }

    @Test
    fun theNewestGoodReadIsUsedIfThePageNeverSettles() = runTest {
        // a page whose results keep changing until the time limit
        val changing: MutableList<PageSnapshot?> = (1..40).map<Int, PageSnapshot?> { page("T$it") }.toMutableList()
        val ops = FakeOps(pages = changing)
        val r = search(ops).search("telephone") as SearchOutcome.Results
        assertEquals(1, r.results.size)
        assertEquals(1, ops.returns)
    }

    @Test
    fun aSportsCardAboveTheResultsIsKept() = runTest {
        val withCard = PageSnapshot(
            "com.android.chrome",
            listOf(n("Detroit Tigers"), n("5"), n("Kansas City Royals"), n("3"), n("Final")) + page("A").nodes,
        )
        val r = search(FakeOps(pages = mutableListOf(withCard))).search("tigers score") as SearchOutcome.Results
        assertTrue(r.header!!.contains("Final"))
    }

    @Test
    fun cancellingStopsThePollingAndDoesNotReturnToTheApp() = runTest {
        val ops = FakeOps(pages = mutableListOf(null))
        val job = launch { search(ops).search("telephone") }
        runCurrent()
        job.cancel(CancellationException("user cancelled"))
        runCurrent()
        assertTrue(job.isCancelled)
        val readsAtCancel = ops.reads
        assertEquals(0, ops.returns)
        assertEquals(readsAtCancel, ops.reads)
        assertNull(null)
    }

    @Test
    fun withoutAConnectionNothingIsOpenedAndTheAnswerSaysWhy() = runTest {
        val ops = FakeOps(isOnline = false, pages = mutableListOf(page("A")))
        val r = search(ops).search("telephone") as SearchOutcome.Failed
        assertEquals(WebFailure.OFFLINE, r.kind)
        assertTrue(ops.opened.isEmpty())
        assertFalse(r.browserShowsResults)
        assertTrue(r.message.contains("offline"))
    }
}
