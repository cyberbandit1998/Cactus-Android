package app.cactusai.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cactusai.assistant.ui.AppRoot
import app.cactusai.assistant.ui.theme.CactusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as CactusApp).container
        // Opened through the system's "assist" action (assistant gesture): start listening, but only a fresh launch.
        val asAssistant = savedInstanceState == null && intent?.action == Intent.ACTION_ASSIST
        setContent {
            CactusTheme {
                AppRoot(container = container, startListening = asAssistant)
            }
        }
    }
}
