package app.cactusai.core.models

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun of(file: File): String = file.inputStream().use { of(it) }

    fun of(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun isHex64(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}
