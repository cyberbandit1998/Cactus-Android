package app.cactusai.core.models

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Which hosts model downloads may talk to. Redirects are checked hop by hop against the same rule. */
data class DownloadPolicy(
    val allowedHostSuffixes: List<String>,
    val requireHttps: Boolean = true,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val maxRedirects: Int = 6,
) {
    fun allows(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return false
        if (scheme != "https" && (requireHttps || scheme != "http")) return false
        return allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        val HUGGING_FACE = DownloadPolicy(allowedHostSuffixes = listOf("huggingface.co", "hf.co"))
    }
}

class HttpResponse internal constructor(
    val code: Int,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: InputStream?,
    val finalUrl: String,
    private val connection: HttpURLConnection,
) : Closeable {
    override fun close() {
        try {
            body?.close()
        } catch (_: IOException) {
        }
        connection.disconnect()
    }
}

/** Plain HttpURLConnection with manual, policy-checked redirects. No cookies, no credentials. */
object Http {
    fun open(url: String, method: String, headers: Map<String, String>, policy: DownloadPolicy): HttpResponse {
        var current = url
        var hops = 0
        while (true) {
            val uri = URI(current)
            if (!policy.allows(uri)) throw IOException("blocked: ${uri.host ?: "?"} is not an allowed download host")
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = policy.connectTimeoutMs
            conn.readTimeout = policy.readTimeoutMs
            conn.requestMethod = method
            conn.useCaches = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", "CactusAI-Android")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            val responseHeaders = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v.isNotEmpty()) responseHeaders[k.lowercase()] = v.last()
            }
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = responseHeaders["location"]
                conn.disconnect()
                if (location == null) throw IOException("redirect without a location")
                if (++hops > policy.maxRedirects) throw IOException("too many redirects")
                current = URI(current).resolve(location).toString()
                continue
            }
            val stream = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            return HttpResponse(code, responseHeaders, stream, current, conn)
        }
    }
}
