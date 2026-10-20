package app.cactusai.assistant.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Master switch: when off, the microphone is never opened by this app. */
    val voiceActivationEnabled: Boolean = true,
    /** Ask before opening a prefilled dialer or message. */
    val confirmCallsAndTexts: Boolean = true,
    /** Act without asking only at or above this model confidence. */
    val actThreshold: Float = 0.7f,
    val trimSilence: Boolean = true,
    /** Gives Whistle the names of installed apps so it spells them right (upstream "keywords" option). */
    val biasSpeechToAppNames: Boolean = true,
    val expandRawJson: Boolean = false,
    /** Folders the user chose for file search (Storage Access Framework tree URIs). */
    val folderUris: Set<String> = emptySet(),
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
            val next = transform(_state.value)
            write(next)
            _state.value = next
        }
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            voiceActivationEnabled = prefs.getBoolean("voice", d.voiceActivationEnabled),
            confirmCallsAndTexts = prefs.getBoolean("confirm_calls_texts", d.confirmCallsAndTexts),
            actThreshold = prefs.getFloat("threshold", d.actThreshold).coerceIn(0.5f, 0.95f),
            trimSilence = prefs.getBoolean("trim_silence", d.trimSilence),
            biasSpeechToAppNames = prefs.getBoolean("bias_app_names", d.biasSpeechToAppNames),
            expandRawJson = prefs.getBoolean("expand_json", d.expandRawJson),
            folderUris = prefs.getStringSet("folders", emptySet()).orEmpty().toSet(),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putBoolean("voice", s.voiceActivationEnabled)
            .putBoolean("confirm_calls_texts", s.confirmCallsAndTexts)
            .putFloat("threshold", s.actThreshold)
            .putBoolean("trim_silence", s.trimSilence)
            .putBoolean("bias_app_names", s.biasSpeechToAppNames)
            .putBoolean("expand_json", s.expandRawJson)
            .putStringSet("folders", s.folderUris)
            .apply()
    }
}
