package app.cactusai.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** One dark scheme, always. Text/background pairs are chosen for at least WCAG AA contrast. */
private val CactusDark = darkColorScheme(
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF1F5130),
    onPrimaryContainer = Color(0xFFA0F5AE),
    secondary = Color(0xFFB4CCB8),
    onSecondary = Color(0xFF203526),
    secondaryContainer = Color(0xFF364B3C),
    onSecondaryContainer = Color(0xFFD0E8D4),
    tertiary = Color(0xFFA1CED9),
    onTertiary = Color(0xFF023640),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1411),
    onBackground = Color(0xFFDEE4DD),
    surface = Color(0xFF0E1411),
    onSurface = Color(0xFFDEE4DD),
    surfaceVariant = Color(0xFF1B241F),
    onSurfaceVariant = Color(0xFFC0C9C0),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404943),
)

@Composable
fun CactusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CactusDark, content = content)
}
