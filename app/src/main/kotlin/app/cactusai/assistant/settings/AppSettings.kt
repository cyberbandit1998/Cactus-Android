package app.cactusai.assistant.settings

import android.content.Context
import app.cactusai.core.settings.AssistantPreferences
import app.cactusai.core.speech.SpeechSettings
import app.cactusai.core.voice.SpeechSensitivity
import app.cactusai.core.web.ReadMode
import app.cactusai.core.web.TempUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Master switch: when off, the microphone is never opened by this app. */
    val voiceActivationEnabled: Boolean = true,
    val trimSilence: Boolean = true,
    /** Gives Whistle the names of installed apps so it spells them right (upstream "keywords" option). */
    val biasSpeechToAppNames: Boolean = true,
    val expandRawJson: Boolean = false,
    /** Folders the user chose for file search (Storage Access Framework tree URIs). */
    val folderUris: Set<String> = emptySet(),
    /** Listening, automatic execution, spoken answers and online lookups. */
    val assistant: AssistantPreferences = AssistantPreferences(),
) {
    /** English only for now; Whistle also supports de, fr, es, it, nl and pl. */
    val language: String get() = "en"
}

/** Small SharedPreferences wrapper. Settings never leave the phone and are excluded from backup. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        synchronized(this) {
            val next = transform(_state.value).let { it.copy(assistant = it.assistant.normalized()) }
            write(next)
            _state.value = next
        }
    }

    /** Changes only the assistant preferences. */
    fun updateAssistant(transform: (AssistantPreferences) -> AssistantPreferences) = update { it.copy(assistant = transform(it.assistant)) }

    private fun read(): Settings {
        val d = Settings()
        val a = AssistantPreferences()
        // Earlier versions stored the threshold as a fraction under "threshold"; keep honouring it until the new key is written.
        val legacyPercent = if (prefs.contains("threshold")) (prefs.getFloat("threshold", 0.7f) * 100).roundToInt() else a.thresholdPercent
        val assistant = AssistantPreferences(
            autoStop = prefs.getBoolean("auto_stop", a.autoStop),
            silenceMs = prefs.getInt("silence_ms", a.silenceMs),
            maxRecordMs = prefs.getInt("max_record_ms", a.maxRecordMs),
            sensitivity = enumOr(prefs.getString("sensitivity", null), a.sensitivity),
            autoExecute = prefs.getBoolean("auto_execute", a.autoExecute),
            thresholdPercent = prefs.getInt("threshold_pct", legacyPercent),
            showConfidence = prefs.getBoolean("show_confidence", a.showConfidence),
            cancelWindowMs = prefs.getInt("cancel_window_ms", a.cancelWindowMs),
            speakAnswers = prefs.getBoolean("speak_answers", a.speakAnswers),
            voiceOutput = prefs.getBoolean("voice_output", a.voiceOutput),
            speechRate = prefs.getFloat("speech_rate", a.speechRate),
            speechPitch = prefs.getFloat("speech_pitch", a.speechPitch),
            voiceName = prefs.getString("voice_name", null),
            onlineLookups = prefs.getBoolean("online_lookups", a.onlineLookups),
            readSearchResults = prefs.getBoolean("read_search_results", a.readSearchResults),
            readMode = ReadMode.fromKey(prefs.getString("read_mode", null)),
            tempUnit = enumOr(prefs.getString("temp_unit", null), a.tempUnit),
            favoriteTeams = AssistantPreferences.parseTeams(prefs.getString("favorite_teams", "").orEmpty()),
            homePlace = prefs.getString("home_place", null),
            use24Hour = prefs.getBoolean("use_24_hour", a.use24Hour),
        ).normalized()
        return Settings(
            voiceActivationEnabled = prefs.getBoolean("voice", d.voiceActivationEnabled),
            trimSilence = prefs.getBoolean("trim_silence", d.trimSilence),
            biasSpeechToAppNames = prefs.getBoolean("bias_app_names", d.biasSpeechToAppNames),
            expandRawJson = prefs.getBoolean("expand_json", d.expandRawJson),
            folderUris = prefs.getStringSet("folders", emptySet()).orEmpty().toSet(),
            assistant = assistant,
        )
    }

    private fun write(s: Settings) {
        val a = s.assistant
        prefs.edit()
            .putBoolean("voice", s.voiceActivationEnabled)
            .putBoolean("trim_silence", s.trimSilence)
            .putBoolean("bias_app_names", s.biasSpeechToAppNames)
            .putBoolean("expand_json", s.expandRawJson)
            .putStringSet("folders", s.folderUris)
            .putBoolean("auto_stop", a.autoStop)
            .putInt("silence_ms", a.silenceMs)
            .putInt("max_record_ms", a.maxRecordMs)
            .putString("sensitivity", a.sensitivity.name)
            .putBoolean("auto_execute", a.autoExecute)
            .putInt("threshold_pct", a.thresholdPercent)
            .putBoolean("show_confidence", a.showConfidence)
            .putInt("cancel_window_ms", a.cancelWindowMs)
            .putBoolean("speak_answers", a.speakAnswers)
            .putBoolean("voice_output", a.voiceOutput)
            .putFloat("speech_rate", a.speechRate)
            .putFloat("speech_pitch", a.speechPitch)
            .putString("voice_name", a.voiceName)
            .putBoolean("online_lookups", a.onlineLookups)
            .putBoolean("read_search_results", a.readSearchResults)
            .putString("read_mode", a.readMode.name)
            .putString("temp_unit", a.tempUnit.name)
            .putString("favorite_teams", a.favoriteTeams.joinToString("\n"))
            .putString("home_place", a.homePlace)
            .putBoolean("use_24_hour", a.use24Hour)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}

/** Handy for the speech adapter, which only needs the speaking part. */
val Settings.speech: SpeechSettings get() = assistant.speechSettings()

/** Names shown in the settings screen. */
fun SpeechSensitivity.label(): String = when (this) {
    SpeechSensitivity.LOW -> "Low (noisy rooms)"
    SpeechSensitivity.NORMAL -> "Normal"
    SpeechSensitivity.HIGH -> "High (quiet voice)"
}

fun TempUnit.label(): String = when (this) {
    TempUnit.AUTO -> "Follow my region"
    TempUnit.FAHRENHEIT -> "Fahrenheit"
    TempUnit.CELSIUS -> "Celsius"
}

fun ReadMode.label(): String = when (this) {
    ReadMode.SHORT_ANSWER -> "Short answer"
    ReadMode.READ_RESULTS -> "Read results"
}
