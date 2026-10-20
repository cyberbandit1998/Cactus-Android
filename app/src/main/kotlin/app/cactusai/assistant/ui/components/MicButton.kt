package app.cactusai.assistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val HOLD_TO_TALK_MS = 600L

/**
 * Push-to-talk button. Press and hold, then let go to send; or tap once to start and tap again to
 * stop. Screen readers get one action that toggles listening. While listening it grows with the
 * loudness of the voice, as a visible microphone activity indicator.
 */
@Composable
fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    level: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
    size: Dp = 168.dp,
) {
    val isListening by rememberUpdatedState(listening)
    val isEnabled by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val pulse by animateFloatAsState(targetValue = if (listening) 1f + level.coerceIn(0f, 1f) * 0.25f else 1f, label = "micPulse")
    val container = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val content = if (listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val description = if (listening) "Stop listening and run the command" else "Start listening. Hold to talk or tap to start."

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .scale(pulse)
            .clip(CircleShape)
            .background(if (enabled) container else MaterialTheme.colorScheme.outlineVariant)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!isEnabled) return@detectTapGestures
                    if (isListening) {
                        stop()
                        return@detectTapGestures
                    }
                    val pressedAt = System.currentTimeMillis()
                    start()
                    tryAwaitRelease()
                    // A long press is "hold to talk": letting go sends. A short tap leaves it listening.
                    if (System.currentTimeMillis() - pressedAt >= HOLD_TO_TALK_MS) stop()
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = if (listening) "Stop listening" else "Start listening") {
                    if (isEnabled) {
                        if (isListening) stop() else start()
                    }
                    true
                }
            },
    ) {
        Icon(
            imageVector = if (listening) CactusIcons.Stop else CactusIcons.Mic,
            contentDescription = null,
            tint = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2.4f),
        )
    }
}
