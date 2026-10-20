package app.cactusai.core.tools

import app.cactusai.core.json.JsonValue

/**
 * How much wording goes into the tool list the model reads. Shorter levels are fallbacks for a small context
 * window; MINIMAL also leaves out the optional `triggers` so it is the least likely to be refused.
 */
enum class ToolDetail { FULL, COMPACT, MINIMAL }

/**
 * Which tools the model is shown. SCREEN tools need the user-enabled accessibility service. INFO tools are answered
 * entirely on the phone (time, date, device facts, arithmetic). ONLINE tools (sports, weather, general questions) are
 * answered from a web search the user switched on; their questions are the only thing that ever leaves the phone.
 */
enum class ToolGroup { PHONE, SCREEN, INFO, ONLINE }

enum class Sensitivity {
    /** May run as soon as the model is confident. Android itself still shows its own UI where it applies. */
    SAFE,

    /** Confirmed in-app first unless the user turned that confirmation off in settings. */
    CONFIRM_BY_DEFAULT,

    /** Always confirmed in-app; no setting can relax it. */
    ALWAYS_CONFIRM,
}

enum class ParamType { STRING, INTEGER }

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    /** Closed set of accepted values (STRING only). Matching is case-insensitive; the lowercase form is kept. */
    val enumValues: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    /** Maximum length in Unicode code points (STRING only). */
    val maxLength: Int = 120,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "bad parameter name: $name" }
        require(type == ParamType.STRING || enumValues.isEmpty()) { "enum is only for strings" }
        require(enumValues.all { it == it.lowercase() && it.isNotBlank() }) { "enum values must be lowercase" }
    }
}

/** Validated argument values: strings and whole numbers only. */
class ToolArgs(private val values: Map<String, Any>) {
    fun string(name: String): String? = values[name] as? String
    fun long(name: String): Long? = values[name] as? Long
    fun int(name: String): Int? = (values[name] as? Long)?.toInt()
    fun has(name: String): Boolean = values.containsKey(name)
    fun toMap(): Map<String, Any> = values

    override fun toString(): String = values.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }
    override fun equals(other: Any?): Boolean = other is ToolArgs && other.values == values
    override fun hashCode(): Int = values.hashCode()
}

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec>,
    val group: ToolGroup,
    val sensitivity: Sensitivity,
    /** Case-insensitive regexes the engine uses to route an unambiguous request to this tool (Needle `triggers`). */
    val triggers: List<String> = emptyList(),
    /** Extra rule over already type-checked arguments; returns an error message or null. */
    val crossCheck: (ToolArgs) -> String? = { null },
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "bad tool name: $name" }
        require(params.map { it.name }.toSet().size == params.size) { "duplicate parameter in $name" }
    }
}

/** A tool call that passed strict validation against the allowlist. Only this type reaches the executors. */
data class ValidatedCall(val tool: ToolSpec, val args: ToolArgs) {
    val name: String get() = tool.name
    override fun toString(): String = "${tool.name}$args"
}

sealed interface ValidationResult {
    data class Ok(val call: ValidatedCall) : ValidationResult
    data class Rejected(val toolName: String?, val reason: String) : ValidationResult
}

/** The raw call as the model produced it, before validation. */
data class RawCall(val name: String, val arguments: JsonValue?)
