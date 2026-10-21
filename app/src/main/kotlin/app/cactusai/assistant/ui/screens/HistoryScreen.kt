package app.cactusai.assistant.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.ui.components.CopyButton
import app.cactusai.assistant.ui.components.OutcomeBadge
import app.cactusai.assistant.ui.components.SectionCard
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.InputSource
import java.util.Date

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear history") }
        }
        Text(
            "Stored only on this phone. Clearing it deletes it for good.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text("Nothing yet. Commands you run will appear here.", style = MaterialTheme.typography.bodyLarge)
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.id }) { entry ->
                    val whenText = DateFormat.getMediumDateFormat(context).format(Date(entry.timeMs)) + " " + DateFormat.getTimeFormat(context).format(Date(entry.timeMs))
                    SectionCard(
                        title = whenText + if (entry.source == InputSource.VOICE) " · voice" else " · typed",
                        trailing = { OutcomeBadge(entry.outcome) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) { Text(entry.text, style = MaterialTheme.typography.titleMedium) }
                            CopyButton(entry.text)
                        }
                        entry.interpretation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                        Text(historyDetail(entry), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        entry.timings?.summary()?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All saved commands and results will be deleted from this phone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}
