package app.cactusai.core.json

import java.math.BigDecimal

/**
 * Minimal strict JSON model, parser and writer.
 *
 * Model output is untrusted input, so the parser is deliberately strict: RFC 8259 grammar only,
 * bounded depth and size, and duplicate object keys are rejected rather than silently overwritten.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue

    /** Keeps the literal text so integers stay exact. */
    data class Num(val raw: String) : JsonValue {
        fun toDouble(): Double = raw.toDouble()

        /** The value as a Long when it is a whole number (15, 15.0, 1.5e1), otherwise null. */
        fun toLongOrNull(): Long? = try {
            BigDecimal(raw).stripTrailingZeros().takeIf { it.scale() <= 0 }?.longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
        fun string(key: String): String? = (fields[key] as? Str)?.value
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun double(key: String): Double? = (fields[key] as? Num)?.toDouble()
        fun long(key: String): Long? = (fields[key] as? Num)?.toLongOrNull()
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun array(key: String): List<JsonValue>? = (fields[key] as? Arr)?.items
    }
}

class JsonParseException(message: String, val offset: Int) : Exception("$message at offset $offset")

object Json {
    const val MAX_DEPTH = 32
    const val MAX_INPUT_CHARS = 2_000_000

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    }

    fun write(value: JsonValue): String = StringBuilder().also { write(value, it, null, 0) }.toString()

    fun writePretty(value: JsonValue): String = StringBuilder().also { write(value, it, "  ", 0) }.toString()

    private fun write(value: JsonValue, out: StringBuilder, indent: String?, level: Int) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    newline(out, indent, level + 1)
                    write(item, out, indent, level + 1)
                }
                newline(out, indent, level)
                out.append(']')
            }
            is JsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent, level + 1)
                    writeString(k, out)
                    out.append(if (indent == null) ":" else ": ")
                    write(v, out, indent, level + 1)
                }
                newline(out, indent, level)
                out.append('}')
            }
        }
    }

    private fun newline(out: StringBuilder, indent: String?, level: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(level) { out.append(indent) }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' || c == ' ' || c == ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            if (s.length > MAX_INPUT_CHARS) throw JsonParseException("input too large", 0)
            skipWs()
            val v = parseValue(0)
            skipWs()
            if (i != s.length) fail("trailing characters")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonParseException(msg, i)

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return value
        }

        private fun parseObject(depth: Int): JsonValue {
            i++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("expected string key")
                val key = parseString()
                if (fields.containsKey(key)) fail("duplicate key \"$key\"")
                skipWs()
                if (peek() != ':') fail("expected ':'")
                i++
                skipWs()
                fields[key] = parseValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue {
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWs()
                items += parseValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short unicode escape")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail("bad unicode escape")
                                }
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                fail("bad number")
            }
            if (i < s.length && s[i] == '.') {
                i++
                val digitsStart = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digitsStart) fail("bad fraction")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val digitsStart = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digitsStart) fail("bad exponent")
            }
            return JsonValue.Num(s.substring(start, i))
        }
    }
}

/** Builds a JSON value from plain Kotlin values; used for tool schemas and history records. */
fun jsonOf(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is JsonValue -> value
    is Boolean -> JsonValue.Bool(value)
    is Int, is Long, is Short, is Byte -> JsonValue.Num(value.toString())
    is Double -> {
        require(value.isFinite()) { "non-finite number" }
        JsonValue.Num(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
    }
    is Float -> jsonOf(value.toDouble())
    is String -> JsonValue.Str(value)
    is Enum<*> -> JsonValue.Str(value.name.lowercase())
    is Map<*, *> -> JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { m ->
        value.forEach { (k, v) -> m[k as String] = jsonOf(v) }
    })
    is Iterable<*> -> JsonValue.Arr(value.map { jsonOf(it) })
    else -> throw IllegalArgumentException("cannot convert ${value::class.simpleName} to JSON")
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonValue.Obj =
    JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { m -> pairs.forEach { (k, v) -> m[k] = jsonOf(v) } })
