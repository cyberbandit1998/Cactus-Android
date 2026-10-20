package app.cactusai.core.models

enum class ModelKind { NEEDLE, WHISTLE }

/**
 * Where an official model lives. Names come from the upstream Needle package
 * (`needle/agent/fetch.py`: repos Cactus-Compute/needle3 and Cactus-Compute/whistle,
 * files needle3.cact and whistle.cact). The sizes are the published approximate sizes, used only
 * for the "about N MB" label and the free-space check; the real size comes from the server.
 */
data class ModelSpec(
    val kind: ModelKind,
    val displayName: String,
    val repoId: String,
    val fileName: String,
    val approxBytes: Long,
    val purpose: String,
) {
    val resolveUrl: String get() = "https://huggingface.co/$repoId/resolve/main/$fileName"
    val treeApiUrl: String get() = "https://huggingface.co/api/models/$repoId/tree/main"
    val pageUrl: String get() = "https://huggingface.co/$repoId"
}

object Models {
    val NEEDLE_3 = ModelSpec(
        kind = ModelKind.NEEDLE,
        displayName = "Needle 3",
        repoId = "Cactus-Compute/needle3",
        fileName = "needle3.cact",
        approxBytes = 35_000_000L,
        purpose = "Understands commands and picks the Android action",
    )

    val WHISTLE = ModelSpec(
        kind = ModelKind.WHISTLE,
        displayName = "Whistle",
        repoId = "Cactus-Compute/whistle",
        fileName = "whistle.cact",
        approxBytes = 16_900_000L,
        purpose = "Turns your voice into text, offline",
    )

    val all: List<ModelSpec> = listOf(WHISTLE, NEEDLE_3)

    fun of(kind: ModelKind): ModelSpec = all.first { it.kind == kind }
}
