package app.cactusai.core.needle

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonParseException
import app.cactusai.core.json.JsonValue
import app.cactusai.core.tools.RawCall

/** What `needle_complete` returned, parsed but not yet trusted. */
data class NeedleResponse(
    val type: String?,
    val success: Boolean,
    val error: String?,
    val errorCode: String?,
    val calls: List<RawCall>,
    /** A call the engine held back (confidence below its floor or a grounding gate fired). */
    val suppressedCalls: List<RawCall>,
    val reasoning: String?,
    /** Calibrated 0..1, or null when the weights carry no confidence head. */
    val confidence: Double?,
    /** `tool.field` names whose value is not grounded in what the user said. */
    val ungrounded: List<String>,
    /** The engine flagged that the request negates or excludes the call. */
    val negated: Boolean,
    val prefillTps: Double?,
    val decodeTps: Double?,
    val rawJson: String,
)

sealed interface NeedleParse {
    data class Parsed(val response: NeedleResponse) : NeedleParse
    data class Malformed(val reason: String) : NeedleParse
}

object NeedleResponses {
    fun parse(json: String): NeedleParse {
        val root = try {
            Json.parse(json)
        } catch (e: JsonParseException) {
            return NeedleParse.Malformed("not valid JSON (${e.message})")
        }
        if (root !is JsonValue.Obj) return NeedleParse.Malformed("expected a JSON object")

        val calls = parseCalls(root, "function_calls") ?: return NeedleParse.Malformed("function_calls is not a list of calls")
        val suppressed = parseCalls(root, "suppressed_calls") ?: return NeedleParse.Malformed("suppressed_calls is not a list of calls")

        val errorText = when (val e = root["error"]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> e.value.ifBlank { null }
            else -> Json.write(e)
        }
        val success = root.bool("success") ?: (errorText == null)

        val confidence = root.double("confidence")?.takeIf { it in 0.0..1.0 }

        val validation = root.obj("validation")
        val ungrounded = validation?.array("ungrounded").orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
        val negated = when (val n = validation?.get("negation")) {
            null, JsonValue.Null -> false
            is JsonValue.Bool -> n.value
            is JsonValue.Arr -> n.items.isNotEmpty()
            is JsonValue.Obj -> n.fields.isNotEmpty()
            is JsonValue.Str -> n.value.isNotBlank()
            is JsonValue.Num -> true
        }

        return NeedleParse.Parsed(
            NeedleResponse(
                type = root.string("type"),
                success = success,
                error = errorText,
                errorCode = (root["error_code"] as? JsonValue.Str)?.value,
                calls = calls,
                suppressedCalls = suppressed,
                reasoning = root.string("reasoning"),
                confidence = confidence,
                ungrounded = ungrounded,
                negated = negated,
                prefillTps = root.double("prefill_tps"),
                decodeTps = root.double("decode_tps"),
                rawJson = json,
            ),
        )
    }

    private fun parseCalls(root: JsonValue.Obj, key: String): List<RawCall>? {
        val value = root[key] ?: return emptyList()
        if (value == JsonValue.Null) return emptyList()
        val items = (value as? JsonValue.Arr)?.items ?: return null
        return items.map { item ->
            val obj = item as? JsonValue.Obj ?: return null
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return null
            // Some engines wrap arguments as a JSON string; accept that form but never anything looser.
            val args = when (val a = obj["arguments"]) {
                is JsonValue.Str -> Json.parseOrNull(a.value) ?: a
                else -> a
            }
            RawCall(name, args)
        }
    }
}

/** What `needle_transcribe` returned. */
data class TranscriptionResult(
    val text: String,
    val language: String?,
    val ttftMs: Double?,
    val decodeTps: Double?,
    val rawJson: String,
) {
    /** Silence and steady noise come back as an empty transcript, not an invented sentence. */
    val isEmpty: Boolean get() = text.isBlank()
}

sealed interface TranscriptionParse {
    data class Parsed(val result: TranscriptionResult) : TranscriptionParse
    data class Malformed(val reason: String) : TranscriptionParse
}

object Transcriptions {
    fun parse(json: String): TranscriptionParse {
        val root = Json.parseOrNull(json) as? JsonValue.Obj ?: return TranscriptionParse.Malformed("not a JSON object")
        val text = root["text"]
        if (text != null && text !is JsonValue.Str) return TranscriptionParse.Malformed("text is not a string")
        return TranscriptionParse.Parsed(
            TranscriptionResult(
                text = root.string("text").orEmpty().trim(),
                language = root.string("language")?.ifBlank { null },
                ttftMs = root.double("ttft_ms"),
                decodeTps = root.double("decode_tps"),
                rawJson = json,
            ),
        )
    }
}
