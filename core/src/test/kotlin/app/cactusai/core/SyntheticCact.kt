package app.cactusai.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TEST FIXTURE ONLY. Builds a byte string that has the structure of a `.cact` archive (header, codebook,
 * tensor table, data) so the structural checks and the downloader can be tested without the real
 * models. It contains no model weights and can never be loaded by the engine.
 */
object SyntheticCact {
    fun build(tensors: Int = 3, codebookWords: Int = 4, dataPerTensor: Int = 1000, tag: Int = 0x05E12A84, testKind: Int = 0): ByteArray {
        val header = 196
        val tableStart = header + codebookWords * 4
        val dataStart = tableStart + tensors * 44
        val total = dataStart + tensors * dataPerTensor
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, tag)
        buf.putInt(4, tensors)
        buf.putInt(8, codebookWords)
        buf.putInt(12, testKind) // only the host-test engine double reads this: 1 = text, 2 = speech
        for (i in 0 until tensors) {
            val rec = tableStart + i * 44
            buf.putLong(rec + 20, (dataStart + i * dataPerTensor).toLong())
            buf.putLong(rec + 28, dataPerTensor.toLong())
        }
        for (i in dataStart until total) buf.put(i, (i * 31).toByte())
        return buf.array()
    }
}
