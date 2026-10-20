package app.cactusai.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import app.cactusai.core.engine.NativeEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val linked = NativeEngine().isLinked
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Text("Cactus AI skeleton. Engine linked: $linked")
            }
        }
    }
}
