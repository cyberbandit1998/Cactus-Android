package app.cactusai.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    // ---- text preparation ----

    @Test
    fun linksAreNeverSpelledOut() {
        assertEquals("See the details", SpeechText.clean("See the details https://example.com/a/b?c=d"))
        assertFalse(SpeechText.clean("Go to http://example.org/x/y/z now").contains("/"))
    }

    @Test
    fun symbolsAreSaidAsWords() {
        assertEquals("The Tigers won 5 to 3.", SpeechText.clean("The Tigers won 5–3."))
        assertEquals("High near 62 degrees Fahrenheit and 40 percent rain.", SpeechText.clean("High near 62°F and 40% rain."))
        assertEquals("It is 17 degrees Celsius.", SpeechText.clean("It is 17°C."))
        assertEquals("According to espn dot com: they won", SpeechText.clean("According to espn.com: “they won”"))
        assertEquals("Fish and chips", SpeechText.clean("Fish & chips"))
    }

    @Test
    fun listsBecomeSentencesAndAnAnswerIsNeverTooLong() {
        assertEquals("Here are the results. First thing. Second thing.", SpeechText.clean("Here are the results.\n1. First thing.\n2. Second thing."))
        val long = SpeechText.clean("This is a sentence. ".repeat(200), maxChars = 300)
        assertTrue(long.length <= 300)
        assertTrue(long.endsWith("."))
        assertEquals("", SpeechText.clean("   "))
    }

    @Test
    fun chunksStayWithinTheLimitAndKeepTheirOrder() {
        val text = (1..40).joinToString(" ") { "This is sentence number $it." }
        val chunks = SpeechText.chunks(text, 100)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(chunks.dropLast(1).all { it.endsWith(".") })
        // a word longer than the limit is cut rather than looping forever
        assertEquals(3, SpeechText.chunks("a".repeat(50), 20).size)
        assertEquals(listOf("short"), SpeechText.chunks("short", 100))
    }

    @Test
    fun speechSettingsAreKeptInRange() {
        assertEquals(0.5f, SpeechSettings(rate = 0.1f).safeRate, 0f)
        assertEquals(2.0f, SpeechSettings(rate = 9f).safeRate, 0f)
        assertEquals(0.5f, SpeechSettings(pitch = -1f).safePitch, 0f)
        assertEquals(2.0f, SpeechSettings(pitch = 5f).safePitch, 0f)
        assertEquals(1.25f, SpeechSettings(rate = 1.25f).safeRate, 0f)
    }

    // ---- lifecycle, against a fake engine and fake audio focus ----

    private class FakeEngine(val events: TtsEvents, override val maxInputLength: Int = 4000) : TtsEngine {
        class Spoken(val id: String, val text: String, val settings: SpeechSettings, val append: Boolean)

        val spoken = mutableListOf<Spoken>()
        var stops = 0
        var shutdowns = 0

        override fun speak(utteranceId: String, text: String, settings: SpeechSettings, append: Boolean) {
            spoken += Spoken(utteranceId, text, settings, append)
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    private class FakeFocus(var grant: Boolean = true) : AudioFocus {
        var requests = 0
        var abandons = 0
        var lost: (() -> Unit)? = null

        override fun request(onLost: () -> Unit): Boolean {
            requests++
            lost = onLost
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    private class Rig(
        settings: SpeechSettings = SpeechSettings(),
        var micActive: Boolean = false,
        grant: Boolean = true,
        maxInput: Int = 4000,
        readyAtOnce: Boolean = false,
    ) {
        var current = settings
        val focus = FakeFocus(grant)
        val states = mutableListOf<SpeechState>()
        var engine: FakeEngine? = null
        val session = SpeechSession(
            engineFactory = { events ->
                FakeEngine(events, maxInput).also {
                    engine = it
                    if (readyAtOnce) events.onInitialized(true)
                }
            },
            focus = focus,
            settings = { current },
            microphoneActive = { micActive },
            onState = { states += it },
        )

        fun ready(ok: Boolean = true) {
            session.prepare()
            engine!!.events.onInitialized(ok)
        }

        fun finishLast() {
            val e = engine!!
            e.events.onDone(e.spoken.last().id)
        }
    }

    @Test
    fun anAnswerIsSpokenAfterFocusIsGrantedAndFocusIsGivenBackWhenItEnds() {
        val rig = Rig()
        rig.ready()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(SpeakResult.SPEAKING, rig.session.speak("It's 3:42 PM."))
        assertEquals(SpeechState.SPEAKING, rig.session.state)
        assertEquals(1, rig.focus.requests)
        assertEquals("It's 3:42 PM.", rig.engine!!.spoken.single().text)
        assertEquals(0, rig.focus.abandons)
        rig.finishLast()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(1, rig.focus.abandons)
    }

    @Test
    fun theUsersRateAndPitchReachTheEngine() {
        val rig = Rig(SpeechSettings(rate = 1.5f, pitch = 0.8f, voiceName = "en-us-x-test-local"))
        rig.ready()
        rig.session.speak("Hello there.")
        val s = rig.engine!!.spoken.single().settings
        assertEquals(1.5f, s.safeRate, 0f)
        assertEquals(0.8f, s.safePitch, 0f)
        assertEquals("en-us-x-test-local", s.voiceName)
        // a change made later applies to the next answer
        rig.finishLast()
        rig.current = rig.current.copy(rate = 0.7f)
        rig.session.speak("Again.")
        assertEquals(0.7f, rig.engine!!.spoken.last().settings.safeRate, 0f)
    }

    @Test
    fun anAnswerRequestedWhileTheEngineStartsIsKeptAndSpokenWhenReady() {
        val rig = Rig()
        assertEquals(SpeakResult.QUEUED_UNTIL_READY, rig.session.speak("Hello."))
        assertEquals(SpeechState.INITIALIZING, rig.session.state)
        assertEquals(0, rig.focus.requests)
        rig.engine!!.events.onInitialized(true)
        assertEquals(SpeechState.SPEAKING, rig.session.state)
        assertEquals("Hello.", rig.engine!!.spoken.single().text)
    }

    @Test
    fun anEngineThatIsReadyBeforeItsConstructorReturnsStillWorks() {
        val rig = Rig(readyAtOnce = true)
        rig.session.prepare()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(SpeakResult.SPEAKING, rig.session.speak("Hello."))
    }

    @Test
    fun aStartUpFailureMakesSpeechUnavailableWithAReason() {
        val rig = Rig()
        rig.session.speak("Hello.")
        rig.engine!!.events.onInitialized(false)
        assertEquals(SpeechState.UNAVAILABLE, rig.session.state)
        assertNotNull(rig.session.problem)
        assertEquals(SpeakResult.UNAVAILABLE, rig.session.speak("Again."))
        assertTrue(rig.engine!!.spoken.isEmpty())
    }

    @Test
    fun nothingIsSpokenWhenVoiceOutputIsOffOrThereIsNothingToSay() {
        val rig = Rig(SpeechSettings(enabled = false))
        rig.ready()
        assertEquals(SpeakResult.DISABLED, rig.session.speak("Hello."))
        rig.current = SpeechSettings()
        assertEquals(SpeakResult.NOTHING_TO_SAY, rig.session.speak("   "))
        assertEquals(SpeakResult.NOTHING_TO_SAY, rig.session.speak("https://example.com/only/a/link"))
        assertTrue(rig.engine!!.spoken.isEmpty())
        assertEquals(0, rig.focus.requests)
    }

    @Test
    fun theAssistantNeverSpeaksWhileTheMicrophoneIsOpen() {
        val rig = Rig()
        rig.ready()
        rig.micActive = true
        assertEquals(SpeakResult.MICROPHONE_ACTIVE, rig.session.speak("Hello."))
        assertEquals(0, rig.focus.requests)
        assertTrue(rig.engine!!.spoken.isEmpty())
        // and a request that was waiting for the engine is dropped if the microphone opened meanwhile
        val late = Rig()
        late.session.speak("Waiting.")
        late.micActive = true
        late.engine!!.events.onInitialized(true)
        assertTrue(late.engine!!.spoken.isEmpty())
        assertEquals(SpeechState.IDLE, late.session.state)
    }

    @Test
    fun openingTheMicrophoneCutsSpeechOffFirst() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("A long answer that is still being read.")
        rig.session.stopForMicrophone()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(1, rig.engine!!.stops)
        assertEquals(1, rig.focus.abandons)
    }

    @Test
    fun theStopButtonStopsAtOnceAndLateEventsDoNothing() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("Hello there.")
        val id = rig.engine!!.spoken.last().id
        rig.session.stop()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(1, rig.engine!!.stops)
        assertEquals(1, rig.focus.abandons)
        rig.engine!!.events.onDone(id) // arrives after the stop
        rig.engine!!.events.onError(id)
        assertEquals(1, rig.focus.abandons)
        assertNull(rig.session.problem)
        rig.session.stop() // pressing it again is harmless
        assertEquals(1, rig.engine!!.stops)
    }

    @Test
    fun aNewAnswerReplacesOneStillBeingSpoken() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("First answer.")
        val first = rig.engine!!.spoken.last().id
        rig.session.speak("Second answer.")
        assertEquals(2, rig.engine!!.spoken.size)
        assertEquals(1, rig.engine!!.stops)
        rig.engine!!.events.onDone(first) // the old one finishing late must not end the new one
        assertEquals(SpeechState.SPEAKING, rig.session.state)
        rig.finishLast()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(2, rig.focus.requests)
        assertEquals(2, rig.focus.abandons)
    }

    @Test
    fun aLongAnswerIsSpokenInOrderedPiecesAndEndsAfterTheLastOne() {
        val rig = Rig(maxInput = 80)
        rig.ready()
        rig.session.speak((1..12).joinToString(" ") { "Sentence number $it is here." })
        val pieces = rig.engine!!.spoken
        assertTrue(pieces.size > 2)
        assertFalse(pieces.first().append)
        assertTrue(pieces.drop(1).all { it.append })
        assertTrue(pieces.all { it.text.length < 80 })
        pieces.dropLast(1).forEach { rig.engine!!.events.onDone(it.id) }
        assertEquals(SpeechState.SPEAKING, rig.session.state)
        rig.engine!!.events.onDone(pieces.last().id)
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(1, rig.focus.abandons)
    }

    @Test
    fun deniedAudioFocusMeansNoSpeech() {
        val rig = Rig(grant = false)
        rig.ready()
        assertEquals(SpeakResult.FOCUS_DENIED, rig.session.speak("Hello."))
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertTrue(rig.engine!!.spoken.isEmpty())
        assertEquals(0, rig.focus.abandons)
    }

    @Test
    fun losingAudioFocusStopsTheSpeech() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("Hello there.")
        rig.focus.lost!!.invoke()
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertEquals(1, rig.engine!!.stops)
        assertEquals(1, rig.focus.abandons)
    }

    @Test
    fun anEngineErrorEndsTheSpeechAndGivesFocusBack() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("Hello there.")
        rig.engine!!.events.onError(rig.engine!!.spoken.last().id)
        assertEquals(SpeechState.IDLE, rig.session.state)
        assertNotNull(rig.session.problem)
        assertEquals(1, rig.focus.abandons)
        assertEquals(SpeakResult.SPEAKING, rig.session.speak("Try again."))
    }

    @Test
    fun shuttingDownReleasesEverything() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("Hello there.")
        rig.session.shutdown()
        assertEquals(SpeechState.UNINITIALIZED, rig.session.state)
        assertEquals(1, rig.engine!!.shutdowns)
        assertEquals(1, rig.focus.abandons)
        // it can start again
        rig.ready()
        assertEquals(SpeakResult.SPEAKING, rig.session.speak("Back again."))
    }

    @Test
    fun theStateIsReportedAsItChanges() {
        val rig = Rig()
        rig.ready()
        rig.session.speak("Hello there.")
        rig.finishLast()
        assertEquals(listOf(SpeechState.INITIALIZING, SpeechState.IDLE, SpeechState.SPEAKING, SpeechState.IDLE), rig.states)
    }
}
