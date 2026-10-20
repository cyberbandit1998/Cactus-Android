package app.cactusai.core.info

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

sealed interface CalcResult {
    data class Value(val value: BigDecimal, val shown: String) : CalcResult
    data class Error(val message: String) : CalcResult
}

/** Spoken number words to digits: "twenty five" -> "25", "one hundred and five" -> "105", "three point five" -> "3.5". */
object NumberWords {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val scales = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)

    private fun isNumberWord(w: String) = w in units || w in tens || w == "hundred" || w in scales

    fun convert(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            if (!isNumberWord(words[i])) {
                out += words[i++]
                continue
            }
            var total = 0L
            var current = 0L
            var j = i
            while (j < words.size) {
                val w = words[j]
                when {
                    w in units -> current += units.getValue(w)
                    w in tens -> current += tens.getValue(w)
                    w == "hundred" -> current = (if (current == 0L) 1 else current) * 100
                    w in scales -> {
                        total += (if (current == 0L) 1 else current) * scales.getValue(w)
                        current = 0
                    }
                    w == "and" && j + 1 < words.size && isNumberWord(words[j + 1]) && (current >= 100 || total > 0) -> Unit
                    else -> break
                }
                j++
            }
            var number = (total + current).toString()
            if (j + 1 < words.size && words[j] == "point" && words[j + 1] in units) {
                val digits = StringBuilder()
                var k = j + 1
                while (k < words.size && words[k] in units && units.getValue(words[k]) < 10) digits.append(units.getValue(words[k++]))
                if (digits.isNotEmpty()) {
                    number += ".$digits"
                    j = k
                }
            }
            out += number
            i = j
        }
        return out.joinToString(" ")
    }
}

/**
 * A small, safe arithmetic evaluator for "What's 15 percent of 80?" and "twelve times seven". It reads numbers,
 * + - * / ^, parentheses, percent, square root, squared and cubed. It never evaluates code: input it cannot read as
 * arithmetic is refused. Exact decimal arithmetic is used, so 0.1 + 0.2 is 0.3.
 */
object Calculator {
    private val leadIn = Regex("""^(?:what is|what's|whats|calculate|compute|how much is|how much|work out|solve|tell me)\s+""")
    private val mc = MathContext(20, RoundingMode.HALF_EVEN)
    private const val MAX_LENGTH = 120
    private val limit = BigDecimal("1E30")

    fun evaluate(raw: String): CalcResult {
        if (raw.length > MAX_LENGTH) return CalcResult.Error("That calculation is too long.")
        var expr = raw.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!', ' ')
        expr = expr.replace(Regex("""\s+(equals|please)$"""), "")
        repeat(2) { expr = leadIn.replace(expr, "") }
        val display = expr.trim()
        expr = Regex("""(?<=\d),(?=\d{3})""").replace(expr, "")
        expr = NumberWords.convert(expr.replace(Regex("""\s+"""), " "))
        expr = Regex("""(\d+(?:\.\d+)?)\s*(?:%|percent)\s*of\s*""").replace(expr) { "(${it.groupValues[1]}/100)*" }
        expr = expr
            .replace("square root of", " sqrt ").replace("square root", " sqrt ")
            .replace("to the power of", "^").replace("raised to", "^")
            .replace(Regex("""\bsquared\b"""), "^2").replace(Regex("""\bcubed\b"""), "^3")
            .replace(Regex("""\bplus\b"""), "+").replace(Regex("""\b(minus|less)\b"""), "-")
            .replace(Regex("""\b(times|multiplied by)\b"""), "*").replace(Regex("""\b(divided by|over)\b"""), "/")
            .replace("×", "*").replace("÷", "/")
            .replace(Regex("""(?<=\d)\s*x\s*(?=\d)"""), "*")
            .replace(Regex("""(\d+(?:\.\d+)?)\s*(?:%|percent)"""), "($1/100)")
        val compact = expr.replace(" ", "")
        if (compact.isEmpty()) return CalcResult.Error("There is nothing to calculate.")
        if (!compact.replace("sqrt", "").all { it.isDigit() || it in "+-*/^()." }) {
            return CalcResult.Error("I can only do arithmetic like 15 percent of 80 or 12 times 7.")
        }
        return try {
            val value = Parser(compact).parse()
            if (value.abs() > limit) return CalcResult.Error("That number is too big for me to say.")
            val shown = format(value)
            CalcResult.Value(value, "$display is $shown.")
        } catch (e: CalcException) {
            CalcResult.Error(e.message ?: "I could not work that out.")
        } catch (_: ArithmeticException) {
            CalcResult.Error("I could not work that out.")
        }
    }

