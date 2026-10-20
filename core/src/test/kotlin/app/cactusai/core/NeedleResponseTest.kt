package app.cactusai.core

import app.cactusai.core.needle.NeedleParse
import app.cactusai.core.needle.NeedleResponses
import app.cactusai.core.needle.TranscriptionParse
import app.cactusai.core.needle.Transcriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedleResponseTest {
    private fun parsed(json: String) = (NeedleResponses.parse(json) as NeedleParse.Parsed).response

    @Test
    fun parsesTheDocumentedEnvelope() {
        // Shape taken from the upstream Needle documentation (llms.txt "Response shape").
        val r = parsed(
            """{"type":"call","success":true,"error":null,"error_code":null,
               "function_calls":[{"name":"set_lights","arguments":{"room":"living room","on":true}}],
               "reasoning":"'living room' -> room","confidence":0.94,"prefill_tps":4300.0,"decode_tps":850.0}""",
        )
        assertTrue(r.success)
        assertEquals("call", r.type)
        assertEquals(1, r.calls.size)
        assertEquals("set_lights", r.calls[0].name)
        assertEquals(0.94, r.confidence!!, 1e-9)
        assertEquals(4300.0, r.prefillTps!!, 1e-9)
        assertTrue(r.ungrounded.isEmpty())
        assertFalse(r.negated)
    }

    @Test
    fun emptyCallsIsARefusalNotAnError() {
        val r = parsed("""{"type":"call","success":true,"function_calls":[],"confidence":0.2}""")
        assertTrue(r.calls.isEmpty())
        assertTrue(r.success)
    }

    @Test
    fun readsSuppressedCallsAndValidation() {
        val r = parsed(
            """{"type":"call","success":true,"function_calls":[],
               "suppressed_calls":[{"name":"open_app","arguments":{"app_name":"Spotify"}}],
               "validation":{"ungrounded":["open_app.app_name"],"negation":["open_app"]}}""",
        )
        assertEquals(1, r.suppressedCalls.size)
        assertEquals(listOf("open_app.app_name"), r.ungrounded)
        assertTrue(r.negated)
    }

    @Test
    fun errorEnvelopeIsNotSuccess() {
        val r = parsed("""{"success":false,"error":"decode failed","error_code":"decode","function_calls":[]}""")
        assertFalse(r.success)
        assertEquals("decode failed", r.error)
    }

    @Test
    fun confidenceOutsideZeroToOneIsTreatedAsUnknown() {
        assertNull(parsed("""{"function_calls":[],"confidence":1.7}""").confidence)
        assertNull(parsed("""{"function_calls":[],"confidence":"high"}""").confidence)
        assertNull(parsed("""{"function_calls":[],"confidence":null}""").confidence)
    }

    @Test
    fun acceptsArgumentsWrappedAsAJsonString() {
        val r = parsed("""{"function_calls":[{"name":"open_app","arguments":"{\"app_name\":\"Maps\"}"}]}""")
        assertTrue(r.calls[0].arguments.toString().contains("Maps"))
    }

    @Test
    fun malformedEnvelopesAreReportedNotThrown() {
        for (bad in listOf("", "not json", "[]", """{"function_calls":"open_app"}""", """{"function_calls":[1]}""", """{"function_calls":[{"arguments":{}}]}""", """{"function_calls":[{"name":"","arguments":{}}]}""", """{"a":1,"a":2}""")) {
            assertTrue(bad, NeedleResponses.parse(bad) is NeedleParse.Malformed)
        }
    }

    @Test
    fun transcriptionParsing() {
        val r = (Transcriptions.parse("""{"text":"  turn off the kitchen lights ","language":"en","ttft_ms":13.6,"decode_tps":1264.4}""") as TranscriptionParse.Parsed).result
        assertEquals("turn off the kitchen lights", r.text)
        assertEquals("en", r.language)
        assertEquals(13.6, r.ttftMs!!, 1e-9)
        assertFalse(r.isEmpty)
    }

    @Test
    fun silenceIsAnEmptyTranscript() {
        val r = (Transcriptions.parse("""{"text":"","language":"","ttft_ms":0.0,"decode_tps":0.0}""") as TranscriptionParse.Parsed).result
        assertTrue(r.isEmpty)
        assertNull(r.language)
    }

    @Test
    fun badTranscriptionEnvelope() {
        assertTrue(Transcriptions.parse("nope") is TranscriptionParse.Malformed)
        assertTrue(Transcriptions.parse("""{"text":5}""") is TranscriptionParse.Malformed)
    }
}
