package app.cactusai.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.settings.speech
import app.cactusai.core.speech.AudioFocus
import app.cactusai.core.speech.SpeakResult
import app.cactusai.core.speech.SpeechSession
import app.cactusai.core.speech.SpeechSettings
import app.cactusai.core.speech.SpeechState
import app.cactusai.core.speech.TtsEngine
import app.cactusai.core.speech.TtsEvents
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A voice the phone's speech engine offers, for the voice picker in settings. */
data class VoiceOption(val name: String, val label: String, val offline: Boolean)

private val assistantAudio: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * The phone's own TextToSpeech engine (whatever the user has chosen under Android's text-to-speech output), with the assistant
 * audio usage so the volume follows the assistant stream. Everything it says was written by this app; it is never fed web
 * text it has not been through [app.cactusai.core.speech.SpeechText] with.
 */
class AndroidTtsEngine(
    context: Context,
    private val events: TtsEvents,
    private val onVoices: (List<VoiceOption>) -> Unit,
) : TtsEngine {
    private var tts: TextToSpeech? = null
    private var appliedVoice: String? = null

    override val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                events.onInitialized(false)
            } else {
                prepare(engine)
                events.onInitialized(true)
            }
        }
    }

    private fun prepare(engine: TextToSpeech) {
        engine.setAudioAttributes(assistantAudio)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = events.onStarted(utteranceId)
                override fun onDone(utteranceId: String) = events.onDone(utteranceId)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String) = events.onError(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = events.onError(utteranceId)

                // Our own stop() or a replacement answer: the session has already moved on.
                override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
            },
        )
        val wanted = Locale.getDefault()
        val result = engine.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.US)
        onVoices(voiceOptions(engine))
    }

    override fun speak(utteranceId: String, text: String, settings: SpeechSettings, append: Boolean) {
        val engine = tts ?: return events.onError(utteranceId)
        engine.setSpeechRate(settings.safeRate)
        engine.setPitch(settings.safePitch)
        applyVoice(engine, settings.voiceName)
        val queue = if (append) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val params = Bundle()
        if (engine.speak(text, queue, params, utteranceId) != TextToSpeech.SUCCESS) events.onError(utteranceId)
    }

    private fun applyVoice(engine: TextToSpeech, name: String?) {
        if (name == appliedVoice) return
        appliedVoice = name
        if (name == null) {
            engine.defaultVoice?.let { engine.voice = it }
            return
        }
        // A voice that was uninstalled since it was chosen simply falls back to the system default.
        val match = engine.voices.orEmpty().firstOrNull { it.name == name }
        if (match != null) engine.voice = match else engine.defaultVoice?.let { engine.voice = it }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun voiceOptions(engine: TextToSpeech): List<VoiceOption> {
        val language = Locale.getDefault().language
        val all = engine.voices.orEmpty()
        val mine = all.filter { it.locale.language == language }.ifEmpty { all.toList() }
        // Offline voices first, then better quality; numbered per language so two voices never share a label.
        val ordered = mine.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
        val counters = HashMap<String, Int>()
        return ordered.map { v ->
            val n = counters.merge(v.locale.toLanguageTag(), 1, Int::plus) ?: 1
            val where = v.locale.getDisplayName(Locale.getDefault())
            val label = "$where, voice $n" + if (v.isNetworkConnectionRequired) " (needs internet)" else ""
            VoiceOption(v.name, label, offline = !v.isNetworkConnectionRequired)
        }
    }
}

/** Takes audio focus (other audio ducks while the assistant speaks) and gives it back when speech ends or is cut off. */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onLost: () -> Unit): Boolean {
        abandon()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(assistantAudio)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }
            .build()
        request = r
        val granted = audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) request = null
        return granted
    }

    override fun abandon() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}

/**
 * What the screens and the controller use to speak. Wraps the tested [SpeechSession] with the Android pieces and exposes its
 * state so the Stop Speaking button knows when to show.
 *
 * [microphoneActive] is the recorder's flag: nothing is spoken while the microphone is open, and [stopForMicrophone] cuts off
 * anything being said just before it opens, so the assistant's voice can never be picked up as the next command.
 */
class SpeechOutput(
    context: Context,
    private val settings: AppSettings,
    microphoneActive: () -> Boolean,
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(SpeechState.UNINITIALIZED)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val session = SpeechSession(
        engineFactory = { events -> AndroidTtsEngine(app, events) { _voices.value = it } },
        focus = AndroidAudioFocus(app),
        settings = { settings.current.speech },
        microphoneActive = microphoneActive,
        onState = { _state.value = it },
    )

    val isSpeaking: Boolean get() = _state.value == SpeechState.SPEAKING

    /** Why speaking is not available (no speech engine on the phone), or null. */
    val problem: String? get() = session.problem

    /** Starts the speech engine ahead of time so the first answer is not delayed. Safe to call often. */
    fun prepare() = session.prepare()

    /** Speaks [text] now, whatever the "speak answers automatically" switch says (used by the Test voice button). */
    fun speak(text: String): SpeakResult = session.speak(text)

    /** Speaks an answer only when the user wants answers spoken by themselves. */
    fun speakAnswer(text: String): SpeakResult? = if (settings.current.speech.autoSpeak) session.speak(text) else null

    /** The Stop Speaking button. */
    fun stop() = session.stop()

    fun stopForMicrophone() = session.stopForMicrophone()

    fun shutdown() = session.shutdown()
}
