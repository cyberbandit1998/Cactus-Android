package app.cactusai.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.AppContainer
import app.cactusai.assistant.settings.label
import app.cactusai.assistant.ui.components.SectionCard
import app.cactusai.core.exec.ExecutionConfig
import app.cactusai.core.exec.ExecutionMode
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.settings.AssistantPreferences
import app.cactusai.core.speech.SpeechState
import app.cactusai.core.voice.EndpointConfig
import app.cactusai.core.voice.SpeechSensitivity
import app.cactusai.core.web.ReadMode
import app.cactusai.core.web.TempUnit
import java.util.Locale
import kotlin.math.roundToInt

private fun seconds(ms: Int): String = String.format(Locale.US, "%.1f", ms / 1000.0)

/** The settings for listening, running commands, spoken answers and online lookups, in the order a command goes through them. */
@Composable
internal fun AssistantSettingsSections(container: AppContainer, history: List<HistoryEntry>) {
    val settings by container.settings.state.collectAsState()
    val prefs = settings.assistant
    val change: ((AssistantPreferences) -> AssistantPreferences) -> Unit = { transform -> container.settings.updateAssistant(transform) }

    ListeningCard(prefs, change)
    RunningCard(prefs, change)
    SpeakingCard(container, prefs, change)
    OnlineCard(prefs, change)
    RecentCommandsCard(history)
}

