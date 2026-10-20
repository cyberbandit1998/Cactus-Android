package app.cactusai.core.history

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import app.cactusai.core.json.jsonObjectOf
import app.cactusai.core.metrics.Timings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class InputSource { VOICE, TEXT }

enum class Outcome { SUCCESS, FAILED, REFUSED, CANCELLED, DECLINED, NEEDS_INPUT }

data class HistoryEntry(
    val id: Long,
    val timeMs: Long,
    val source: InputSource,
    /** Recognised speech, or the typed command. */
    val text: String,
    /** The validated tool calls as short text, e.g. `open_app{app_name=Spotify}`. */
    val interpretation: String?,
    val outcome: Outcome,
    val message: String,
    val timings: Timings?,
)

object HistoryCodec {
    fun encode(entries: List<HistoryEntry>): String =
        Json.write(JsonValue.Arr(entries.map(::toJson)))

    /** Skips any record that does not parse so one bad entry never loses the rest. */
    fun decode(text: String): List<HistoryEntry> {
        val arr = (Json.parseOrNull(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return arr.mapNotNull { (it as? JsonValue.Obj)?.let(::fromJson) }
    }

    private fun toJson(e: HistoryEntry): JsonValue.Obj {
        val t = e.timings
        return jsonObjectOf(
            "id" to e.id,
            "time" to e.timeMs,
            "source" to e.source,
            "text" to e.text,
            "interpretation" to e.interpretation,
            "outcome" to e.outcome,
            "message" to e.message,
            "timings" to t?.let {
                jsonObjectOf(
                    "load" to it.modelLoadMs, "record" to it.recordMs, "transcribe" to it.transcribeMs,
                    "interpret" to it.interpretMs, "execute" to it.executeMs, "total" to it.totalMs,
                    "wTtft" to it.whistleTtftMs, "wTps" to it.whistleDecodeTps,
                    "nPrefill" to it.needlePrefillTps, "nTps" to it.needleDecodeTps,
                )
            },
        )
    }

    private fun fromJson(o: JsonValue.Obj): HistoryEntry? {
        val id = o.long("id") ?: return null
        val time = o.long("time") ?: return null
        val source = enumOrNull<InputSource>(o.string("source")) ?: return null
        val outcome = enumOrNull<Outcome>(o.string("outcome")) ?: return null
        val t = o.obj("timings")?.let {
            Timings(
                modelLoadMs = it.long("load"), recordMs = it.long("record"), transcribeMs = it.long("transcribe"),
                interpretMs = it.long("interpret"), executeMs = it.long("execute"), totalMs = it.long("total"),
                whistleTtftMs = it.double("wTtft"), whistleDecodeTps = it.double("wTps"),
                needlePrefillTps = it.double("nPrefill"), needleDecodeTps = it.double("nTps"),
            )
        }
        return HistoryEntry(id, time, source, o.string("text").orEmpty(), o.string("interpretation"), outcome, o.string("message").orEmpty(), t)
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** Command history, stored only in a private app file and capped; [clear] deletes it. */
class HistoryStore(private val file: File, private val maxEntries: Int = 500) {
    private val lock = Any()
    private var cache: List<HistoryEntry>? = null

    fun all(): List<HistoryEntry> = synchronized(lock) { load() }

    fun add(entry: HistoryEntry): List<HistoryEntry> = synchronized(lock) {
        val updated = (listOf(entry) + load()).take(maxEntries)
        save(updated)
        updated
    }

    fun clear() = synchronized(lock) {
        cache = emptyList()
        file.delete()
        Unit
    }

    private fun load(): List<HistoryEntry> {
        cache?.let { return it }
        val loaded = try {
            if (file.isFile) HistoryCodec.decode(file.readText()) else emptyList()
        } catch (_: IOException) {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    private fun save(entries: List<HistoryEntry>) {
        cache = entries
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(HistoryCodec.encode(entries))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // History is a convenience; a full disk must not break commands.
        }
    }
}
