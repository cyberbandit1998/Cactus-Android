package app.cactusai.assistant.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.cactusai.assistant.AppContainer
import app.cactusai.assistant.BuildConfig
import app.cactusai.assistant.a11y.AccessibilityStatus
import app.cactusai.assistant.assistant.CactusVoiceInteractionService
import app.cactusai.assistant.system.Permissions
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.assistant.ui.components.SectionCard
import app.cactusai.core.engine.NativeLib
import app.cactusai.core.models.Models
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenModels: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestContacts: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.state.collectAsState()
    val thermal by container.thermal.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    var confirmDeleteHistory by remember { mutableStateOf(false) }
    var confirmDeleteModels by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                container.settings.update { it.copy(folderUris = it.folderUris + uri.toString()) }
            } catch (_: SecurityException) {
                // The picker did not offer a persistable grant; the folder cannot be remembered.
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        SectionCard(title = "Models") {
            Text("Download, verify, update or delete the Whistle and Needle models.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenModels) { Text("Open Models") }
        }

        SectionCard(title = "Microphone and voice") {
            SwitchRow("Voice activation", "Allows the microphone button and the assistant gesture to listen. Turn off to use typed commands only.", settings.voiceActivationEnabled) { on ->
                container.settings.update { it.copy(voiceActivationEnabled = on) }
            }
            SwitchRow("Trim silence before transcribing", "Removes quiet parts at the start and end of a recording.", settings.trimSilence) { on ->
                container.settings.update { it.copy(trimSilence = on) }
            }
            SwitchRow("Help recognise app names", "Gives the speech model the names of your installed apps, so \"Spotify\" is spelled right. They stay on the phone.", settings.biasSpeechToAppNames) { on ->
                container.settings.update { it.copy(biasSpeechToAppNames = on) }
            }
            Text("Language: English. Whistle also supports German, French, Spanish, Italian, Dutch and Polish; they are not switched on yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("The microphone is never open in the background. A wake word is not available: this app only listens when you press the button or open the assistant.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "Digital assistant") {
            @Suppress("UNUSED_EXPRESSION") refresh
            Text(assistantStatus(context), style = MaterialTheme.typography.bodyLarge)
            Text(
                "Choose Cactus AI under Default apps > Digital assistant app. Then the system's assistant gesture (on many Pixels: press and hold the power button, if that is set to the assistant under Settings > System > Gestures) opens Cactus AI over the current app. " +
                    "Android may keep some assistant features for Google's own assistant, so what your phone does with the gesture is something to test on the phone itself. Hotword (\"Hey ...\") activation is not provided.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { startSettings(context, AndroidSettings.ACTION_VOICE_INPUT_SETTINGS, AndroidSettings.ACTION_SETTINGS) }) { Text("Open assistant settings") }
        }

        SectionCard(title = "Accessibility (optional)") {
            @Suppress("UNUSED_EXPRESSION") refresh
            val enabled = AccessibilityStatus.isEnabledInSettings(context)
            Text(
                when {
                    AccessibilityStatus.isConnected -> "On and connected."
                    enabled -> "Switched on in Android settings; connecting…"
                    else -> "Off. This is the default."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Lets you say things like \"scroll down\", \"go back\", \"tap search\" and \"type hello\". It looks at the screen only to find what you named and keeps nothing. It never types into password or payment fields, does not act inside Android's permission or install screens, and asks before risky taps such as Pay or Delete. " +
                    "You turn it on yourself in Android's Accessibility settings and can turn it off there at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Installed from an APK file? Android 13 and newer first greys the switch out. Open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startSettings(context, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Accessibility settings") }
                OutlinedButton(onClick = { startAppInfo(context) }) { Text("App info") }
            }
        }

        SectionCard(title = "Permissions") {
            @Suppress("UNUSED_EXPRESSION") refresh
            PermissionRow("Microphone", "Needed for voice commands.", Permissions.hasMicrophone(context), onRequestMicrophone)
            PermissionRow("Contacts", "Needed to call or text a person by name, and to find contacts.", Permissions.hasContacts(context), onRequestContacts)
            OutlinedButton(onClick = { startAppInfo(context) }) { Text("Review or revoke in Android settings") }
        }

        SectionCard(title = "Confirmations") {
            SwitchRow("Confirm before calls and texts", "Cactus AI only ever opens a filled-in dialer or message. This also asks you first.", settings.confirmCallsAndTexts) { on ->
                container.settings.update { it.copy(confirmCallsAndTexts = on) }
            }
            Text("Act without asking at ${(settings.actThreshold * 100).roundToInt()}% confidence or more", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.actThreshold,
                onValueChange = { v -> container.settings.update { it.copy(actThreshold = (v * 20).roundToInt() / 20f) } },
                valueRange = 0.5f..0.95f,
                steps = 8,
            )
            Text("Below that, the command is shown to you first. Higher is safer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "Files") {
            Text(
                "Android does not let apps search the whole phone. Choose the folders you want \"find my file\" to look in; only their file names are searched, on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            settings.folderUris.forEach { uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Uri.parse(uri).lastPathSegment ?: uri, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        try {
                            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: SecurityException) {
                        }
                        container.settings.update { it.copy(folderUris = it.folderUris - uri) }
                    }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose a folder") }
        }

        SectionCard(title = "Privacy") {
            Text(
                "Everything runs on this phone: speech recognition, command understanding and your history. There is no account, no analytics, no ads and no cloud AI. " +
                    "The internet is used only to download the models and for web searches you ask for. Recordings are not saved. The engine's own usage reporting is switched off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmDeleteHistory = true }) { Text("Delete history") }
                OutlinedButton(onClick = { confirmDeleteModels = true }) { Text("Delete models") }
            }
        }

        SectionCard(title = "Debugging") {
            SwitchRow("Show raw JSON expanded", "Opens the model's raw answer by default in the details section.", settings.expandRawJson) { on ->
                container.settings.update { it.copy(expandRawJson = on) }
            }
            Text("Version ${BuildConfig.VERSION_NAME} · native shim ${shimVersion()} · engine linked: ${container.engine.isLinked}", style = MaterialTheme.typography.bodySmall)
            Text("Phone temperature: ${ThermalMonitor.label(thermal)}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmDeleteHistory) {
        AlertDialog(
            onDismissRequest = { confirmDeleteHistory = false },
            title = { Text("Delete history?") },
            text = { Text("All saved commands are removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmDeleteHistory = false; container.history.clear() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteHistory = false }) { Text("Keep") } },
        )
    }
    if (confirmDeleteModels) {
        AlertDialog(
            onDismissRequest = { confirmDeleteModels = false },
            title = { Text("Delete both models?") },
            text = { Text("Voice and commands stop working until you download them again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteModels = false
                    Models.all.forEach { container.models.delete(it.kind) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModels = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(name: String, why: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("$name: ${if (granted) "allowed" else "not allowed"}", style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) OutlinedButton(onClick = onRequest) { Text("Allow") }
    }
}

private fun shimVersion(): String = try {
    if (NativeLib.load()) NativeLib.nativeShimVersion() else "not loaded"
} catch (_: UnsatisfiedLinkError) {
    "not loaded"
}

private fun assistantStatus(context: Context): String {
    val mine = ComponentName(context, CactusVoiceInteractionService::class.java)
    val current = try {
        AndroidSettings.Secure.getString(context.contentResolver, "assistant")
            ?: AndroidSettings.Secure.getString(context.contentResolver, "voice_interaction_service")
    } catch (_: RuntimeException) {
        null
    }
    return when {
        current.isNullOrBlank() -> "No digital assistant is selected, or Android does not tell apps which one."
        current.equals(mine.flattenToString(), ignoreCase = true) || current.equals(mine.flattenToShortString(), ignoreCase = true) -> "Cactus AI is your digital assistant."
        else -> "Another assistant is selected: ${current.substringBefore('/')}"
    }
}

private fun startSettings(context: Context, action: String, fallback: String? = null) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        if (fallback != null) startSettings(context, fallback)
    }
}

private fun startAppInfo(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        startSettings(context, AndroidSettings.ACTION_SETTINGS)
    }
}
