package app.cactusai.core.models

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structural check of a `.cact` archive without loading it, so a truncated or wrong file is
 * caught with a clear message instead of an engine crash.
 *
 * Layout, as read by the upstream Python package (`needle/__init__.py`, `needle/agent/whistle.py`):
 *  - header `<48If` = 196 bytes; word 0 is the format tag, word 1 the tensor count, word 2 the codebook length
 *  - then `codebook` 32-bit words
 *  - then one 44-byte record per tensor (`<BBHIIIIQQII`); the u64 at record offset 20 is the data offset
 *    and the u64 at 28 is the data size
 */
object CactFile {
    const val TAG_GENERATION_2 = 0x05E12A83
    const val TAG_GENERATION_3 = 0x05E12A84
    private const val HEADER_BYTES = 196
    private const val RECORD_BYTES = 44
    private const val MAX_TENSORS = 200_000
    private const val MAX_CODEBOOK = 1 shl 22

    sealed interface Check {
        data class Valid(val tensors: Int, val sizeBytes: Long) : Check
        data class Invalid(val reason: String) : Check
    }

    fun inspect(file: File): Check {
        if (!file.isFile) return Check.Invalid("file is missing")
        val length = file.length()
        if (length < HEADER_BYTES) return Check.Invalid("file is too small to be a model (${length} bytes)")
        return try {
            RandomAccessFile(file, "r").use { raf -> inspect(raf, length) }
        } catch (e: java.io.IOException) {
            Check.Invalid("could not read the file (${e.message})")
        }
    }

    private fun inspect(raf: RandomAccessFile, length: Long): Check {
        val header = ByteArray(HEADER_BYTES).also { raf.readFully(it) }
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val tag = h.getInt(0)
        when (tag) {
            TAG_GENERATION_3 -> Unit
            TAG_GENERATION_2 -> return Check.Invalid("this is a Needle 2 file; the app needs the Needle 3 / Whistle format")
            else -> return Check.Invalid("not a .cact model (format tag 0x${Integer.toHexString(tag)})")
        }
        val tensors = h.getInt(4)
        val codebook = h.getInt(8)
        if (tensors <= 0 || tensors > MAX_TENSORS) return Check.Invalid("implausible tensor count $tensors")
        if (codebook < 0 || codebook > MAX_CODEBOOK) return Check.Invalid("implausible codebook length $codebook")

        val recordsStart = HEADER_BYTES.toLong() + codebook.toLong() * 4
        val recordsEnd = recordsStart + tensors.toLong() * RECORD_BYTES
        if (recordsEnd > length) return Check.Invalid("file is cut short (the tensor table does not fit)")

        raf.seek(recordsStart)
        val table = ByteArray(tensors * RECORD_BYTES).also { raf.readFully(it) }
        val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until tensors) {
            val base = i * RECORD_BYTES
            val offset = t.getLong(base + 20)
            val size = t.getLong(base + 28)
            if (offset < 0 || size < 0 || offset > length || size > length - offset) {
                return Check.Invalid("file is cut short (tensor $i extends past the end)")
            }
        }
        return Check.Valid(tensors, length)
    }
}
