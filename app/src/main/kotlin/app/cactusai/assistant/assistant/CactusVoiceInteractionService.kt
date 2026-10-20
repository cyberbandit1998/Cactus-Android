package app.cactusai.assistant.assistant

import android.service.voice.VoiceInteractionService

/**
 * Registers Cactus AI as a selectable digital assistant. It deliberately does nothing in the
 * background: no hotword detection, no microphone use. Sessions start only when the user invokes the
 * assistant (the system's assistant gesture).
 */
class CactusVoiceInteractionService : VoiceInteractionService()
