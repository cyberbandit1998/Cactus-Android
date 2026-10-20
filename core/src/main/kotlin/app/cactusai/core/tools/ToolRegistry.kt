package app.cactusai.core.tools

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import app.cactusai.core.json.jsonObjectOf
import app.cactusai.core.json.jsonOf
import java.security.MessageDigest

/**
 * The allowlist. The model can only name tools registered here, with exactly the declared arguments.
 * Nothing the model writes is ever interpreted as code, a shell command, a URL or an Intent.
 */
class ToolRegistry(val tools: List<ToolSpec>) {
    private val byName = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names" }
    }

    fun find(name: String): ToolSpec? = byName[name]

    fun enabled(groups: Set<ToolGroup>): List<ToolSpec> = tools.filter { it.group in groups }

    /**
     * The `tools` array handed to `needle_init` (the engine's raw JSON-schema form, plus `triggers`).
     * The engine reads the whole list as part of its prompt and `needle_init` fails if it does not fit the
     * context window, so shorter [ToolDetail] levels exist as fallbacks.
     */
    fun toNeedleToolsJson(groups: Set<ToolGroup>, detail: ToolDetail = ToolDetail.FULL): String =
        Json.write(JsonValue.Arr(enabled(groups).map { schemaOf(it, detail) }))

    /** Stable fingerprint of what the engine was initialised with, so unchanged tool sets are not re-initialised. */
    fun signature(groups: Set<ToolGroup>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toNeedleToolsJson(groups).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun validate(name: String, arguments: JsonValue?, groups: Set<ToolGroup>): ValidationResult {
        val tool = byName[name] ?: return rejected(null, "'${display(name)}' is not a tool this assistant has")
        if (tool.group !in groups) return rejected(name, "'$name' needs a feature that is turned off")
        val obj = when (arguments) {
            null, JsonValue.Null -> JsonValue.Obj(emptyMap())
            is JsonValue.Obj -> arguments
            else -> return rejected(name, "arguments for '$name' must be an object")
        }
        val known = tool.params.associateBy { it.name }
        obj.fields.keys.firstOrNull { it !in known }?.let {
            return rejected(name, "'$name' has no argument called '${display(it)}'")
        }
        val clean = LinkedHashMap<String, Any>()
        for (param in tool.params) {
            val raw = obj.fields[param.name]
            if (raw == null || raw == JsonValue.Null) {
                if (param.required) return rejected(name, "'$name' needs '${param.name}'")
                continue
            }
            when (val checked = checkParam(tool.name, param, raw)) {
                is Checked.Bad -> return rejected(name, checked.reason)
                is Checked.Good -> clean[param.name] = checked.value
            }
        }
        val args = ToolArgs(clean)
        tool.crossCheck(args)?.let { return rejected(name, it) }
        return ValidationResult.Ok(ValidatedCall(tool, args))
    }

    private sealed interface Checked {
        data class Good(val value: Any) : Checked
        data class Bad(val reason: String) : Checked
    }

    private fun checkParam(tool: String, p: ParamSpec, raw: JsonValue): Checked {
        val label = "'${p.name}' of '$tool'"
        return when (p.type) {
            ParamType.INTEGER -> {
                val n = (raw as? JsonValue.Num)?.toLongOrNull() ?: return Checked.Bad("$label must be a whole number")
                if (p.min != null && n < p.min) return Checked.Bad("$label must be at least ${p.min}")
                if (p.max != null && n > p.max) return Checked.Bad("$label must be at most ${p.max}")
                Checked.Good(n)
            }
            ParamType.STRING -> {
                val text = (raw as? JsonValue.Str)?.value ?: return Checked.Bad("$label must be text")
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return Checked.Bad("$label is empty")
                findForbiddenChar(trimmed)?.let { return Checked.Bad("$label contains a character that is not allowed") }
                if (trimmed.codePointCount(0, trimmed.length) > p.maxLength) {
                    return Checked.Bad("$label is longer than ${p.maxLength} characters")
                }
                if (p.enumValues.isNotEmpty()) {
                    val match = p.enumValues.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                        ?: return Checked.Bad("$label must be one of ${p.enumValues.joinToString()}")
                    Checked.Good(match)
                } else {
                    Checked.Good(trimmed)
                }
            }
        }
    }

    private fun firstSentence(text: String): String = text.substringBefore(". ").removeSuffix(".")

    private fun rejected(tool: String?, reason: String) = ValidationResult.Rejected(tool, reason)

    private fun display(s: String): String = s.take(40).filter { it in ' '..'~' }

    private fun schemaOf(tool: ToolSpec, detail: ToolDetail): JsonValue.Obj {
        val props = LinkedHashMap<String, Any?>()
        for (p in tool.params) {
            val prop = LinkedHashMap<String, Any?>()
            prop["type"] = if (p.type == ParamType.INTEGER) "integer" else "string"
            if (detail == ToolDetail.FULL) prop["description"] = p.description
            if (p.enumValues.isNotEmpty()) prop["enum"] = p.enumValues
            p.min?.let { prop["minimum"] = it }
            p.max?.let { prop["maximum"] = it }
            props[p.name] = prop
        }
        val parameters = LinkedHashMap<String, Any?>()
        parameters["type"] = "object"
        parameters["properties"] = props
        val required = tool.params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) parameters["required"] = required
        val schema = LinkedHashMap<String, Any?>()
        schema["name"] = tool.name
        schema["description"] = if (detail == ToolDetail.MINIMAL) firstSentence(tool.description) else tool.description
        schema["parameters"] = parameters
        if (tool.triggers.isNotEmpty()) schema["triggers"] = tool.triggers
        return jsonOf(schema) as JsonValue.Obj
    }

    companion object {
        /** First code point that is a control, format (zero-width, bidi), separator, private-use or lone surrogate. */
        fun findForbiddenChar(s: String): Int? {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val type = Character.getType(cp)
                val bad = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt() ||
                    type == Character.UNASSIGNED.toInt()
                if (bad) return cp
                i += Character.charCount(cp)
            }
            return null
        }

        fun describe(call: ValidatedCall): String = Json.write(jsonObjectOf("tool" to call.name, "arguments" to call.args.toMap()))
    }
}
