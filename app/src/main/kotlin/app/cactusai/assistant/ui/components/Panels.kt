package app.cactusai.assistant.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.assistant.AssistantUiState
import app.cactusai.assistant.assistant.PermissionNeed
import app.cactusai.core.exec.ExecutionMode
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultItem
import java.util.Date
import kotlin.math.roundToInt

class PanelActions(
    val onConfirm: () -> Unit,
    val onDecline: () -> Unit,
    val onChoose: (Choice) -> Unit,
    val onDismissChoice: () -> Unit,
    val onResultClick: (ResultItem) -> Unit,
    val onGrantPermission: (PermissionNeed) -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onStopSpeaking: () -> Unit = {},
)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
        Icon(CactusIcons.Copy, contentDescription = "Copy text")
    }
}

/** Everything about the current command: what was heard, what was decided, what happened. */
@Composable
fun AssistantPanel(
    state: AssistantUiState,
    actions: PanelActions,
    showRawJson: Boolean,
    modifier: Modifier = Modifier,
    /** True while an answer is being read aloud: shows the Stop speaking button. */
    speaking: Boolean = false,
    /** The "Show the model's confidence" setting. */
    showConfidence: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.recognizedText.isNotBlank()) {
            SectionCard(
                title = if (state.source == InputSource.TEXT) "You typed" else "You said",
                trailing = { CopyButton(state.recognizedText) },
            ) {
                SelectionContainer { Text(state.recognizedText, style = MaterialTheme.typography.titleMedium) }
            }
        }

        if (state.busy && state.status.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.status, style = MaterialTheme.typography.bodyLarge)
            }
        }

        RunFacts(state, showConfidence)

        state.confirmation?.let { c ->
            SectionCard(title = "Please confirm") {
                c.steps.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                c.reasons.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = actions.onConfirm) { Text("Do it") }
                    OutlinedButton(onClick = actions.onDecline) { Text("Don't") }
                }
            }
        }

        state.choice?.let { prompt ->
            SectionCard(title = "Which one?") {
                Text(prompt.message, style = MaterialTheme.typography.bodyLarge)
                prompt.choices.forEach { choice ->
                    OutlinedButton(onClick = { actions.onChoose(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.label)
                            choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                TextButton(onClick = actions.onDismissChoice) { Text("Cancel") }
            }
        }

        if (state.response.isNotBlank()) {
            SectionCard(title = "Assistant", trailing = { OutcomeBadge(state.outcome) }) {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    SelectionContainer { Text(state.response, style = MaterialTheme.typography.bodyLarge) }
                }
                state.answers.forEach { AnswerFacts(it) }
                if (speaking) OutlinedButton(onClick = actions.onStopSpeaking) { Text("Stop speaking") }
            }
        } else if (speaking) {
            OutlinedButton(onClick = actions.onStopSpeaking) { Text("Stop speaking") }
        }

        if (state.action.isNotBlank()) {
            Text("Now: ${state.action}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.results.isNotEmpty()) {
            SectionCard(title = "Results (${state.results.size})") {
                state.results.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier.fillMaxWidth().clickable { actions.onResultClick(item) }.padding(vertical = 8.dp),
                    ) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }

        state.permission?.let { need ->
            SectionCard(title = "Permission needed") {
                Text(
                    when (need) {
                        PermissionNeed.MICROPHONE -> "Cactus AI listens only while you hold or tap the microphone button. Allow microphone access to talk to it. Speech is turned into text on this phone."
                        PermissionNeed.CONTACTS -> "To find a person by name, Cactus AI needs to read your contacts. They are only searched on this phone and are never uploaded."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { actions.onGrantPermission(need) }) {
                    Text(if (need == PermissionNeed.MICROPHONE) "Allow microphone" else "Allow contacts")
                }
            }
        }

        if (state.hint == Hint.ENABLE_SCREEN_CONTROL) {
            SectionCard(title = "Optional: screen control") {
                Text(
                    "Scrolling, tapping and typing need the optional Accessibility service. It is off until you turn it on in Android settings, and you can turn it off there at any time. On a phone where Cactus AI was installed from an APK file, Android may first ask you to allow restricted settings (App info > ⋮ > Allow restricted settings).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = actions.onOpenAccessibility) { Text("Open Accessibility settings") }
            }
        }

        if (!state.interpretation.isNullOrBlank() || state.timings?.summary()?.isNotBlank() == true) {
            SectionCard(title = "Details") {
                state.interpretation?.takeIf { it.isNotBlank() }?.let {
                    Text("How I understood it", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                }
                state.timings?.summary()?.takeIf { it.isNotBlank() }?.let {
                    Text("Measured on this phone: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RawJson(state.rawJson, startOpen = showRawJson)
            }
        }
    }
}

/** Whether the command ran by itself or after a yes, and the model's own confidence (never a made-up one). */
@Composable
private fun RunFacts(state: AssistantUiState, showConfidence: Boolean) {
    val lines = buildList {
        if (showConfidence && state.confidenceKnown) {
            add(state.confidence?.let { "Model confidence: ${(it * 100).roundToInt()}%" } ?: "The model gave no confidence, so I ask before acting.")
        }
        state.executionMode?.let {
            add(
                when (it) {
                    ExecutionMode.AUTOMATIC -> if (state.busy) "Running automatically" else "Ran automatically"
                    ExecutionMode.CONFIRMED -> if (state.busy) "Running after you confirmed" else "Ran after you confirmed"
                },
            )
        }
    }
    if (lines.isEmpty()) return
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        lines.forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Where an answer came from, and how far it can be trusted, in words. */
@Composable
private fun AnswerFacts(answer: InfoAnswer) {
    val context = LocalContext.current
    val where = if (answer.origin == AnswerOrigin.ONLINE) {
        val checked = answer.retrievedAtMs?.let { " · checked " + DateFormat.getTimeFormat(context).format(Date(it)) }.orEmpty()
        "Online$checked"
    } else {
        "Answered on this phone"
    }
    Text(where, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    answer.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (answer.origin == AnswerOrigin.ONLINE && !answer.verified) {
        Text("Not independently confirmed. Open a source below to check.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RawJson(json: String?, startOpen: Boolean) {
    if (json.isNullOrBlank()) return
    var open by rememberSaveable(startOpen) { mutableStateOf(startOpen) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide raw JSON" else "Show raw JSON") }
    if (open) {
        Row {
            SelectionContainer(Modifier.weight(1f)) { Text(json, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            CopyButton(json)
        }
    }
}

/** Outcome as an icon plus words, never colour alone. */
@Composable
fun OutcomeBadge(outcome: Outcome?) {
    val (icon, label) = when (outcome) {
        Outcome.SUCCESS -> Icons.Default.CheckCircle to "Done"
        Outcome.FAILED -> Icons.Default.Warning to "Didn't work"
        Outcome.REFUSED -> Icons.Default.Info to "Not understood"
        Outcome.CANCELLED -> Icons.Default.Close to "Cancelled"
        Outcome.DECLINED -> Icons.Default.Close to "Declined"
        Outcome.NEEDS_INPUT -> Icons.Default.Info to "Needs permission"
        null -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (outcome == Outcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun VerticalSpace(height: Int) = Spacer(Modifier.height(height.dp))
