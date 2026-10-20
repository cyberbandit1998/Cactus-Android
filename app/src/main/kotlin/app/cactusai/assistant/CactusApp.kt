package app.cactusai.assistant

import android.app.Application

class CactusApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
