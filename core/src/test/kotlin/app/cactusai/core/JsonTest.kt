package app.cactusai.core

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonParseException
import app.cactusai.core.json.JsonValue
import app.cactusai.core.json.jsonObjectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedDocument() {
        val v = Json.parse("""{"a":[1,2.5,"x",true,null],"b":{"c":"d"}}""") as JsonValue.Obj
        assertEquals(5, v.array("a")!!.size)
        assertEquals("d", v.obj("b")!!.string("c"))
    }

    @Test
    fun wholeNumbersAreExact() {
        val v = Json.parse("""{"n":15,"f":15.0,"e":1.5e1,"x":15.5}""") as JsonValue.Obj
        assertEquals(15L, v.long("n"))
        assertEquals(15L, v.long("f"))
        assertEquals(15L, v.long("e"))
        assertNull(v.long("x"))
    }

    @Test
    fun rejectsDuplicateKeys() = assertRejected("""{"a":1,"a":2}""")

    @Test
    fun rejectsTrailingGarbage() = assertRejected("""{"a":1} x""")

    @Test
    fun rejectsTrailingComma() = assertRejected("""[1,2,]""")

    @Test
    fun rejectsSingleQuotes() = assertRejected("""{'a':1}""")

    @Test
    fun rejectsRawControlCharacterInString() = assertRejected("\"a\nb\"")

    @Test
    fun rejectsLeadingZeroAndBareDot() {
        assertRejected("01")
        assertRejected("1.")
        assertRejected("-")
        assertRejected("1e")
    }

    @Test
    fun rejectsExcessiveNesting() {
        assertRejected("[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5))
    }

    @Test
    fun rejectsBadUnicodeEscape() {
        assertRejected("\"\\u12G4\"")
        assertRejected("\"\\u+123\"")
        assertRejected("\"\\u12\"")
    }

    @Test
    fun decodesEscapesAndSurrogatePairs() {
        assertEquals("a\"b\\c/\n\u00e9", (Json.parse("\"a\\\"b\\\\c\\/\\n\\u00e9\"") as JsonValue.Str).value)
        assertEquals("\uD83D\uDE00", (Json.parse("\"\\ud83d\\ude00\"") as JsonValue.Str).value)
    }

    @Test
    fun writerEscapesAndRoundTrips() {
        val original = jsonObjectOf("text" to "line1\nquote\" back\\slash \u2028 tab\t", "n" to 3, "list" to listOf(1, "two", null, true))
        val written = Json.write(original)
        assertTrue(!written.contains('\n'))
        assertEquals(original, Json.parse(written))
        assertEquals(original, Json.parse(Json.writePretty(original)))
    }

    @Test
    fun parseOrNullDoesNotThrow() {
        assertNull(Json.parseOrNull("{"))
    }

    private fun assertRejected(text: String) {
        try {
            Json.parse(text)
            fail("should have been rejected: $text")
        } catch (_: JsonParseException) {
        }
    }
}
