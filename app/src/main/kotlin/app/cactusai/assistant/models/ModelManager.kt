package app.cactusai.assistant.models

import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.core.models.DownloadException
import app.cactusai.core.models.DownloadProgress
import app.cactusai.core.models.ModelDownloader
import app.cactusai.core.models.ModelKind
import app.cactusai.core.models.ModelSpec
import app.cactusai.core.models.ModelStatus
import app.cactusai.core.models.ModelStore
import app.cactusai.core.models.Models
import app.cactusai.core.models.RemoteInfo
import app.cactusai.core.models.RemoteInfoFetcher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Busy { NONE, DOWNLOADING, CHECKING, VERIFYING }

data class ModelUi(
    val spec: ModelSpec,
    val status: ModelStatus = ModelStatus.NotInstalled,
    val busy: Busy = Busy.NONE,
    val progress: DownloadProgress? = null,
    /** Last result or error, written for the user. */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** true = a different file is published, false = up to date, null = not checked or not known. */
    val updateAvailable: Boolean? = null,
    val remote: RemoteInfo? = null,
    /** true = file matches its recorded SHA-256, false = it changed, null = not checked. */
    val integrityOk: Boolean? = null,
)

/** Download, verify, update-check and delete for the two official models. Works offline once installed. */
class ModelManager(
    private val store: ModelStore,
    private val downloader: ModelDownloader,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val jobs = HashMap<ModelKind, Job>()

    private val _models = MutableStateFlow(Models.all.associate { it.kind to ModelUi(it, store.status(it)) })
    val models: StateFlow<Map<ModelKind, ModelUi>> = _models.asStateFlow()

    fun refresh() {
        _models.update { map -> map.mapValues { (_, ui) -> if (ui.busy == Busy.NONE) ui.copy(status = store.status(ui.spec)) else ui } }
    }

    fun storageUsedBytes(): Long = store.usedBytes()

    fun freeBytes(): Long = store.dir.let { d -> d.mkdirs(); d.usableSpace }

    fun modelsDir(): File = store.dir

    fun download(kind: ModelKind) {
        synchronized(jobs) {
            if (jobs[kind]?.isActive == true) return
            val spec = Models.of(kind)
            if (store.status(spec) is ModelStatus.Installed) {
                set(kind) { it.copy(status = store.status(spec), message = "${spec.displayName} is already installed.", messageIsError = false) }
                return
            }
            if (!network.isOnline()) {
                set(kind) { it.copy(message = "You are offline. Connect to the internet to download ${spec.displayName}; the download continues where it stopped.", messageIsError = true) }
                return
            }
            jobs[kind] = scope.launch(Dispatchers.IO) { runDownload(spec) }
        }
    }

    fun cancel(kind: ModelKind) {
        synchronized(jobs) { jobs.remove(kind)?.cancel() }
    }

    fun delete(kind: ModelKind) {
        cancel(kind)
        val spec = Models.of(kind)
        store.delete(spec)
        set(kind) { ModelUi(spec, store.status(spec), message = "${spec.displayName} was deleted from this phone.") }
    }

    fun checkForUpdate(kind: ModelKind) {
        val spec = Models.of(kind)
        if (!network.isOnline()) {
            set(kind) { it.copy(message = "You are offline, so I cannot check for updates.", messageIsError = true) }
            return
        }
        scope.launch(Dispatchers.IO) {
            set(kind) { it.copy(busy = Busy.CHECKING, message = "Checking for an update…", messageIsError = false) }
            val remote = RemoteInfoFetcher.fetch(spec)
            val local = (store.status(spec) as? ModelStatus.Installed)?.sha256
            val update = if (remote.sha256 != null && local != null) remote.sha256 != local else null
            val text = when {
                remote.sha256 == null && remote.sizeBytes == null -> "Could not reach the model server."
                update == true -> "A different version of ${spec.displayName} is published. Delete the installed one and download again to update."
                update == false -> "${spec.displayName} is up to date."
                else -> "${spec.displayName} could not be compared (no checksum is published or recorded)."
            }
            set(kind) { it.copy(busy = Busy.NONE, remote = remote, updateAvailable = update, message = text, messageIsError = remote.sha256 == null && remote.sizeBytes == null) }
        }
    }

    /** Re-reads the file and compares it with the SHA-256 recorded at install time. */
    fun verify(kind: ModelKind) {
        val spec = Models.of(kind)
        scope.launch(Dispatchers.IO) {
            set(kind) { it.copy(busy = Busy.VERIFYING, message = "Checking the file…", messageIsError = false) }
            val ok = store.verifyHash(spec)
            set(kind) {
                it.copy(
                    busy = Busy.NONE,
                    status = store.status(spec),
                    integrityOk = ok,
                    message = when (ok) {
                        true -> "${spec.displayName} matches its recorded checksum."
                        false -> "${spec.displayName} has changed since it was installed. Delete it and download it again."
                        null -> "No checksum was recorded for ${spec.displayName}, so only its structure was checked."
                    },
                    messageIsError = ok == false,
                )
            }
        }
    }

    private suspend fun runDownload(spec: ModelSpec) {
        val kind = spec.kind
        set(kind) { it.copy(busy = Busy.DOWNLOADING, progress = null, message = "Contacting the model server…", messageIsError = false, integrityOk = null) }
        try {
            val remote = withContext(Dispatchers.IO) { RemoteInfoFetcher.fetch(spec) }
            set(kind) { it.copy(remote = remote) }
            downloader.download(spec, store.dir, remote) { p -> set(kind) { it.copy(progress = p, message = null) } }
            set(kind) {
                it.copy(busy = Busy.NONE, progress = null, status = store.status(spec), updateAvailable = false, messageIsError = false,
                    message = "${spec.displayName} is installed. It works offline from now on.")
            }
        } catch (e: CancellationException) {
            set(kind) { it.copy(busy = Busy.NONE, progress = null, status = store.status(spec), message = "Download stopped. Tap Resume to continue where it left off.", messageIsError = false) }
            throw e
        } catch (e: DownloadException) {
            val hint = when {
                e.kind == DownloadException.Kind.HASH_MISMATCH || e.kind == DownloadException.Kind.SIZE_MISMATCH -> " The partial file was discarded; try again."
                e.retryable -> " Tap Resume to try again."
                else -> ""
            }
            set(kind) { it.copy(busy = Busy.NONE, progress = null, status = store.status(spec), message = "${e.message}.$hint".replace("..", "."), messageIsError = true) }
        } catch (e: Exception) {
            set(kind) { it.copy(busy = Busy.NONE, progress = null, status = store.status(spec), message = "Download failed: ${e.message}", messageIsError = true) }
        } finally {
            synchronized(jobs) { jobs.remove(kind) }
        }
    }

    private fun set(kind: ModelKind, transform: (ModelUi) -> ModelUi) {
        _models.update { map -> map + (kind to transform(map.getValue(kind))) }
    }
}
