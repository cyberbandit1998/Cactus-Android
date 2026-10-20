package app.cactusai.assistant

import android.app.Application
import app.cactusai.assistant.actions.AndroidActionExecutor
import app.cactusai.assistant.actions.AppCatalog
import app.cactusai.assistant.actions.ContactsLookup
import app.cactusai.assistant.actions.DeviceActions
import app.cactusai.assistant.actions.FileSearch
import app.cactusai.assistant.assistant.AssistantController
import app.cactusai.assistant.audio.MicRecorder
import app.cactusai.assistant.engine.EngineHost
import app.cactusai.assistant.history.HistoryRepository
import app.cactusai.assistant.models.ModelManager
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.core.engine.NativeEngine
import app.cactusai.core.history.HistoryStore
import app.cactusai.core.models.ModelDownloader
import app.cactusai.core.models.ModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Plain constructor wiring: one instance of everything, created on first use. No DI framework. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(app)
    val network = NetworkMonitor(app)
    val thermal = ThermalMonitor(app)

    /** Models live in the app's private storage: no other app can read them, and they are not backed up. */
    val modelStore = ModelStore(File(app.filesDir, "models"))
    val models = ModelManager(modelStore, ModelDownloader(), network, scope)

    val engine = EngineHost(NativeEngine(), modelStore)

    val history = HistoryRepository(HistoryStore(File(app.filesDir, "history/history.json")))

    val apps = AppCatalog(app)
    val contacts = ContactsLookup(app)
    private val executor = AndroidActionExecutor(app, apps, contacts, DeviceActions(app), FileSearch(app), network, settings)
    val recorder = MicRecorder(app)

    val controller = AssistantController(app, scope, settings, engine, recorder, executor, history, apps, thermal)
}
