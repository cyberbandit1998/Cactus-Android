package app.cactusai.core.speech

enum class SpeechState { UNINITIALIZED, INITIALIZING, IDLE, SPEAKING, UNAVAILABLE }

/** What the Android TextToSpeech adapter reports back. All calls may arrive on any thread. */
interface TtsEvents {
    fun onInitialized(success: Boolean)
    fun onStarted(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)
}

/** The part of Android's TextToSpeech the session needs. */
interface TtsEngine {
    /** The longest text one `speak` call accepts. */
    val maxInputLength: Int
    fun speak(utteranceId: String, text: String, settings: SpeechSettings, append: Boolean)
    fun stop()
    fun shutdown()
}

/** Audio focus: other audio is quietened or paused while the assistant speaks, and the assistant stops if it loses focus. */
interface AudioFocus {
    /** True when granted. [onLost] is called if focus is taken away while speaking. */
    fun request(onLost: () -> Unit): Boolean
    fun abandon()
}

enum class SpeakResult { SPEAKING, QUEUED_UNTIL_READY, NOTHING_TO_SAY, DISABLED, MICROPHONE_ACTIVE, FOCUS_DENIED, UNAVAILABLE }

/**
 * One spoken answer at a time. The Android details (the TextToSpeech object, the audio manager) sit behind [TtsEngine] and
 * [AudioFocus], so the lifecycle can be tested without a phone:
 *  - the engine is created once and its start-up is awaited, with a request made earlier kept until it is ready;
 *  - nothing is spoken while the microphone is open, and the microphone path calls [stopForMicrophone] first, so the
 *    assistant's own voice can never be recorded as a command;
 *  - audio focus is requested before speaking and given back exactly once, when speech ends, is stopped, fails or loses focus;
 *  - a new answer replaces the old one; [stop] is the Stop Speaking button.
 */
class SpeechSession(
    private val engineFactory: (TtsEvents) -> TtsEngine,
    private val focus: AudioFocus,
    private val settings: () -> SpeechSettings,
    private val microphoneActive: () -> Boolean,
    private val onState: (SpeechState) -> Unit = {},
) : TtsEvents {
    private val lock = Any()
    private var engine: TtsEngine? = null
    private var queuedText: String? = null
    private var focusHeld = false
    private var current: String? = null
    private var pendingChunks = 0
    private var counter = 0
    private var earlyInit: Boolean? = null

    var state: SpeechState = SpeechState.UNINITIALIZED
        private set(value) {
            field = value
            onState(value)
        }

    /** Why speech is unavailable (no engine on the phone, start-up failed). */
    var problem: String? = null
        private set

    /** Starts the engine if that has not happened yet. Safe to call often. */
    fun prepare() {
        synchronized(lock) {
            if (engine != null || state == SpeechState.UNAVAILABLE) return
            state = SpeechState.INITIALIZING
            engine = engineFactory(this)
            // An engine may report that it is ready before its constructor returns.
            earlyInit?.let {
                earlyInit = null
                handleInit(it)
            }
        }
    }

    fun speak(text: String): SpeakResult {
        val s = settings()
        val prepared = SpeechText.clean(text)
        synchronized(lock) {
            if (!s.enabled) return SpeakResult.DISABLED
            if (prepared.isBlank()) return SpeakResult.NOTHING_TO_SAY
            if (microphoneActive()) return SpeakResult.MICROPHONE_ACTIVE
            if (state == SpeechState.UNAVAILABLE) return SpeakResult.UNAVAILABLE
            if (engine == null) prepare()
            if (state == SpeechState.INITIALIZING || state == SpeechState.UNINITIALIZED) {
                queuedText = prepared
                return SpeakResult.QUEUED_UNTIL_READY
            }
            return startSpeaking(prepared, s)
        }
    }

    private fun startSpeaking(prepared: String, s: SpeechSettings): SpeakResult {
        val e = engine ?: return SpeakResult.UNAVAILABLE
        if (state == SpeechState.SPEAKING) {
            e.stop()
            releaseFocus()
        }
        if (!focus.request { onFocusLost() }) {
            state = SpeechState.IDLE
            return SpeakResult.FOCUS_DENIED
        }
        focusHeld = true
        val chunks = SpeechText.chunks(prepared, minOf(e.maxInputLength - 1, 3900).coerceAtLeast(40))
        counter++
        current = "u$counter"
        pendingChunks = chunks.size
        state = SpeechState.SPEAKING
        chunks.forEachIndexed { i, chunk -> e.speak("u$counter-$i", chunk, s, append = i > 0) }
        return SpeakResult.SPEAKING
    }

    /** The Stop Speaking button. Also called before the microphone opens and when the app leaves the screen. */
    fun stop() {
        synchronized(lock) {
            queuedText = null
            if (state == SpeechState.SPEAKING) {
                engine?.stop()
                finish()
            }
        }
    }

    /** Called just before the microphone opens: whatever is being said is cut off and nothing new starts. */
    fun stopForMicrophone() = stop()

    fun shutdown() {
        synchronized(lock) {
            queuedText = null
            engine?.shutdown()
            engine = null
            releaseFocus()
            current = null
            state = SpeechState.UNINITIALIZED
        }
    }

    // ---- events from the engine ----

    override fun onInitialized(success: Boolean) {
        synchronized(lock) {
            if (engine == null) {
                if (state == SpeechState.INITIALIZING) earlyInit = success
                return
            }
            handleInit(success)
        }
    }

    private fun handleInit(success: Boolean) {
        if (!success) {
            problem = "The phone's text-to-speech engine could not start. Check Settings > System > Languages > Text-to-speech output."
            state = SpeechState.UNAVAILABLE
            queuedText = null
            return
        }
        state = SpeechState.IDLE
        val waiting = queuedText
        queuedText = null
        if (waiting != null && !microphoneActive() && settings().enabled) startSpeaking(waiting, settings())
    }

    override fun onStarted(utteranceId: String) = Unit

    override fun onDone(utteranceId: String) {
        synchronized(lock) {
            if (!belongsToCurrent(utteranceId) || state != SpeechState.SPEAKING) return
            pendingChunks--
            if (pendingChunks <= 0) finish()
        }
    }

    override fun onError(utteranceId: String) {
        synchronized(lock) {
            if (!belongsToCurrent(utteranceId) || state != SpeechState.SPEAKING) return
            engine?.stop()
            problem = "The speaker stopped with an error."
            finish()
        }
    }

    private fun onFocusLost() {
        synchronized(lock) {
            if (state == SpeechState.SPEAKING) {
                engine?.stop()
                finish()
            }
        }
    }

    private fun belongsToCurrent(id: String): Boolean = current != null && id.startsWith("$current-")

    private fun finish() {
        releaseFocus()
        current = null
        pendingChunks = 0
        if (state != SpeechState.UNAVAILABLE) state = SpeechState.IDLE
    }

    private fun releaseFocus() {
        if (focusHeld) {
            focusHeld = false
            focus.abandon()
        }
    }
}