    /** The value as people say it: no trailing zeros, at most 10 decimals, thousands separated for whole numbers. */
    fun format(value: BigDecimal): String {
        val rounded = value.setScale(10, RoundingMode.HALF_EVEN).stripTrailingZeros()
        val plain = if (rounded.scale() < 0) rounded.setScale(0).toPlainString() else rounded.toPlainString()
        val negative = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    /** The calculation with symbols spelled out, for the speaker: "12 * 7" -> "12 times 7". */
    fun spokenForm(shown: String): String = shown
        .replace(" * ", " times ").replace("*", " times ").replace(" / ", " divided by ").replace("/", " divided by ")
        .replace(" + ", " plus ").replace("+", " plus ").replace("^2", " squared").replace("^3", " cubed").replace("^", " to the power of ")
        .replace("%", " percent").replace(Regex("""(?<=\d)\s*-\s*(?=\d)"""), " minus ").replace(Regex("""\s+"""), " ").trim()

    private class CalcException(message: String) : Exception(message)

    private class Parser(private val s: String) {
        private var pos = 0
        private var depth = 0

        fun parse(): BigDecimal {
            val v = expr()
            if (pos != s.length) throw CalcException("I could not read that as arithmetic.")
            return v
        }

        private fun peek(): Char? = s.getOrNull(pos)

        private fun expr(): BigDecimal {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v.add(term(), mc) }
                    '-' -> { pos++; v.subtract(term(), mc) }
                    else -> return v
                }
                check(v)
            }
        }

        private fun term(): BigDecimal {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v = v.multiply(power(), mc) }
                    '/' -> {
                        pos++
                        val d = power()
                        if (d.signum() == 0) throw CalcException("I can't divide by zero.")
                        v = v.divide(d, mc)
                    }
                    else -> return v
                }
                check(v)
            }
        }

        private fun power(): BigDecimal {
            val base = unary()
            if (peek() != '^') return base
            pos++
            val exponent = power()
            return pow(base, exponent)
        }

        private fun unary(): BigDecimal {
            if (++depth > 40) throw CalcException("That calculation is too deeply nested.")
            try {
                return when (peek()) {
                    '-' -> { pos++; unary().negate() }
                    '+' -> { pos++; unary() }
                    else -> primary()
                }
            } finally {
                depth--
            }
        }

        private fun primary(): BigDecimal {
            val c = peek() ?: throw CalcException("That calculation is incomplete.")
            if (c == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw CalcException("A bracket is not closed.")
                pos++
                return v
            }
            if (s.startsWith("sqrt", pos)) {
                pos += 4
                val v = unary()
                if (v.signum() < 0) throw CalcException("I can't take the square root of a negative number.")
                return v.sqrt(mc)
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toBigDecimalOrNull() ?: throw CalcException("I could not read that number.")
            }
            throw CalcException("I could not read that as arithmetic.")
        }

        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val n = exponent.stripTrailingZeros()
            if (n.scale() <= 0 && n.abs() <= BigDecimal(64)) {
                val e = n.toInt()
                if (base.signum() == 0 && e < 0) throw CalcException("I can't divide by zero.")
                if (base.abs() > BigDecimal("1E12") && e > 2) throw CalcException("That number is too big for me to say.")
                val p = base.pow(kotlin.math.abs(e), mc)
                return if (e >= 0) p else BigDecimal.ONE.divide(p, mc)
            }
            val d = Math.pow(base.toDouble(), exponent.toDouble())
            if (!d.isFinite()) throw CalcException("I could not work that out.")
            return BigDecimal(d, mc)
        }

        private fun check(v: BigDecimal) {
            if (v.abs() > BigDecimal("1E60")) throw CalcException("That number is too big for me to say.")
        }
    }
}
