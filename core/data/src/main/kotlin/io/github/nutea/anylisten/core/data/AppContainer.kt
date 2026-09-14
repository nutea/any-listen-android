package io.github.nutea.anylisten.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withPermit
import io.github.nutea.anylisten.core.data.connection.AndroidNetworkMonitor
import io.github.nutea.anylisten.core.data.connection.ConnectionState
import io.github.nutea.anylisten.core.data.connection.SessionConnectionManager
import io.github.nutea.anylisten.core.data.connection.WebSocketIpcChannel
import io.github.nutea.anylisten.core.data.download.DownloadCoordinator
import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.gateway.IpcAuthClient
import io.github.nutea.anylisten.core.data.gateway.ProtocolAnyListenGateway
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.repo.LibraryRepository
import io.github.nutea.anylisten.core.data.repo.RecentlyPlayedRecorder
import io.github.nutea.anylisten.core.data.repo.SessionRepository
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.data.session.SecureSessionStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val maintenance = PlaybackMaintenance()
    fun setPlaybackActive(active: Boolean) { maintenance.setActive(active) }

    private val scope = AppScopes.create("container", Dispatchers.Default)
    private val clients = HttpClients.create()

    /** API/IPC client. Playback must use [mediaHttp] so the two cannot cancel each other. */
    val http = clients.api
    val mediaHttp = clients.media

    val db = AppDatabase.create(appContext)
    val sessionStore = SecureSessionStore(appContext)
    val settings = AppSettingsStore(appContext)
    val network = AndroidNetworkMonitor(appContext)

    val connection = SessionConnectionManager(
        store = sessionStore,
        authenticator = IpcAuthClient(clients.api),
        channels = WebSocketIpcChannel.Factory(clients.api),
        network = network,
        transport = clients,
        scope = scope,
    )

    val gateway = ProtocolAnyListenGateway(connection)
    val artwork = ArtworkStore(appContext.filesDir.resolve("artwork"), clients.api, { gateway.isOnline() }, { !maintenance.active })
    val offlineAssets = OfflineAssets(appContext.filesDir.resolve("offline"), gateway,
        FileDownloader(clients.api), artwork, { sessionStore.current()?.profile?.baseUrl.orEmpty() })
    val library = LibraryRepository(db.libraryDao(), gateway)
    val recentlyPlayed = RecentlyPlayedRecorder(library)
    val session = SessionRepository(connection)
    val downloadsDir = appContext.filesDir.resolve("downloads").apply { mkdirs() }
    val cacheDir = appContext.cacheDir.resolve("media").apply { mkdirs() }
    val downloads = DownloadCoordinator(
        dao = db.downloadDao(),
        libraryDao = db.libraryDao(),
        gateway = gateway,
        downloader = FileDownloader(clients.api),
        downloadsDir = downloadsDir,
        cacheDir = cacheDir,
        usableBytes = { downloadsDir.usableSpace },
        offlineAssets = offlineAssets,
        extrasWarning = { appContext.getString(R.string.download_extras_incomplete) },
        streamingCacheDir = appContext.cacheDir.resolve("exoplayer"),
        resourceBytes = { artwork.bytes() + offlineAssets.resourceBytes() },
        repairWarning = { appContext.getString(R.string.download_legacy_repair) },
    )

    /** Observable session state for UI and playback; the single source of connection truth. */
    val connectionState get() = connection.state

    private val librarySync = io.github.nutea.anylisten.core.data.repo.LibraryAutoSync(
        scope, { gateway.isOnline() }, { android.os.SystemClock.elapsedRealtime() },
    ) { ids -> library.refresh(ids) }
    private var started = false

    fun start() {
        if (started) return
        started = true
        connection.start()
        // Every newly established socket gets one library refresh, and only one: keying on the
        // generation keeps a link flap from re-fetching the whole library.
        scope.launch {
            connection.state
                .map { (it as? ConnectionState.Online)?.generation ?: 0L }
                .distinctUntilChanged()
                .filter { it != 0L }
                .collect { librarySync.request() }
        }
        scope.launch {
            connection.libraryChanges.collect { if (it > 0) connection.takeLibraryChange()?.let(librarySync::request) }
        }
        if (sessionStore.current() != null) connection.requestConnect()
    }

    fun onForeground() {
        if (sessionStore.current() == null) return
        connection.requestConnect()
        librarySync.onForeground()
    }

    fun isConnected(): Boolean = connection.state.value is ConnectionState.Online

    /** Only refresh resources already retained on this device; never download a whole new library. */
    suspend fun refreshCachedResources(force: Boolean = false) = maintenance.run {
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!gateway.isOnline()) return@withContext
        val limit = kotlinx.coroutines.sync.Semaphore(2)
        val completed = downloads.observe().first().filter { it.status == io.github.nutea.anylisten.core.model.DownloadStatus.COMPLETED }
        val keys = (completed.map { it.cacheKey } + offlineAssets.catalog().map { it.cacheKey }).toSet()
        val snapshot = library.cached()
        val tracks = snapshot.tracksByPlaylist.values.flatten().distinctBy { it.cacheKey }
        val known = tracks.associateBy { it.cacheKey }
        keys.map { key -> launch(kotlinx.coroutines.Dispatchers.IO) {
            limit.withPermit {
                val track = known[key] ?: library.cachedTrack(key) ?: return@withPermit
                val downloaded = downloads.completedFile(key) != null
                if (!downloaded && offlineAssets.catalog().none { it.cacheKey == key }) return@withPermit
                bestEffort { offlineAssets.cacheExtras(track,force) }
                if (downloaded) {
                    val refresh = downloads.refreshCompleted(track,force)
                    try { refresh.join() } finally {
                        if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) refresh.cancel()
                    }
                }
                else if (offlineAssets.audioFile(key) != null) bestEffort { offlineAssets.cacheAudio(track,force,existingOnly = true) }
            }
        } }.joinAll()
        tracks.filter { it.cacheKey !in keys }.map { track -> launch {
            limit.withPermit { bestEffort {
                if (offlineAssets.savedCoverUrl(track.cacheKey) != null) offlineAssets.cacheCover(track, force)
                else track.coverUrl?.takeIf { artwork.cached(it) != null }?.let { artwork.get(it, force) }
            } }
        } }.joinAll()
      }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
}
