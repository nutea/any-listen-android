package io.github.nutea.anylisten.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.nutea.anylisten.core.data.download.DownloadCoordinator
import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.gateway.IpcAuthClient
import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.ProtocolAnyListenGateway
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.repo.LibraryRepository
import io.github.nutea.anylisten.core.data.repo.SessionRepository
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.data.session.SecureSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val http = NetworkFactory.client()
    val db = AppDatabase.create(appContext)
    val sessionStore = SecureSessionStore(appContext)
    val settings = AppSettingsStore(appContext)
    val gateway = ProtocolAnyListenGateway(http, IpcAuthClient(http))
    val mockGateway = MockAnyListenGateway()
    val library = LibraryRepository(db.libraryDao(), gateway)
    val session = SessionRepository(sessionStore, gateway) { library.refresh() }
    val downloadsDir = appContext.filesDir.resolve("downloads").apply { mkdirs() }
    val cacheDir = appContext.cacheDir.resolve("media").apply { mkdirs() }
    val downloads = DownloadCoordinator(
        dao = db.downloadDao(),
        libraryDao = db.libraryDao(),
        gateway = gateway,
        downloader = FileDownloader(http),
        downloadsDir = downloadsDir,
        cacheDir = cacheDir,
        wifiOnly = { runBlocking { settings.wifiOnly.first() } },
        onWifi = { onWifi() },
        usableBytes = { downloadsDir.usableSpace },
    )

    private fun onWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
