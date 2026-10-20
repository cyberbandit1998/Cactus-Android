package app.cactusai.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cactusai.assistant.AppContainer
import app.cactusai.assistant.assistant.PermissionNeed
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.assistant.ui.components.PanelActions
import app.cactusai.assistant.ui.screens.CommandScreen
import app.cactusai.assistant.ui.screens.HistoryScreen
import app.cactusai.assistant.ui.screens.HomeScreen
import app.cactusai.assistant.ui.screens.ModelsScreen
import app.cactusai.assistant.ui.screens.SettingsScreen
import app.cactusai.core.engine.NativeLib
import app.cactusai.core.models.ModelKind
import app.cactusai.core.models.ModelStatus

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    COMMAND("Command", Icons.Default.Edit),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    MODELS("Models", Icons.Default.Build),
    SETTINGS("Settings", Icons.Default.SettingsIcon),
}

@Composable
fun AppRoot(container: AppContainer, startListening: Boolean) {
    val context = LocalContext.current
    val controller = container.controller
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val state by controller.state.collectAsStateWithLifecycle()
    val models by container.models.models.collectAsStateWithLifecycle()
    val engine by container.engine.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val history by container.history.entries.collectAsStateWithLifecycle()
    val thermal by container.thermal.status.collectAsStateWithLifecycle()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            controller.clear()
            controller.startListening()
        } else {
            controller.permissionDenied(PermissionNeed.MICROPHONE)
        }
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.retryAfterPermission() else controller.permissionDenied(PermissionNeed.CONTACTS)
    }

    // Launched as the system assistant: start listening once (a rotation does not repeat it), if voice is allowed.
    LaunchedEffect(startListening) {
        if (startListening) controller.startListening()
    }

    val panelActions = remember(controller) {
        PanelActions(
            onConfirm = controller::confirm,
            onDecline = controller::decline,
            onChoose = controller::choose,
            onDismissChoice = controller::dismissChoice,
            onResultClick = controller::onResultClicked,
            onGrantPermission = { need ->
                when (need) {
                    PermissionNeed.MICROPHONE -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    PermissionNeed.CONTACTS -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            },
            onOpenAccessibility = { openAccessibilitySettings(context) },
        )
    }

    val modelsReady = models.values.all { it.status is ModelStatus.Installed }
    val commandReady = (models[ModelKind.NEEDLE]?.status is ModelStatus.Installed)
    val thermalWarning = if (ThermalMonitor.shouldWarn(thermal)) {
        "Android reports the phone is ${ThermalMonitor.label(thermal).lowercase()}. It may slow the models down" +
            (if (ThermalMonitor.shouldPause(thermal)) " and I will pause until it cools." else ".")
    } else {
        null
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    voiceEnabled = settings.voiceActivationEnabled,
                    modelsReady = modelsReady,
                    engineLinked = container.engine.isLinked,
                    engineProblem = NativeLib.lastLoadError(),
                    thermalWarning = thermalWarning,
                    showRawJson = settings.expandRawJson,
                    onStartListening = controller::startListening,
                    onStopListening = controller::stopListening,
                    onCancel = controller::cancel,
                    onOpenModels = { tab = Tab.MODELS },
                    panelActions = panelActions,
                )
                Tab.COMMAND -> CommandScreen(
                    state = state,
                    ready = commandReady,
                    showRawJson = settings.expandRawJson,
                    onSubmit = controller::submitText,
                    onCancel = controller::cancel,
                    panelActions = panelActions,
                )
                Tab.HISTORY -> HistoryScreen(entries = history, onClear = container.history::clear)
                Tab.MODELS -> ModelsScreen(
                    models = models,
                    engine = engine,
                    lastTimings = state.timings,
                    thermalLabel = ThermalMonitor.label(thermal),
                    usedBytes = container.models.storageUsedBytes(),
                    freeBytes = container.models.freeBytes(),
                    onDownload = container.models::download,
                    onCancel = container.models::cancel,
                    onDelete = container.models::delete,
                    onReinstall = { kind ->
                        container.models.delete(kind)
                        container.models.download(kind)
                    },
                    onCheckUpdate = container.models::checkForUpdate,
                    onVerify = container.models::verify,
                )
                Tab.SETTINGS -> SettingsScreen(
                    container = container,
                    onOpenModels = { tab = Tab.MODELS },
                    onRequestMicrophone = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                )
            }
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        // Nothing sensible to fall back to; the user can open Accessibility from Android settings.
    }
}
