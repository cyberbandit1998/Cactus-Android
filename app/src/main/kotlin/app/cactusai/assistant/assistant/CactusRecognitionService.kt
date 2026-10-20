package app.cactusai.assistant.assistant

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Placeholder required by Android for voice interaction services. It does not recognise speech for
 * other apps: it answers "not supported". Cactus AI listens through its own assistant session.
 */
class CactusRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
            // The caller went away; nothing to report to.
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
