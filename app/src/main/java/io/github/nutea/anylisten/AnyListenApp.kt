package io.github.nutea.anylisten

import android.app.Application
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.core.playback.ContainerHolder

class AnyListenApp : Application(), ContainerHolder {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
