package io.github.nutea.anylisten.core.data

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
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

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val maintenance = PlaybackMaintenance()
    fun setPlaybackActive(active: Boolean) { maintenance.setActive(active) }
    val http = NetworkFactory.client()
    fun dropStaleConnections() = http.dropStaleConnections()
    fun cancelInFlightCalls() = http.cancelInFlightCalls()
    val db = AppDatabase.create(appContext)
    val sessionStore = SecureSessionStore(appContext)
    val settings = AppSettingsStore(appContext)
    val gateway = ProtocolAnyListenGateway(http, IpcAuthClient(http))
    val artwork = ArtworkStore(appContext.filesDir.resolve("artwork"), http, { gateway.isOnline() }, { !maintenance.active })
    val offlineAssets = OfflineAssets(appContext.filesDir.resolve("offline"), gateway,
        FileDownloader(http), artwork, { sessionStore.current()?.profile?.baseUrl.orEmpty() })
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
        usableBytes = { downloadsDir.usableSpace },
        offlineAssets = offlineAssets,
        extrasWarning = { appContext.getString(R.string.download_extras_incomplete) },
        streamingCacheDir = appContext.cacheDir.resolve("exoplayer"),
        resourceBytes = { artwork.bytes() + offlineAssets.resourceBytes() },
        repairWarning = { appContext.getString(R.string.download_legacy_repair) },
    )

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
