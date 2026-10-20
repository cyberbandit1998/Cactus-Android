package app.cactusai.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.cactusai.core.models.ModelStatus
import app.cactusai.core.models.Models
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TestSupport {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val container: AppContainer get() = (context as CactusApp).container

    /** The AI tests need the two real models already downloaded through the app (Models tab). */
    fun modelsInstalled(): Boolean = Models.all.all { container.modelStore.status(it) is ModelStatus.Installed }

    fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    /** Reads a 16-bit PCM mono WAV from the test APK's assets into floats in [-1, 1]. */
    fun readWavAsset(path: String): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "$path is not a WAV file" }
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = buf.getInt(at + 4)
            if (id == "fmt ") {
                channels = buf.getShort(at + 10).toInt()
                rate = buf.getInt(at + 12)
                bits = buf.getShort(at + 22).toInt()
            } else if (id == "data") {
                require(channels == 1 && rate == 16_000 && bits == 16) { "$path must be 16 kHz mono 16-bit (is $rate Hz, $channels ch, $bits bit)" }
                return FloatArray(size / 2) { buf.getShort(at + 8 + it * 2) / 32768f }
            }
            at += 8 + size + (size and 1)
        }
        error("$path has no data chunk")
    }
}
