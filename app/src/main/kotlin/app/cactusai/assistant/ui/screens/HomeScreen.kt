package app.cactusai.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.assistant.AssistantUiState
import app.cactusai.assistant.assistant.Phase
import app.cactusai.assistant.ui.components.AssistantPanel
import app.cactusai.assistant.ui.components.MicButton
import app.cactusai.assistant.ui.components.PanelActions
import app.cactusai.assistant.ui.components.SectionCard

@Composable
fun HomeScreen(
    state: AssistantUiState,
    voiceEnabled: Boolean,
    modelsReady: Boolean,
    engineLinked: Boolean,
    engineProblem: String?,
    thermalWarning: String?,
    showRawJson: Boolean,
    autoStop: Boolean,
    showConfidence: Boolean,
    speaking: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancel: () -> Unit,
    onOpenModels: () -> Unit,
    panelActions: PanelActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Cactus AI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Private voice assistant. Speech and commands are understood on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (!engineLinked) {
            SectionCard(title = "AI engine missing") {
                Text(
                    "This build of the app does not contain the Needle engine, so it cannot run the models. Build it again after running the engine fetch script (see the README)." +
                        (engineProblem?.let { " Details: $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (engineLinked && !modelsReady) {
            SectionCard(title = "Models needed") {
                Text(
                    "Download the Whistle (speech) and Needle (commands) models once. After that everything works without an internet connection.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenModels) { Text("Open Models") }
            }
        }
        thermalWarning?.let {
            SectionCard(title = "Phone is warm") { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (!voiceEnabled) {
            SectionCard(title = "Voice is off") {
                Text("The microphone is disabled in Settings. You can still type commands on the Command tab.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        val listening = state.phase == Phase.LISTENING
        MicButton(
            listening = listening,
            enabled = voiceEnabled && !state.busy || listening,
            level = state.micLevel,
            onStart = onStartListening,
            onStop = onStopListening,
        )

        // The microphone's state, always in words: Idle, Listening, Speech detected or Processing.
        Text(
            "Microphone: ${state.micState.label}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (listening) {
            MicActivity(
                level = state.micLevel, elapsedMs = state.elapsedMs, speechDetected = state.speechDetected,
                autoStopSeconds = state.autoStopSeconds, maxSeconds = state.maxRecordSeconds,
            )
        } else {
            Text(
                text = when {
                    state.busy -> state.status.ifBlank { "Working…" }
                    !voiceEnabled -> "Voice is turned off"
                    autoStop -> "Tap once and speak. I stop when you stop talking. Or hold to talk."
                    else -> "Hold to talk, or tap once to start and again to send"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (state.busy) {
            OutlinedButton(onClick = onCancel) { Text(if (listening) "Cancel and discard" else "Cancel") }
        }

        AssistantPanel(state = state, actions = panelActions, showRawJson = showRawJson, speaking = speaking, showConfidence = showConfidence)
        Spacer(Modifier.height(24.dp))
    }
}

/** Always-visible sign that the microphone is on, with a live loudness bar, the elapsed time and what ends the recording. */
@Composable
fun MicActivity(
    level: Float,
    elapsedMs: Long,
    speechDetected: Boolean = false,
    autoStopSeconds: Float? = null,
    maxSeconds: Int = 30,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.width(8.dp))
            val seconds = elapsedMs / 1000
            Text(
                "${if (speechDetected) "Speech detected" else "Listening"} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(0.6f))
        val hint = when {
            autoStopSeconds == null -> "Tap the microphone again when you are done."
            speechDetected -> "I stop after ${String.format(java.util.Locale.US, "%.1f", autoStopSeconds)} s of quiet. Tap the microphone to stop now."
            else -> "Start speaking. I stop ${String.format(java.util.Locale.US, "%.1f", autoStopSeconds)} s after you finish."
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text("Up to $maxSeconds seconds. Nothing is saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
