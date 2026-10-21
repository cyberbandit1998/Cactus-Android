package app.cactusai.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cactusai.assistant.assistant.AssistantController
import app.cactusai.assistant.assistant.Phase
import app.cactusai.assistant.assistant.PermissionNeed
import app.cactusai.assistant.models.ModelManager
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.speech.SpeechOutput
import app.cactusai.assistant.ui.components.AssistantPanel
import app.cactusai.assistant.ui.components.MicButton
import app.cactusai.assistant.ui.components.PanelActions
import app.cactusai.assistant.ui.screens.MicActivity
import app.cactusai.core.history.Outcome
import app.cactusai.core.models.ModelStatus
import app.cactusai.core.speech.SpeechState
import kotlinx.coroutines.delay

/**
 * The compact assistant that appears over the current app. Same controller as the main screen.
 * Permission dialogs cannot be shown from here, so anything that needs one points to the main app.
 */
@Composable
fun AssistantOverlay(
    controller: AssistantController,
    models: ModelManager,
    speech: SpeechOutput,
    settings: AppSettings,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val modelStates by models.models.collectAsStateWithLifecycle()
    val missingModels = modelStates.values.any { it.status !is ModelStatus.Installed }
    val speechState by speech.state.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val speaking = speechState == SpeechState.SPEAKING

    // After a plain success (nothing to read or tap, no answer to read) hand the screen back to the app underneath.
    LaunchedEffect(state.phase, state.outcome, state.results.size, state.answers.size) {
        if (state.phase == Phase.DONE && state.outcome == Outcome.SUCCESS && state.results.isEmpty() && state.answers.isEmpty()) {
            delay(2_000)
            onClose()
        }
    }

    val actions = remember(controller) {
        PanelActions(
            onConfirm = controller::confirm,
            onDecline = controller::decline,
            onChoose = controller::choose,
            onDismissChoice = controller::dismissChoice,
            onResultClick = { item ->
                controller.onResultClicked(item)
                onClose()
            },
            onGrantPermission = { _: PermissionNeed -> onOpenApp() },
            onOpenAccessibility = onOpenApp,
            onStopSpeaking = controller::stopSpeaking,
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).clickable(enabled = false) {},
        ) {
            Column(
                Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cactus AI", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                val listening = state.phase == Phase.LISTENING
                if (missingModels) {
                    Text("Download the models in the Cactus AI app first (Models tab).", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenApp) { Text("Open Cactus AI") }
                } else {
                    MicButton(
                        listening = listening,
                        enabled = !state.busy || listening,
                        level = state.micLevel,
                        onStart = controller::startListening,
                        onStop = controller::stopListening,
                        size = 96.dp,
                    )
                    Text("Microphone: ${state.micState.label}", style = MaterialTheme.typography.titleSmall)
                    if (listening) {
                        MicActivity(
                            level = state.micLevel, elapsedMs = state.elapsedMs, speechDetected = state.speechDetected,
                            autoStopSeconds = state.autoStopSeconds, maxSeconds = state.maxRecordSeconds,
                        )
                    } else if (state.busy) {
                        OutlinedButton(onClick = controller::cancel) { Text("Cancel") }
                    }
                }
                AssistantPanel(state = state, actions = actions, showRawJson = false, speaking = speaking, showConfidence = prefs.assistant.showConfidence)
                TextButton(onClick = onOpenApp) { Text("Open the app") }
            }
        }
    }
}
