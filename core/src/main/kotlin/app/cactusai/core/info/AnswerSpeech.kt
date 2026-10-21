package app.cactusai.core.info

/** What the speaker is given for a command's answers: the spoken wording, followed by any caution that goes with it. */
object AnswerSpeech {
    fun forSpeaker(answers: List<InfoAnswer>): String =
        answers.mapNotNull { a ->
            val body = a.spoken.trim()
            val caution = a.note?.trim()?.takeIf { it.isNotEmpty() && !body.contains(it, ignoreCase = true) }
            listOfNotNull(body.ifEmpty { null }, caution).joinToString(" ").ifEmpty { null }
        }.joinToString(" ")
}
