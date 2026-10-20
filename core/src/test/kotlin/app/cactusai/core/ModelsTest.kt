package app.cactusai.core

import app.cactusai.core.models.CactFile
import app.cactusai.core.models.DownloadException
import app.cactusai.core.models.DownloadPolicy
import app.cactusai.core.models.ModelDownloader
import app.cactusai.core.models.ModelKind
import app.cactusai.core.models.ModelSpec
import app.cactusai.core.models.ModelStatus
import app.cactusai.core.models.ModelStore
import app.cactusai.core.models.Models
import app.cactusai.core.models.RemoteInfo
import app.cactusai.core.models.RemoteInfoFetcher
import app.cactusai.core.models.Sha256
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ModelsTest {
    private lateinit var dir: File
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("models-test").toFile()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    private val spec = ModelSpec(ModelKind.WHISTLE, "Test model", "test/repo", "test.cact", 10_000, "test")
    private val local = DownloadPolicy(allowedHostSuffixes = listOf("127.0.0.1"), requireHttps = false)

    // ---- .cact structure ----

    private fun cactFile(bytes: ByteArray = SyntheticCact.build()): File = File(dir, "f.cact").also { it.writeBytes(bytes) }

    @Test
    fun wellFormedArchiveIsValid() {
        val r = CactFile.inspect(cactFile())
        assertTrue(r is CactFile.Check.Valid)
        assertEquals(3, (r as CactFile.Check.Valid).tensors)
    }

    @Test
    fun truncatedArchiveIsDetectedEvenWithoutAHash() {
        val full = SyntheticCact.build()
        for (cut in listOf(10, 150, 220, full.size - 1, full.size - 500)) {
            val r = CactFile.inspect(cactFile(full.copyOf(cut)))
            assertTrue("cut at $cut", r is CactFile.Check.Invalid)
        }
    }

    @Test
    fun wrongFormatsAreNamed() {
        val gen2 = CactFile.inspect(cactFile(SyntheticCact.build(tag = CactFile.TAG_GENERATION_2))) as CactFile.Check.Invalid
        assertTrue(gen2.reason.contains("Needle 2"))
        val html = CactFile.inspect(cactFile("<!DOCTYPE html><html>".padEnd(400, ' ').toByteArray())) as CactFile.Check.Invalid
        assertTrue(html.reason.contains("not a .cact"))
        assertTrue(CactFile.inspect(File(dir, "missing")) is CactFile.Check.Invalid)
    }

    @Test
    fun implausibleHeaderValuesAreRejected() {
        val bytes = SyntheticCact.build()
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
        assertTrue(CactFile.inspect(cactFile(bytes)) is CactFile.Check.Invalid)
    }

    // ---- store ----

    @Test
    fun storeStatusLifecycle() {
        val store = ModelStore(dir)
        assertEquals(ModelStatus.NotInstalled, store.status(spec))
        store.partFile(spec).writeBytes(ByteArray(100))
        assertEquals(ModelStatus.Partial(100), store.status(spec))
        store.file(spec).writeBytes(SyntheticCact.build())
        ModelStore.writeHash(store.file(spec), Sha256.of(store.file(spec)))
        assertTrue(store.status(spec) is ModelStatus.Installed)
        assertEquals(true, store.verifyHash(spec))
        store.file(spec).appendBytes(byteArrayOf(1))
        assertEquals(false, store.verifyHash(spec))
        store.delete(spec)
        assertEquals(ModelStatus.NotInstalled, store.status(spec))
        assertFalse(ModelStore.hashFile(store.file(spec)).exists())
    }

    @Test
    fun corruptInstalledFileIsReportedInvalid() {
        val store = ModelStore(dir)
        store.file(spec).writeBytes(ByteArray(500))
        assertTrue(store.status(spec) is ModelStatus.Invalid)
    }

    @Test
    fun officialSpecsUseTheUpstreamRepositoriesOverHttps() {
        assertEquals("https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact", Models.NEEDLE_3.resolveUrl)
        assertEquals("https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact", Models.WHISTLE.resolveUrl)
        assertTrue(DownloadPolicy.HUGGING_FACE.allows(java.net.URI(Models.NEEDLE_3.resolveUrl)))
    }

    @Test
    fun downloadPolicyBlocksOtherHostsAndPlainHttp() {
        val p = DownloadPolicy.HUGGING_FACE
        assertTrue(p.allows(java.net.URI("https://cdn-lfs.huggingface.co/x")))
        assertTrue(p.allows(java.net.URI("https://cas-bridge.xethub.hf.co/x")))
        assertFalse(p.allows(java.net.URI("http://huggingface.co/x")))
        assertFalse(p.allows(java.net.URI("https://evilhuggingface.co/x")))
        assertFalse(p.allows(java.net.URI("https://huggingface.co.evil.example/x")))
        assertFalse(p.allows(java.net.URI("file:///etc/passwd")))
    }

    // ---- downloader against a real local HTTP server ----

    private fun serve(body: ByteArray, onRequest: (HttpExchange, Int) -> Boolean = { _, _ -> false }): String {
        val hits = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/file") { ex ->
            val n = hits.incrementAndGet()
            if (onRequest(ex, n)) return@createContext
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (start >= body.size) {
                    ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                    ex.sendResponseHeaders(416, -1)
                } else {
                    ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                    ex.sendResponseHeaders(206, (body.size - start).toLong())
                    ex.responseBody.use { it.write(body, start, body.size - start) }
                }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/file"
    }

    private fun download(url: String, remote: RemoteInfo, progress: (Long) -> Unit = {}): File = runBlocking {
        ModelDownloader(local).download(spec, File(dir, "m"), remote, url) { progress(it.downloadedBytes) }
    }

    private fun expectFailure(kind: DownloadException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: DownloadException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    @Test
    fun downloadsVerifiesAndInstalls() {
        val bytes = SyntheticCact.build(tensors = 5, dataPerTensor = 40_000)
        val url = serve(bytes)
        var last = 0L
        val file = download(url, RemoteInfo(bytes.size.toLong(), Sha256.of(bytes))) { last = it }
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(bytes.size.toLong(), last)
        assertFalse(File(dir, "m/test.cact.part").exists())
        assertEquals(Sha256.of(bytes), File(dir, "m/test.cact.sha256").readText().trim())
    }

    @Test
    fun worksWithoutAPublishedHash() {
        val bytes = SyntheticCact.build()
        val file = download(serve(bytes), RemoteInfo(bytes.size.toLong(), null))
        assertTrue(file.exists())
        assertNotNull(File(dir, "m/test.cact.sha256").takeIf { it.exists() })
    }

    @Test
    fun resumesAnInterruptedDownloadWithARangeRequest() {
        val bytes = SyntheticCact.build(tensors = 4, dataPerTensor = 50_000)
        val ranges = mutableListOf<String?>()
        val url = serve(bytes) { ex, n ->
            ranges += ex.requestHeaders.getFirst("Range")
            if (n == 1) {
                // First attempt: promise the whole file, send a third of it, then drop the connection.
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.write(bytes, 0, bytes.size / 3)
                ex.responseBody.flush()
                ex.close()
                true
            } else {
                false
            }
        }
        val remote = RemoteInfo(bytes.size.toLong(), Sha256.of(bytes))
        expectFailure(DownloadException.Kind.NETWORK) { download(url, remote) }
        val part = File(dir, "m/test.cact.part")
        assertTrue(part.exists() && part.length() in 1 until bytes.size)
        val resumedFrom = part.length()

        val file = download(url, remote)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals("bytes=$resumedFrom-", ranges.last())
    }

    @Test
    fun aServerThatIgnoresRangeStillProducesACorrectFile() {
        val bytes = SyntheticCact.build(dataPerTensor = 20_000)
        val url = serve(bytes) { ex, _ ->
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
            true
        }
        File(dir, "m").mkdirs()
        File(dir, "m/test.cact.part").writeBytes(bytes.copyOf(1234))
        val file = download(url, RemoteInfo(bytes.size.toLong(), Sha256.of(bytes)))
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test
    fun corruptedDownloadIsRejectedAndNeverInstalled() {
        val bytes = SyntheticCact.build()
        val tampered = bytes.copyOf().also { it[bytes.size - 5] = (it[bytes.size - 5] + 1).toByte() }
        val url = serve(tampered)
        expectFailure(DownloadException.Kind.HASH_MISMATCH) { download(url, RemoteInfo(bytes.size.toLong(), Sha256.of(bytes))) }
        assertFalse(File(dir, "m/test.cact").exists())
        assertFalse(File(dir, "m/test.cact.part").exists())
    }

    @Test
    fun wrongSizeIsRejected() {
        val bytes = SyntheticCact.build()
        val url = serve(bytes)
        expectFailure(DownloadException.Kind.SIZE_MISMATCH) { download(url, RemoteInfo(bytes.size.toLong() + 10, null)) }
        assertFalse(File(dir, "m/test.cact").exists())
    }

    @Test
    fun anErrorPageIsNotInstalledAsAModel() {
        val html = "<html>Access denied</html>".padEnd(2000, ' ').toByteArray()
        val url = serve(html)
        expectFailure(DownloadException.Kind.BAD_FORMAT) { download(url, RemoteInfo(html.size.toLong(), null)) }
        assertFalse(File(dir, "m/test.cact").exists())
    }

    @Test
    fun httpErrorsAreReportedWithRetryability() {
        val url = serve(ByteArray(1)) { ex, _ ->
            ex.sendResponseHeaders(503, -1)
            ex.close()
            true
        }
        try {
            download(url, RemoteInfo(null, null))
            fail()
        } catch (e: DownloadException) {
            assertEquals(DownloadException.Kind.HTTP, e.kind)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun redirectsToOtherHostsAreBlocked() {
        val url = serve(ByteArray(1)) { ex, _ ->
            ex.responseHeaders.add("Location", "http://localhost:1/steal")
            ex.sendResponseHeaders(302, -1)
            ex.close()
            true
        }
        expectFailure(DownloadException.Kind.NETWORK) { download(url, RemoteInfo(null, null)) }
    }

    @Test
    fun cancellingStopsTheTransferAndKeepsThePartialFile() {
        val bytes = SyntheticCact.build(tensors = 4, dataPerTensor = 200_000)
        val url = serve(bytes)
        try {
            runBlocking {
                ModelDownloader(local).download(spec, File(dir, "m"), RemoteInfo(bytes.size.toLong(), null), url) {
                    throw kotlinx.coroutines.CancellationException("user cancelled")
                }
            }
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertFalse(File(dir, "m/test.cact").exists())
    }

    // ---- remote info ----

    @Test
    fun remoteInfoFromTheRepositoryListing() {
        val sha = "a".repeat(64)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/tree") { ex ->
            val json = """[{"type":"file","path":"other.bin","size":1},{"type":"file","path":"test.cact","size":123,"lfs":{"oid":"$sha","size":123456,"pointerSize":134}}]"""
            ex.sendResponseHeaders(200, json.length.toLong())
            ex.responseBody.use { it.write(json.toByteArray()) }
        }
        s.start()
        server = s
        val info = RemoteInfoFetcher.fetch(spec, local, treeUrl = "http://127.0.0.1:${s.address.port}/tree")
        assertEquals(123456L, info.sizeBytes)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun remoteInfoFallsBackToLinkedEtagHeaders() {
        val sha = "b".repeat(64)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/tree") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        s.createContext("/file") { ex ->
            // Like the real host: a redirect to a CDN, with the content hash and size on the redirect itself.
            ex.responseHeaders.add("X-Linked-ETag", "\"$sha\"")
            ex.responseHeaders.add("X-Linked-Size", "777")
            ex.responseHeaders.add("Location", "http://127.0.0.1:1/signed-cdn-address")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        val info = RemoteInfoFetcher.fetch(spec, local, treeUrl = "$base/tree", fileUrl = "$base/file")
        assertEquals(777L, info.sizeBytes)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun remoteInfoIsEmptyWhenTheServerIsUnreachable() {
        val info = RemoteInfoFetcher.fetch(spec, local, treeUrl = "http://127.0.0.1:1/tree", fileUrl = "http://127.0.0.1:1/file")
        assertNull(info.sizeBytes)
        assertNull(info.sha256)
    }
}
