package app.cactusai.core.info

import app.cactusai.core.match.TextNorm
import java.time.LocalDate

/** A question that can be answered on the phone, with the local tool and arguments that answer it. */
data class LocalRoute(val tool: String, val args: Map<String, String>)

/**
 * The safety net behind "do not send locally answerable questions to a web service". Needle chooses the tool, but a small
 * model can send "what time is it" to the general-question tool. Before any question goes online this recognises the
 * plain time, date, battery, device and arithmetic questions and answers them with the local tools instead.
 *
 * The patterns are anchored on purpose: "what time is it" is local, "what time is the Michigan game" is not, and an
 * unrecognised day such as "my birthday" is never rerouted (it would only produce a useless local refusal).
 */
object LocalGuard {
    private val timeNow = Regex("""(?:what is |whats |tell me |can you tell me |do you know )?(?:the )?(?:current |local )?time(?: is it)?(?: right)?(?: now)?(?: please)?""")
    private val whatTimeIsIt = Regex("""what time is it(?: right now| now| currently)?(?: please)?""")
    private val timeIn = Regex("""(?:what is |what |whats |tell me )?(?:the )?(?:current |local )?time (?:is it )?in (.+)""")
    private val dateNow = Regex("""(?:what is |whats |tell me )?(?:the )?(?:todays |today s )?(?:current )?date(?: today)?(?: please)?""")
    private val dayQuestion = Regex("""what (?:day|date) (?:is|was) (it|today|tomorrow|yesterday|the day after tomorrow)(?: today)?""")
    private val weekdayOf = Regex("""what (?:day|day of the week|date) (?:is|was|will be) (.+)""")
    private val daysUntil = Regex("""how many days (?:are there |is it |are left |do i have )?(?:until|till|to|since|from) (.+)""")
    private val daysUntil2 = Regex("""days (?:until|till|since) (.+)""")
    private val battery = Regex("""(?:what is |whats |how is |how much is )?(?:my |the )?(?:phone s |phones )?battery(?: percentage| percent| level| life| left| charge)?(?: is| at)?(?: left)?""")
    private val charging = Regex("""(?:is|are) (?:my |the )?(?:phone|device|battery)? ?(?:currently )?(?:charging|plugged in)(?: right now| now)?""")
    private val androidVersion = Regex(
        """(?:what is |whats |what |tell me )?(?:my |the )?(?:phone s |phones )?android version(?: am i running| do i have| is this| is my phone running| is installed)?""" +
            """|what version of android(?: am i running| do i have| is this| is my phone running)?""",
    )
    private val storage = Regex("""how much (?:storage|space|memory) (?:do i have |is )?(?:left|free|available|remaining)(?: on my phone)?|how much (?:free )?(?:storage|space) (?:do i have|is left|is free)(?: left| free| on my phone)?""")
    private val model = Regex("""what (?:phone|model) (?:is this|do i have|am i using)|what is my phone model""")

    fun reroute(question: String, today: LocalDate): LocalRoute? {
        val q = TextNorm.normalize(question.replace("'", ""))
        if (q.isEmpty()) return null

        timeIn.matchEntire(q)?.let { return LocalRoute("get_time", mapOf("place" to it.groupValues[1])) }
        if (whatTimeIsIt.matches(q) || timeNow.matches(q)) return LocalRoute("get_time", emptyMap())

        if (dateNow.matches(q)) return LocalRoute("get_date", emptyMap())
        dayQuestion.matchEntire(q)?.let { return LocalRoute("get_date", mapOf("day" to it.groupValues[1].let { d -> if (d == "it") "today" else d })) }
        weekdayOf.matchEntire(q)?.let { m ->
            val day = m.groupValues[1].removePrefix("the ").trim()
            if (DateInfo.resolve(day, today) != null) return LocalRoute("get_date", mapOf("day" to day))
        }
        (daysUntil.matchEntire(q) ?: daysUntil2.matchEntire(q))?.let { m ->
            val day = m.groupValues[1].trim()
            if (DateInfo.resolve(day, today) != null) return LocalRoute("get_date", mapOf("day" to day))
        }

        if (charging.matches(q)) return LocalRoute("device_info", mapOf("topic" to "charging"))
        if (battery.matches(q)) return LocalRoute("check_battery", emptyMap())
        if (androidVersion.matches(q)) return LocalRoute("device_info", mapOf("topic" to "android_version"))
        if (storage.matches(q)) return LocalRoute("device_info", mapOf("topic" to "storage"))
        if (model.matches(q)) return LocalRoute("device_info", mapOf("topic" to "model"))

        // Plain arithmetic: it must contain an operator word or symbol so that "what is 5" or a year is not a sum.
        if (q.any { it.isDigit() } || NumberWords.convert(q) != q) {
            val operator = Regex("""\b(plus|minus|times|divided by|multiplied by|percent of|squared|cubed|square root|to the power of)\b|[+*/^]|\d\s*x\s*\d""")
            if (operator.containsMatchIn(question.lowercase()) && Calculator.evaluate(question) is CalcResult.Value) {
                return LocalRoute("calculate", mapOf("expression" to question.trim()))
            }
        }
        return null
    }
}
