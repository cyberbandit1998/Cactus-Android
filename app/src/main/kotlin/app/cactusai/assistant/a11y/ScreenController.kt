package app.cactusai.assistant.a11y

sealed interface ScreenResult {
    data class Done(val message: String) : ScreenResult
    data class NotFound(val message: String) : ScreenResult

    /** Refused on purpose, for example a password field. */
    data class Blocked(val message: String) : ScreenResult

    /** Several things on screen matched; the user picks. */
    data class Ambiguous(val message: String, val options: List<String>, val pick: suspend (Int) -> ScreenResult) : ScreenResult

    /** The tap would be hard to undo (pay, send, delete...). Runs only if the user approves. */
    data class NeedsApproval(val message: String, val proceed: suspend () -> ScreenResult) : ScreenResult
}

/** What the Accessibility service can do. Implemented only by the service, which the user enables in Android settings. */
interface ScreenController {
    suspend fun scroll(down: Boolean): ScreenResult
    suspend fun navigate(destination: String): ScreenResult
    suspend fun tapText(text: String): ScreenResult
    suspend fun tapResult(position: Int): ScreenResult
    suspend fun typeText(text: String): ScreenResult
    suspend fun findOnScreen(text: String): ScreenResult
}

/** Set while the Accessibility service is connected; null otherwise, which is the default. */
object ScreenControl {
    @Volatile
    var controller: ScreenController? = null
        internal set

    val isAvailable: Boolean get() = controller != null
}
