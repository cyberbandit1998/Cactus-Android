package app.cactusai.assistant.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.cactusai.assistant.CactusApp
import app.cactusai.assistant.MainActivity
import app.cactusai.assistant.ui.AssistantOverlay
import app.cactusai.assistant.ui.theme.CactusTheme

/**
 * The assistant shown over the current app when the system's assistant gesture is used.
 * It is a normal Compose screen driven by the same [AssistantController] as the main app, so
 * speech, interpretation, confirmation and execution behave identically.
 */
class CactusVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val container get() = (context.applicationContext as CactusApp).container
    private val owner = SessionLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        owner.create()
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            CactusTheme {
                AssistantOverlay(
                    controller = container.controller,
                    models = container.models,
                    speech = container.speech,
                    settings = container.settings,
                    onClose = { hide() },
                    onOpenApp = {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        hide()
                    },
                )
            }
        }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        owner.start()
        container.controller.clear()
        // Start listening right away, as an assistant should, but only if the user allows it.
        container.controller.startListening()
    }

    override fun onHide() {
        // Stop the microphone and anything still waiting for the user as soon as the overlay goes away. A command that is already
        // running is left to finish: opening the browser for a search is what hides the overlay.
        container.controller.onOverlayHidden()
        owner.stop()
        super.onHide()
    }

    override fun onDestroy() {
        owner.destroy()
        super.onDestroy()
    }

    /** Gives the Compose view the lifecycle owners it looks for, since a session is not an Activity. */
    private class SessionLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun create() {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
