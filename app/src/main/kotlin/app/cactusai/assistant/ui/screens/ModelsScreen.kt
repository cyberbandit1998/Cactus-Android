package app.cactusai.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cactusai.assistant.engine.EngineState
import app.cactusai.assistant.engine.LoadStage
import app.cactusai.assistant.engine.ModelRuntime
import app.cactusai.assistant.models.Busy
import app.cactusai.assistant.models.ModelUi
import app.cactusai.assistant.ui.components.SectionCard
import app.cactusai.core.metrics.Timings
import app.cactusai.core.models.ModelKind
import app.cactusai.core.models.ModelStatus
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

@Composable
fun ModelsScreen(
    models: Map<ModelKind, ModelUi>,
    engine: EngineState,
    lastTimings: Timings?,
    thermalLabel: String,
    usedBytes: Long,
    freeBytes: Long,
    onDownload: (ModelKind) -> Unit,
    onCancel: (ModelKind) -> Unit,
    onDelete: (ModelKind) -> Unit,
    onReinstall: (ModelKind) -> Unit,
    onCheckUpdate: (ModelKind) -> Unit,
    onVerify: (ModelKind) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Models", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "The official Cactus Compute models, downloaded once from Hugging Face into this app's private storage. After that nothing needs the internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        models.values.forEach { ui ->
            val runtime = if (ui.spec.kind == ModelKind.NEEDLE) engine.needle else engine.whistle
            ModelCard(ui, runtime, onDownload, onCancel, onDelete, onReinstall, onCheckUpdate, onVerify)
        }

        SectionCard(title = "Storage") {
            Text("Models use ${formatBytes(usedBytes)}. Free space on this phone: ${formatBytes(freeBytes)}.", style = MaterialTheme.typography.bodyMedium)
            Text("The files are private to this app and are not backed up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "Performance on this phone") {
            Text("Engine linked into this build: ${if (engine.engineLinked) "yes" else "not yet checked or no"}", style = MaterialTheme.typography.bodyMedium)
            Text("Phone temperature: $thermalLabel", style = MaterialTheme.typography.bodyMedium)
            engine.needlePrefixTokens?.let {
                Text("Command model setup: $it prompt tokens in ${engine.needleInitMs ?: 0} ms (re-done only when the tools or the date change)", style = MaterialTheme.typography.bodyMedium)
            }
            if (lastTimings == null) {
                Text(
                    "No timings yet. After you run a command, the measured load, speech, command and total times appear here. This app ships no benchmark numbers: everything shown was measured on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Last command: ${lastTimings.summary()}", style = MaterialTheme.typography.bodyMedium)
                lastTimings.whistleTtftMs?.let { Text("Whistle reported ${"%.1f".format(Locale.US, it)} ms to first token" + (lastTimings.whistleDecodeTps?.let { t -> " and ${"%.0f".format(Locale.US, t)} tokens/s" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                lastTimings.needleDecodeTps?.let { Text("Needle reported ${"%.0f".format(Locale.US, it)} tokens/s decoding" + (lastTimings.needlePrefillTps?.let { t -> " and ${"%.0f".format(Locale.US, t)} tokens/s prefill" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ModelCard(
    ui: ModelUi,
    runtime: ModelRuntime,
    onDownload: (ModelKind) -> Unit,
    onCancel: (ModelKind) -> Unit,
    onDelete: (ModelKind) -> Unit,
    onReinstall: (ModelKind) -> Unit,
    onCheckUpdate: (ModelKind) -> Unit,
    onVerify: (ModelKind) -> Unit,
) {
    val kind = ui.spec.kind
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }
    val installed = ui.status as? ModelStatus.Installed

    SectionCard(title = "${ui.spec.displayName} · ${ui.spec.purpose}") {
        val statusText = when {
            ui.busy == Busy.DOWNLOADING -> ui.progress?.let { p ->
                val pct = p.totalBytes?.takeIf { it > 0 }?.let { " ${(p.downloadedBytes * 100 / it).coerceIn(0, 100)}%" } ?: ""
                "Downloading$pct · ${formatBytes(p.downloadedBytes)}" + (p.totalBytes?.let { " of ${formatBytes(it)}" } ?: "") + " · ${formatBytes(p.bytesPerSecond)}/s"
            } ?: "Starting download…"
            ui.status is ModelStatus.Installed -> "Installed · ${formatBytes(installed!!.sizeBytes)}"
            ui.status is ModelStatus.Partial -> "Partly downloaded (${formatBytes((ui.status as ModelStatus.Partial).bytes)}). Tap Resume."
            ui.status is ModelStatus.Invalid -> "Damaged: ${(ui.status as ModelStatus.Invalid).reason}. Delete it and download again."
            else -> "Not installed · about ${formatBytes(ui.spec.approxBytes)}"
        }
        Text(statusText, style = MaterialTheme.typography.bodyLarge)
        if (ui.busy == Busy.DOWNLOADING) {
            val p = ui.progress
            if (p?.totalBytes != null && p.totalBytes!! > 0) {
                LinearProgressIndicator(progress = { (p.downloadedBytes.toFloat() / p.totalBytes!!).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Text(
            when (runtime.stage) {
                LoadStage.NOT_LOADED -> "Engine: not loaded yet. It loads the first time you use it."
                LoadStage.LOADING -> "Engine: loading…"
                LoadStage.LOADED -> "Engine: loaded" + (runtime.loadMs?.let { " in $it ms (measured here)" } ?: "")
                LoadStage.FAILED -> "Engine: could not load. ${runtime.error.orEmpty()}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (runtime.stage == LoadStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (runtime.loadedSha != null && installed?.sha256 != null && runtime.loadedSha != installed.sha256) {
            Text("A different file is installed than the one in memory. Close and reopen the app to use it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (ui.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }

        when {
            ui.busy == Busy.DOWNLOADING -> OutlinedButton(onClick = { onCancel(kind) }) { Text("Stop download") }
            installed == null -> Button(onClick = { onDownload(kind) }) { Text(if (ui.status is ModelStatus.Partial) "Resume download" else "Download") }
        }
        if (ui.busy != Busy.DOWNLOADING) {
            if (installed != null) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCheckUpdate(kind) }, enabled = ui.busy == Busy.NONE) { Text("Check for update") }
                    OutlinedButton(onClick = { onVerify(kind) }, enabled = ui.busy == Busy.NONE) { Text("Verify file") }
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmReinstall = true }) { Text("Delete and reinstall") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            } else if (ui.status !is ModelStatus.NotInstalled) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${ui.spec.displayName}?") },
            text = { Text("The file is removed from this phone. Voice and commands stop working until you download it again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(kind) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text("Delete and reinstall ${ui.spec.displayName}?") },
            text = { Text("The file is deleted and downloaded again (about ${formatBytes(ui.spec.approxBytes)}). This needs an internet connection.") },
            confirmButton = { TextButton(onClick = { confirmReinstall = false; onReinstall(kind) }) { Text("Reinstall") } },
            dismissButton = { TextButton(onClick = { confirmReinstall = false }) { Text("Cancel") } },
        )
    }
}
