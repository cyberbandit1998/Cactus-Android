package app.cactusai.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.assistant.AssistantUiState
import app.cactusai.assistant.ui.components.AssistantPanel
import app.cactusai.assistant.ui.components.PanelActions

private val EXAMPLES = listOf(
    "Open Spotify",
    "Search Google for 3D printing ideas",
    "Set a timer for 15 minutes",
    "Open Bluetooth settings",
    "Check my battery",
    "Search YouTube for Bambu Lab P1S tips",
)

@Composable
fun CommandScreen(
    state: AssistantUiState,
    ready: Boolean,
    showRawJson: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    panelActions: PanelActions,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (text.isNotBlank() && !state.busy) onSubmit(text) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Command", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Type what you would say. The Needle model turns it into an Android action; it is not a chat model, so it only understands commands.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Command") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { submit() }, enabled = ready && text.isNotBlank() && !state.busy) { Text("Run command") }
            if (state.busy) OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        if (!ready) {
            Text(
                "The models are not ready yet. Open the Models tab to download them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("Examples", style = MaterialTheme.typography.labelLarge)
        EXAMPLES.forEach { example ->
            TextButton(onClick = { text = example }) { Text(example) }
        }
        AssistantPanel(state = state, actions = panelActions, showRawJson = showRawJson)
    }
}
