package app.cactusai.core.info

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerSpeechTest {
    @Test
    fun theCautionIsSpokenWithTheAnswer() {
        val a = InfoAnswer("Final: Tigers 5, Royals 3.", spoken = "The Tigers beat the Royals, five to three.", note = "Not checked: one source only.")
        assertEquals("The Tigers beat the Royals, five to three. Not checked: one source only.", AnswerSpeech.forSpeaker(listOf(a)))
    }

    @Test
    fun aCautionAlreadyInTheWordsIsNotRepeated() {
        val a = InfoAnswer("x", spoken = "It is 72 degrees. Check the source for the current forecast.", note = "check the source for the current forecast.")
        assertEquals("It is 72 degrees. Check the source for the current forecast.", AnswerSpeech.forSpeaker(listOf(a)))
    }

    @Test
    fun severalAnswersAreReadInOrderAndNothingIsReadForNone() {
        val one = InfoAnswer("a", spoken = "First.")
        val two = InfoAnswer("b", spoken = "Second.")
        assertEquals("First. Second.", AnswerSpeech.forSpeaker(listOf(one, two)))
        assertEquals("", AnswerSpeech.forSpeaker(emptyList()))
        assertEquals("", AnswerSpeech.forSpeaker(listOf(InfoAnswer("a", spoken = "  "))))
    }
}
