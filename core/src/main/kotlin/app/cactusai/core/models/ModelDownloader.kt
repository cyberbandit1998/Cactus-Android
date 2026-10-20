package app.cactusai.core.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?, val bytesPerSecond: Long)

class DownloadException(val kind: Kind, message: String, val retryable: Boolean) : Exception(message) {
    enum class Kind { NETWORK, HTTP, STORAGE, SIZE_MISMATCH, HASH_MISMATCH, BAD_FORMAT }
}

/**
 * Resumable model download. Data goes to `<name>.part` and only becomes `<name>` after the size,
 * SHA-256 (when the server published one) and `.cact` structure all check out, so a half-finished or
 * corrupted file can never be mistaken for an installed model. An interrupted download keeps its
 * `.part` file and continues from there with an HTTP Range request.
 */
class ModelDownloader(private val policy: DownloadPolicy = DownloadPolicy.HUGGING_FACE) {

    suspend fun download(
        spec: ModelSpec,
        destDir: File,
        remote: RemoteInfo,
        url: String = spec.resolveUrl,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw DownloadException(DownloadException.Kind.STORAGE, "cannot create ${destDir.path}", false)
        val dest = File(destDir, spec.fileName)
        val part = File(destDir, spec.fileName + ".part")

        val expectedSize = remote.sizeBytes
        val needed = (expectedSize ?: spec.approxBytes) - part.length().coerceAtLeast(0)
        if (needed > 0 && destDir.usableSpace < needed + FREE_SPACE_MARGIN) {
            throw DownloadException(DownloadException.Kind.STORAGE, "not enough free storage for ${spec.displayName} (needs about ${(needed + FREE_SPACE_MARGIN) / 1_000_000} MB)", false)
        }

        if (expectedSize != null && part.exists() && part.length() > expectedSize) part.delete()
        transfer(url, part, expectedSize, onProgress)

        val size = part.length()
        if (expectedSize != null && size != expectedSize) {
            part.delete()
            throw DownloadException(DownloadException.Kind.SIZE_MISMATCH, "downloaded $size bytes but expected $expectedSize", true)
        }
        if (remote.sha256 != null) {
            coroutineContext.ensureActive()
            val actual = Sha256.of(part)
            if (actual != remote.sha256) {
                part.delete()
                throw DownloadException(DownloadException.Kind.HASH_MISMATCH, "SHA-256 mismatch: the download is corrupted", true)
            }
        }
        when (val check = CactFile.inspect(part)) {
            is CactFile.Check.Valid -> Unit
            is CactFile.Check.Invalid -> {
                part.delete()
                throw DownloadException(DownloadException.Kind.BAD_FORMAT, "${spec.displayName} is not a usable model: ${check.reason}", false)
            }
        }
        coroutineContext.ensureActive()
        val hash = remote.sha256 ?: Sha256.of(part)
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw DownloadException(DownloadException.Kind.STORAGE, "could not finish the file (${e.message})", false)
        }
        ModelStore.writeHash(dest, hash)
        dest
    }

    private enum class Step { DONE, RESTART_FROM_ZERO }

    private suspend fun transfer(url: String, part: File, expectedSize: Long?, onProgress: (DownloadProgress) -> Unit) {
        var restarted = false
        while (true) {
            val existing = if (part.exists()) part.length() else 0L
            if (expectedSize != null && existing == expectedSize) return
            val headers = if (existing > 0) mapOf("Range" to "bytes=$existing-") else emptyMap()
            val response = try {
                Http.open(url, "GET", headers, policy)
            } catch (e: IOException) {
                throw DownloadException(DownloadException.Kind.NETWORK, "cannot reach the model server (${e.message})", true)
            }
            val step = response.use { r -> receive(r, part, existing, expectedSize, onProgress) }
            if (step == Step.DONE) return
            // The server answered a resume request in a way we cannot trust: start over exactly once.
            if (restarted) throw DownloadException(DownloadException.Kind.HTTP, "the server did not honour a resume request", true)
            restarted = true
            part.delete()
        }
    }

    private suspend fun receive(r: HttpResponse, part: File, existing: Long, expectedSize: Long?, onProgress: (DownloadProgress) -> Unit): Step {
        val append: Boolean
        val total: Long?
        when (r.code) {
            // 200 = the whole file (also what a server that ignores Range sends): rewrite from zero.
            200 -> {
                append = false
                total = r.headers["content-length"]?.toLongOrNull()
            }
            206 -> {
                val range = parseContentRange(r.headers["content-range"])
                if (range == null || range.start != existing) return Step.RESTART_FROM_ZERO
                append = true
                total = range.total
            }
            416 -> return Step.RESTART_FROM_ZERO
            else -> throw DownloadException(DownloadException.Kind.HTTP, "the model server answered HTTP ${r.code}", r.code >= 500 || r.code == 429)
        }
        val body = r.body ?: throw DownloadException(DownloadException.Kind.HTTP, "empty response", true)
        writeBody(body, part, if (append) existing else 0L, append, total, total ?: expectedSize, onProgress)
        return Step.DONE
    }

    private suspend fun writeBody(
        body: java.io.InputStream,
        part: File,
        startAt: Long,
        append: Boolean,
        declaredTotal: Long?,
        total: Long?,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        RandomAccessFile(part, "rw").use { raf ->
            if (!append) raf.setLength(0)
            raf.seek(startAt)
            val buf = ByteArray(64 * 1024)
            var written = startAt
            val startedAt = System.nanoTime()
            var lastReport = 0L
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val n = body.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    written += n
                    val now = System.nanoTime()
                    if (now - lastReport > 100_000_000L) {
                        lastReport = now
                        val seconds = ((now - startedAt) / 1e9).coerceAtLeast(0.001)
                        onProgress(DownloadProgress(written, total, ((written - startAt) / seconds).toLong()))
                    }
                }
            } catch (e: IOException) {
                throw DownloadException(DownloadException.Kind.NETWORK, "the connection dropped (${e.message}); it will resume where it stopped", true)
            }
            val seconds = ((System.nanoTime() - startedAt) / 1e9).coerceAtLeast(0.001)
            onProgress(DownloadProgress(written, total, ((written - startAt) / seconds).toLong()))
            // The JDK reports a connection that closed early as a normal end of stream. Fewer bytes than the
            // server itself announced is an interruption: keep the .part file so the next try resumes.
            if (declaredTotal != null && written < declaredTotal) {
                throw DownloadException(
                    DownloadException.Kind.NETWORK,
                    "the connection closed at $written of $declaredTotal bytes; it will resume where it stopped",
                    true,
                )
            }
        }
    }

    internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

    internal fun parseContentRange(value: String?): ContentRange? {
        val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(value?.trim().orEmpty()) ?: return null
        return ContentRange(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull())
    }

    private companion object {
        const val FREE_SPACE_MARGIN = 50L * 1024 * 1024
    }
}