@Composable
private fun ListeningCard(prefs: AssistantPreferences, change: ((AssistantPreferences) -> AssistantPreferences) -> Unit) {
    SectionCard(title = "Listening") {
        SwitchRow(
            "Stop listening by itself",
            "Tap the microphone once, speak, and I notice when you have finished. You can still tap again to stop, or cancel, at any time.",
            prefs.autoStop,
        ) { on -> change { it.copy(autoStop = on) } }

        if (prefs.autoStop) {
            Text("Wait ${seconds(prefs.silenceMs)} s of quiet after you speak", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.silenceMs / 1000f,
                onValueChange = { v -> change { it.copy(silenceMs = (v * 10).roundToInt() * 100) } },
                valueRange = EndpointConfig.MIN_SILENCE_MS / 1000f..EndpointConfig.MAX_SILENCE_MS / 1000f,
                steps = 34,
            )
            Text(
                "The countdown starts only after you have started speaking, so a pause in the middle of a command does not cut it off unless it is longer than this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Longest recording: ${prefs.maxRecordMs / 1000} s", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.maxRecordMs / 1000f,
            onValueChange = { v -> change { it.copy(maxRecordMs = v.roundToInt() * 1000) } },
            valueRange = EndpointConfig.MIN_RECORDING_MS / 1000f..EndpointConfig.MAX_RECORDING_MS / 1000f,
            steps = 26,
        )

        Text("Microphone sensitivity", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = SpeechSensitivity.entries.map { it to it.label() },
            selected = prefs.sensitivity,
            onSelect = { s -> change { it.copy(sensitivity = s) } },
        )
        Text(
            "Detecting the end of your speech happens on this phone with a simple loudness check, with no extra model, and no audio leaves the phone. " +
                "It can be fooled by a very noisy room or a very quiet voice: if it stops too early or never stops, change the wait time or the sensitivity, or tap the microphone to stop it yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunningCard(prefs: AssistantPreferences, change: ((AssistantPreferences) -> AssistantPreferences) -> Unit) {
    SectionCard(title = "Running commands") {
        SwitchRow(
            "Automatically execute safe actions",
            "Low-risk actions such as opening an app, a web search, a timer or scrolling run by themselves when the model is confident, after a short moment in which you can cancel. Off: every command is shown to you first.",
            prefs.autoExecute,
        ) { on -> change { it.copy(autoExecute = on) } }

        Text("Confidence needed to run without asking: ${prefs.thresholdPercent}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.thresholdPercent.toFloat(),
            onValueChange = { v -> change { it.copy(thresholdPercent = v.roundToInt()) } },
            valueRange = ExecutionConfig.MIN_THRESHOLD_PERCENT.toFloat()..ExecutionConfig.MAX_THRESHOLD_PERCENT.toFloat(),
            steps = ExecutionConfig.MAX_THRESHOLD_PERCENT - ExecutionConfig.MIN_THRESHOLD_PERCENT - 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantPreferences.THRESHOLD_PRESETS.forEach { p ->
                if (p == prefs.thresholdPercent) {
                    Button(onClick = { change { it.copy(thresholdPercent = p) } }) { Text("$p%") }
                } else {
                    OutlinedButton(onClick = { change { it.copy(thresholdPercent = p) } }) { Text("$p%") }
                }
            }
        }
        Text(
            "This is the model's own confidence for the command. Below it, the command is shown to you first. If the model gives no confidence number, I always ask. Higher is safer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow("Show the model's confidence", "Shows the percentage next to each command.", prefs.showConfidence) { on -> change { it.copy(showConfidence = on) } }

        Text("Cancel window: ${seconds(prefs.cancelWindowMs)} s", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.cancelWindowMs / 1000f,
            onValueChange = { v -> change { it.copy(cancelWindowMs = (v * 10).roundToInt() * 100) } },
            valueRange = 0f..3f,
            steps = 29,
        )

        Text("Confirm sensitive actions: always on", style = MaterialTheme.typography.titleSmall)
        Text(
            "Sending a text, placing a call, deleting, buying or paying, security settings, granting permissions, installing or uninstalling, and tapping a button that could do any of those always wait for your OK. No setting changes that, however sure the model is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeakingCard(container: AppContainer, prefs: AssistantPreferences, change: ((AssistantPreferences) -> AssistantPreferences) -> Unit) {
    val speech = container.speech
    val state by speech.state.collectAsState()
    val voices by speech.voices.collectAsState()
    var picking by remember { mutableStateOf(false) }

    SectionCard(title = "Spoken answers") {
        SwitchRow("Allow the assistant to speak", "Uses your phone's own text-to-speech engine. Nothing is sent anywhere to be spoken.", prefs.voiceOutput) { on ->
            change { it.copy(voiceOutput = on) }
            if (!on) speech.stop()
        }
        SwitchRow("Speak answers automatically", "Answers to questions are read aloud when they are ready. Tap Stop speaking to cut one off.", prefs.speakAnswers) { on ->
            change { it.copy(speakAnswers = on) }
        }

        Text("When I search the web", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = ReadMode.entries.map { it to it.label() },
            selected = prefs.readMode,
            onSelect = { m -> change { it.copy(readMode = m) } },
        )
        Text(
            when (prefs.readMode) {
                ReadMode.SHORT_ANSWER -> "Short answer: I say one sentence only when the search results agree on it; otherwise I read the first two or three results."
                ReadMode.READ_RESULTS -> "Read results: I read the titles and descriptions of the first two or three results."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Speed: ${String.format(Locale.US, "%.1f", prefs.speechRate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.speechRate,
            onValueChange = { v -> change { it.copy(speechRate = (v * 10).roundToInt() / 10f) } },
            valueRange = 0.5f..2.0f,
            steps = 14,
        )
        Text("Pitch: ${String.format(Locale.US, "%.1f", prefs.speechPitch)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.speechPitch,
            onValueChange = { v -> change { it.copy(speechPitch = (v * 10).roundToInt() / 10f) } },
            valueRange = 0.5f..2.0f,
            steps = 14,
        )

        val chosen = voices.firstOrNull { it.name == prefs.voiceName }
        Text("Voice: ${chosen?.label ?: "the phone's default"}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { speech.prepare(); picking = true }) { Text("Choose voice") }
            OutlinedButton(onClick = { speech.speak("This is how I will read answers to you.") }) { Text("Test voice") }
            if (state == SpeechState.SPEAKING) OutlinedButton(onClick = speech::stop) { Text("Stop speaking") }
        }
        speech.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            "To install more voices or change the speech engine, use Android settings > System > Languages > Text-to-speech output.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Choose a voice") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { change { it.copy(voiceName = null) }; picking = false }) { Text("The phone's default") }
                    if (voices.isEmpty()) {
                        Text("No voices reported yet. If this stays empty, the phone's speech engine may still be starting.", style = MaterialTheme.typography.bodySmall)
                    }
                    voices.forEach { v ->
                        TextButton(onClick = { change { it.copy(voiceName = v.name) }; picking = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(v.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun OnlineCard(prefs: AssistantPreferences, change: ((AssistantPreferences) -> AssistantPreferences) -> Unit) {
    var home by remember { mutableStateOf(prefs.homePlace.orEmpty()) }
    var teams by remember { mutableStateOf(prefs.favoriteTeams.joinToString(", ")) }

    SectionCard(title = "Questions and the web") {
        SwitchRow(
            "Look things up online",
            "Sports scores, weather and general questions open a Google search in your browser and read the result. Only the words of your question go to Google, sent by your browser. The time, date, battery, storage and calculations never go online.",
            prefs.onlineLookups,
        ) { on -> change { it.copy(onlineLookups = on) } }
        SwitchRow(
            "Read search results aloud",
            "After \"search for …\", read the results page back to you. This needs the optional Accessibility service; without it the results simply stay open in your browser.",
            prefs.readSearchResults,
        ) { on -> change { it.copy(readSearchResults = on) } }

        Text("Temperature", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = TempUnit.entries.map { it to it.label() },
            selected = prefs.tempUnit,
            onSelect = { u -> change { it.copy(tempUnit = u) } },
        )

        OutlinedTextField(
            value = home,
            onValueChange = { v -> home = v; change { it.copy(homePlace = v) } },
            label = { Text("Home city (for \"what's the weather\")") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = teams,
            onValueChange = { v -> teams = v; change { it.copy(favoriteTeams = AssistantPreferences.parseTeams(v)) } },
            label = { Text("Favourite teams, separated by commas") },
            supportingText = { Text("So \"when do the Tigers play?\" knows which Tigers you mean.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Text read from a web page is only shown and spoken. It is never treated as a command, and never changes what the phone does.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentCommandsCard(history: List<HistoryEntry>) {
    SectionCard(title = "Recent commands") {
        val recent = history.take(5)
        if (recent.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
        }
        recent.forEach { e ->
            Column {
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
                Text(historyDetail(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Ran automatically · 91% · online", or whatever of that applies. */
internal fun historyDetail(e: HistoryEntry): String = listOfNotNull(
    when (e.mode) {
        ExecutionMode.AUTOMATIC -> "Ran automatically"
        ExecutionMode.CONFIRMED -> "Ran after you confirmed"
        null -> e.outcome.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    },
    e.confidence?.let { "${(it * 100).roundToInt()}% confident" },
    if (e.online) "online" else null,
).joinToString(" · ")

/** A row of mutually exclusive options; the chosen one is filled. */
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}
