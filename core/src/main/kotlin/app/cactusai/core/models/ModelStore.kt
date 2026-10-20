package app.cactusai.core.models

import java.io.File
import java.io.IOException

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus

    /** An interrupted download that can be resumed. */
    data class Partial(val bytes: Long) : ModelStatus

    data class Installed(val file: File, val sizeBytes: Long, val sha256: String?) : ModelStatus

    /** A file is there but is not a usable model (truncated, wrong format). */
    data class Invalid(val reason: String) : ModelStatus
}

/** Models live in one private directory; nothing here is shared with other apps. */
class ModelStore(val dir: File) {
    fun file(spec: ModelSpec): File = File(dir, spec.fileName)
    fun partFile(spec: ModelSpec): File = File(dir, spec.fileName + ".part")

    /** Cheap check (header and tensor table only). Use [verifyHash] for a full re-read. */
    fun status(spec: ModelSpec): ModelStatus {
        val f = file(spec)
        if (f.exists()) {
            return when (val c = CactFile.inspect(f)) {
                is CactFile.Check.Valid -> ModelStatus.Installed(f, f.length(), readHash(f))
                is CactFile.Check.Invalid -> ModelStatus.Invalid(c.reason)
            }
        }
        val p = partFile(spec)
        return if (p.exists() && p.length() > 0) ModelStatus.Partial(p.length()) else ModelStatus.NotInstalled
    }

    /** true = file matches the hash recorded when it was installed, false = changed, null = nothing recorded. */
    fun verifyHash(spec: ModelSpec): Boolean? {
        val f = file(spec)
        val recorded = readHash(f) ?: return null
        return Sha256.of(f) == recorded
    }

    fun delete(spec: ModelSpec) {
        file(spec).delete()
        partFile(spec).delete()
        hashFile(file(spec)).delete()
    }

    fun usedBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun readHash(f: File): String? =
        try {
            hashFile(f).takeIf { it.isFile }?.readText()?.trim()?.lowercase()?.takeIf(Sha256::isHex64)
        } catch (_: IOException) {
            null
        }

    companion object {
        fun hashFile(model: File): File = File(model.parentFile, model.name + ".sha256")

        fun writeHash(model: File, sha256: String) {
            try {
                hashFile(model).writeText(sha256 + "\n")
            } catch (_: IOException) {
                // The model is still valid; it just cannot be re-verified later.
            }
        }
    }
}
