package app.cactusai.core.models

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import java.io.IOException

/** What the model host says about a file: its size and, for LFS files, the SHA-256 of its content. */
data class RemoteInfo(val sizeBytes: Long?, val sha256: String?)

object RemoteInfoFetcher {
    private const val MAX_LISTING_BYTES = 4_000_000

    /**
     * Tries the repository listing first (`lfs.oid` is the content SHA-256), then the
     * `X-Linked-ETag` / `X-Linked-Size` headers of the download URL. Either may be missing;
     * a missing hash only means the download is checked by size and `.cact` structure instead.
     */
    fun fetch(spec: ModelSpec, policy: DownloadPolicy = DownloadPolicy.HUGGING_FACE, treeUrl: String = spec.treeApiUrl, fileUrl: String = spec.resolveUrl): RemoteInfo {
        fromListing(spec, policy, treeUrl)?.let { if (it.sizeBytes != null || it.sha256 != null) return it }
        return fromHeaders(policy, fileUrl)
    }

    private fun fromListing(spec: ModelSpec, policy: DownloadPolicy, treeUrl: String): RemoteInfo? = try {
        Http.open(treeUrl, "GET", mapOf("Accept" to "application/json"), policy).use { r ->
            if (r.code != 200 || r.body == null) return null
            val text = String(r.body.readNBytes(MAX_LISTING_BYTES), Charsets.UTF_8)
            val entries = (Json.parseOrNull(text) as? JsonValue.Arr)?.items ?: return null
            val entry = entries.firstNotNullOfOrNull { e ->
                (e as? JsonValue.Obj)?.takeIf { it.string("path") == spec.fileName }
            } ?: return null
            val lfs = entry.obj("lfs")
            val sha = lfs?.string("oid")?.lowercase()?.takeIf(Sha256::isHex64)
            val size = lfs?.long("size") ?: entry.long("size")
            RemoteInfo(size?.takeIf { it > 0 }, sha)
        }
    } catch (_: IOException) {
        null
    }

    private fun fromHeaders(policy: DownloadPolicy, fileUrl: String): RemoteInfo = try {
        Http.open(fileUrl, "HEAD", emptyMap(), policy).use { r ->
            if (r.code !in 200..299) return RemoteInfo(null, null)
            val sha = r.headers["x-linked-etag"]?.trim('"', ' ')?.lowercase()?.takeIf(Sha256::isHex64)
            val size = (r.headers["x-linked-size"] ?: r.headers["content-length"])?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            RemoteInfo(size, sha)
        }
    } catch (_: IOException) {
        RemoteInfo(null, null)
    }
}
