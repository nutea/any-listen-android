package io.github.nutea.anylisten.ui

import io.github.nutea.anylisten.core.playback.playbackOrderKeys
import kotlinx.coroutines.flow.debounce
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.nutea.anylisten.AnyListenApp
import io.github.nutea.anylisten.BuildConfig
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.data.session.PersistedPlayback
import io.github.nutea.anylisten.core.data.session.StoredSession
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.DownloadRecord
import io.github.nutea.anylisten.core.model.DownloadStatus
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LocalAssetItem
import io.github.nutea.anylisten.core.model.LocalInventory
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.PlayLater
import io.github.nutea.anylisten.core.model.PlaybackMode
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.TrackSort
import io.github.nutea.anylisten.core.model.TrackSortField
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RepeatMode
import io.github.nutea.anylisten.core.model.ServerProfile
import io.github.nutea.anylisten.core.model.StorageSummary
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.ThemeMode
import io.github.nutea.anylisten.ui.screens.LocalBatchAction
import io.github.nutea.anylisten.core.model.TrackIdentity
import io.github.nutea.anylisten.core.playback.PendingPlayback
import io.github.nutea.anylisten.core.playback.PlaybackReconnect
import io.github.nutea.anylisten.core.playback.PlaybackService
import io.github.nutea.anylisten.core.playback.removeQueuedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class ConnectUiState(
    val url: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val helloOk: Boolean? = null,
)

data class LibraryUiState(
    val snapshot: LibrarySnapshot = LibrarySnapshot(emptyList(), emptyMap(), 0L, offline = false),
    val refreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val selected: Playlist? = null,
    val filtered: List<Track> = emptyList(),
    val pendingAdd: List<Track>? = null,
    val pendingDownload: List<Track>? = null,
    val status: String? = null,
    val sort: TrackSortField = TrackSortField.TITLE,
    val sortAscending: Boolean = true,
)

data class PlayerUiState(
    val track: Track? = null,
    val lyrics: Lyrics? = null,
    val queue: List<Track> = emptyList(),
    val error: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val availableOffline: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffled: Boolean = false,
    val playbackOrder: List<String> = emptyList(),
    val repeat: RepeatMode = RepeatMode.ALL,
    val laterKeys: List<String> = emptyList(),
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container: AppContainer = (application as AnyListenApp).container
    private val _connect = MutableStateFlow(ConnectUiState())
    val connect: StateFlow<ConnectUiState> = _connect.asStateFlow()
    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()
    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()
    private val _downloads = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()
    private val _downloadedAssets = MutableStateFlow<List<LocalAssetItem>>(emptyList())
    val downloadedAssets: StateFlow<List<LocalAssetItem>> = _downloadedAssets.asStateFlow()
    private val _cachedAssets = MutableStateFlow<List<LocalAssetItem>>(emptyList())
    val cachedAssets: StateFlow<List<LocalAssetItem>> = _cachedAssets.asStateFlow()
    private val _storage = MutableStateFlow(StorageSummary(0, 0, 0))
    val storage: StateFlow<StorageSummary> = _storage.asStateFlow()
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    private val _autoCacheAudio = MutableStateFlow(true)
    val autoCacheAudio: StateFlow<Boolean> = _autoCacheAudio.asStateFlow()
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val _playerSheet = MutableStateFlow<String?>(null)
    val playerSheet: StateFlow<String?> = _playerSheet.asStateFlow()
    private val _profile = MutableStateFlow<ServerProfile?>(null)
    val profile: StateFlow<ServerProfile?> = _profile.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var lyricsForKey: String? = null
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var lyricsAttemptAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastPersistAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishController(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _player.update { it.copy(error = applicationContext.getString(R.string.error_track), isBuffering = false, playWhenReady = false) }
        }
    }

    init {
        applyStoredSession(container.sessionStore.current())
        viewModelScope.launch {
            container.library.observeLibrary().collect { snap ->
                _library.update { state ->
                    val selected = state.selected?.let { current -> snap.playlists.find { it.id == current.id } }
                        ?: snap.playlists.firstOrNull()
                    state.copy(
                        snapshot = snap,
                        selected = selected,
                        filtered = filter(snap, selected, state.query, state.sort, state.sortAscending),
                    )
                }
                val freshTracks = snap.tracksByPlaylist.values.flatten().associateBy { it.cacheKey }
                _player.update { state ->
                    state.copy(
                        track = state.track?.let { old -> freshTracks[old.cacheKey]?.let { old.copy(coverUrl = it.coverUrl) } ?: old },
                        queue = state.queue.map { old -> freshTracks[old.cacheKey]?.let { old.copy(coverUrl = it.coverUrl) } ?: old },
                    )
                }
                if (_player.value.queue.isEmpty()) restorePlayback()
            }
        }
        viewModelScope.launch {
            combine(container.downloads.observe(), container.library.observeLibrary()) { records, snap ->
                records to snap
            }.collect { (records, snap) ->
                _downloads.value = records
                rebuildLocalInventory(records, snap)
            }
        }
        viewModelScope.launch {
            container.offlineAssets.updates.debounce(150).collect {
                val candidates = _library.value.snapshot.tracksByPlaylist.values.flatten() + _player.value.queue
                val covers = withContext(Dispatchers.IO) {
                    candidates.distinctBy { it.cacheKey }.associate { it.cacheKey to container.offlineAssets.savedCoverUrl(it.cacheKey) }
                }
                fun updatedCover(track: Track): Track = covers[track.cacheKey]?.let { track.copy(coverUrl = it) } ?: track
                _library.update { state -> state.copy(
                    snapshot = state.snapshot.copy(tracksByPlaylist = state.snapshot.tracksByPlaylist.mapValues { (_, tracks) -> tracks.map(::updatedCover) }),
                    filtered = state.filtered.map(::updatedCover),
                ) }
                val track = _player.value.track
                val lyrics = withContext(Dispatchers.IO) { track?.let { container.offlineAssets.cachedLyrics(it) } }
                _player.update { state -> state.copy(
                    track = state.track?.let(::updatedCover), queue = state.queue.map(::updatedCover),
                    lyrics = if (state.track?.cacheKey == track?.cacheKey) lyrics else state.lyrics,
                ) }
                rebuildLocalInventory(_downloads.value, _library.value.snapshot)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(io.github.nutea.anylisten.core.data.CACHE_CHECK_INTERVAL_MS)
                container.refreshCachedResources()
            }
        }
        viewModelScope.launch { container.settings.themeMode.collect { _themeMode.value = it } }
        viewModelScope.launch { container.settings.autoCacheAudio.collect { _autoCacheAudio.value = it } }
        viewModelScope.launch {
            restoreSession()
            refreshStorage()
            registerNetworkCallback()
            restorePlayback()
        }
        viewModelScope.launch {
            while (isActive) {
                controller?.let { publishController(it) }
                delay(if (controller?.isPlaying == true) 400L else 1_000L)
            }
        }
    }

    fun updateUrl(value: String) = _connect.update { it.copy(url = value, error = null) }
    fun updatePassword(value: String) = _connect.update { it.copy(password = value, error = null) }
    fun updateQuery(value: String) {
        _library.update { state ->
            if (state.query == value) state else state.copy(query = value, filtered = filter(state.snapshot, state.selected, value, state.sort, state.sortAscending))
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        _library.update { state ->
            if (state.selected == playlist) state else state.copy(selected = playlist, filtered = filter(state.snapshot, playlist, state.query, state.sort, state.sortAscending))
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _library.update { state ->
            if (state.selected == playlist && state.query.isEmpty()) state
            else state.copy(selected = playlist, query = "",
                filtered = filter(state.snapshot, playlist, "", state.sort, state.sortAscending))
        }
    }

    fun testHello() {
        viewModelScope.launch {
            _connect.update { it.copy(busy = true, error = null, helloOk = null) }
            runCatching {
                val client = io.github.nutea.anylisten.core.data.gateway.IpcAuthClient(container.http)
                val body = withContext(Dispatchers.IO) { client.readHello(_connect.value.url) }
                client.isHello(body)
            }.onSuccess { ok ->
                _connect.update { it.copy(busy = false, helloOk = ok) }
            }.onFailure { error ->
                _connect.update { it.copy(busy = false, helloOk = false, error = message(error)) }
            }
        }
    }

    fun login() {
        viewModelScope.launch {
            _connect.update { it.copy(busy = true, error = null) }
            runCatching { container.session.login(_connect.value.url, _connect.value.password) }
                .onSuccess {
                    _signedIn.value = true
                    _profile.value = container.sessionStore.current()?.profile
                    _connect.update { it.copy(busy = false, password = "") }
                    bindPlayer()
                    resumeDownloads()
                }.onFailure { error ->
                    _connect.update { it.copy(busy = false, error = message(error)) }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _library.update { it.copy(refreshing = true, error = null) }
            runCatching { container.library.refresh(); viewModelScope.launch { container.refreshCachedResources(); refreshStorage() }; refreshStorage() }
                .onFailure { error -> _library.update { it.copy(error = message(error)) } }
            _library.update { it.copy(refreshing = false) }
        }
    }

    fun play(tracks: List<Track>, start: Track? = tracks.firstOrNull(), startPositionMs: Long = 0L, laterKeys: List<String> = emptyList()) {
        if (tracks.isEmpty()) return
        val current = _player.value
        _player.update {
            it.copy(track = start, queue = tracks, error = null, lyrics = null, positionMs = startPositionMs, laterKeys = laterKeys, isPlaying = false, playWhenReady = true, isBuffering = true)
        }
        lyricsForKey = null
        val pending = PendingPlayback(tracks, start, current.shuffled, current.repeat, startPositionMs, laterKeys)
        PlaybackService.pendingPlay.set(pending)
        val service = PlaybackService.service
        if (service != null) {
            PlaybackService.pendingPlay.set(null)
            service.playTracks(pending.tracks, pending.start, pending.shuffled, pending.repeat, pending.startPositionMs, pending.laterKeys)
        } else {
            applicationContext.startService(Intent(applicationContext, PlaybackService::class.java))
        }
        bindPlayer()
        persistPlayback(force = true)
        start?.let { loadLyrics(it) }
    }

    fun playLast() {
        viewModelScope.launch {
            restorePlayback()
            val state = _player.value
            if (state.queue.isNotEmpty()) {
                play(state.queue, state.track, state.positionMs, state.laterKeys)
                return@launch
            }
            val completed = _downloads.value.firstOrNull { it.status == DownloadStatus.COMPLETED }
            if (completed != null) playDownload(completed)
        }
    }

    fun playDownload(record: DownloadRecord) {
        val known = _library.value.snapshot.tracksByPlaylist.values.flatten()
            .firstOrNull { it.cacheKey == record.cacheKey }
        val track = known ?: Track(
            identity = record.identity,
            title = record.title,
            artist = record.artist,
            album = "",
            durationMs = null,
            fingerprint = record.fingerprint,
        )
        play(listOf(track), track)
    }

    fun playLocal(item: LocalAssetItem) {
        if (!item.completeness.audioReady) return
        play(listOf(item.track), item.track)
    }

    fun acknowledgeStatus() {
        _library.update { it.copy(status = null) }
    }

    fun togglePlayPause() {
        val c = controller
        val state = _player.value
        if (c == null || c.mediaItemCount == 0) {
            if (state.queue.isNotEmpty()) {
                play(state.queue, state.track, state.positionMs, state.laterKeys)
            } else {
                playLast()
            }
            return
        }
        if (c.playWhenReady && c.playerError == null && c.playbackState != Player.STATE_ENDED) c.pause()
        else if (c.playerError != null || c.playbackState == Player.STATE_IDLE) {
            PlaybackService.service?.recoverConnection(playRequested = true)
        } else c.play()
    }

    fun skipNext() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            togglePlayPause()
            return
        }
        c.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            togglePlayPause()
            return
        }
        c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        if (controller?.mediaItemCount?.let { it > 0 } == true) controller?.seekTo(position)
        else {
            _player.update { it.copy(positionMs = position) }
            persistPlayback(force = true)
        }
    }

    fun setSort(field: TrackSortField) {
        _library.update { state ->
            val ascending = if (state.sort == field) !state.sortAscending else true
            state.copy(
                sort = field,
                sortAscending = ascending,
                filtered = filter(state.snapshot, state.selected, state.query, field, ascending),
            )
        }
    }

    fun playLater(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val state = _player.value
        val result = PlayLater.insert(state.queue, state.track?.cacheKey, state.laterKeys, tracks)
        _player.update { it.copy(queue = result.queue, laterKeys = result.laterKeys) }
        PlaybackService.pendingPlay.updateAndGet { pending ->
            pending?.copy(tracks = result.queue, laterKeys = result.laterKeys)
        }
        PlaybackService.service?.applyQueuePreservingCurrent(result.queue, result.laterKeys)
        persistPlayback(force = true)
        _library.update { it.copy(status = applicationContext.getString(R.string.play_later_added, tracks.size)) }
    }

    fun cyclePlayMode() {
        applyPlayMode(PlaybackMode.from(_player.value.repeat, _player.value.shuffled).next())
    }

    fun setPlayMode(mode: PlaybackMode) {
        applyPlayMode(mode)
        persistPlayback(force = true)
    }

    fun removeQueueItem(track: Track) {
        val state = _player.value
        val index = state.queue.indexOfFirst { it.cacheKey == track.cacheKey }
        if (index < 0) return
        val remaining = state.queue.toMutableList().apply { removeAt(index) }
        val removingCurrent = state.track?.cacheKey == track.cacheKey
        val next = if (removingCurrent) remaining.getOrNull(index.coerceAtMost(remaining.lastIndex)) else state.track
        _player.update { it.copy(queue = remaining, track = next, lyrics = if (removingCurrent) null else it.lyrics,
            positionMs = if (removingCurrent) 0 else it.positionMs,
            durationMs = if (removingCurrent) next?.durationMs ?: 0L else it.durationMs,
            laterKeys = it.laterKeys.filter { key -> key != track.cacheKey }) }
        PlaybackService.pendingPlay.updateAndGet { pending ->
            pending?.let { request ->
                val kept = request.tracks.filterNot { it.cacheKey == track.cacheKey }
                if (kept.isEmpty()) null else request.copy(tracks = kept, start = next, startPositionMs = if (removingCurrent) 0 else request.startPositionMs)
            }
        }
        val c = controller
        if (PlaybackService.service?.removeQueueTrack(track.cacheKey) != true) c?.removeQueuedTrack(track.cacheKey)
        if (remaining.isEmpty()) {
            c?.stop()
            c?.clearMediaItems()
            _player.value = PlayerUiState(shuffled = state.shuffled, repeat = state.repeat)
            viewModelScope.launch { container.settings.setPlayback(PersistedPlayback()) }
        } else {
            persistPlayback(force = true)
        }
    }

    private fun applyPlayMode(mode: PlaybackMode) {
        PlaybackService.pendingPlay.updateAndGet { it?.copy(shuffled = mode.shuffled, repeat = mode.repeat) }
        PlaybackService.service?.setPlaybackMode(mode)
        controller?.shuffleModeEnabled = mode.shuffled
        controller?.repeatMode = when (mode.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _player.update { it.copy(shuffled = mode.shuffled, repeat = mode.repeat) }
    }

    fun playQueueItem(track: Track) {
        val index = _player.value.queue.indexOfFirst { it.cacheKey == track.cacheKey }
        val c = controller
        if (c != null && index >= 0 && index < c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        } else {
            play(_player.value.queue.ifEmpty { listOf(track) }, track)
        }
    }

    fun requestDownload(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (tracks.size >= DOWNLOAD_CONFIRM_COUNT) {
            _library.update { it.copy(pendingDownload = tracks, error = null) }
        } else {
            download(tracks)
        }
    }

    fun confirmPendingDownload() {
        val tracks = _library.value.pendingDownload ?: return
        _library.update { it.copy(pendingDownload = null) }
        download(tracks)
    }

    fun cancelPendingDownload() {
        _library.update { it.copy(pendingDownload = null) }
    }

    fun download(tracks: List<Track>) {
        viewModelScope.launch {
            runCatching { container.downloads.enqueue(tracks) }
                .onSuccess {
                    _library.update {
                        it.copy(error = null, status = applicationContext.getString(R.string.download_queued, tracks.size))
                    }
                }
                .onFailure { error -> _library.update { it.copy(error = message(error), status = null) } }
            refreshStorage()
        }
    }

    fun openQueueSheet() { _playerSheet.value = "queue" }
    fun consumePlayerSheet() { _playerSheet.value = null }

    fun isAvailableOffline(track: Track): Boolean =
        container.offlineAssets.audioFile(track.cacheKey) != null ||
            _downloads.value.any { it.cacheKey == track.cacheKey && it.status == DownloadStatus.COMPLETED && it.filePath?.let(::File)?.isFile == true }

    fun isFavorite(track: Track): Boolean {
        val love = _library.value.snapshot.tracksByPlaylist[ProtocolConstants.LIST_LOVE].orEmpty()
        return love.any { it.identity.remoteTrackId == track.identity.remoteTrackId }
    }

    fun toggleFavorite(track: Track) {
        if (isFavorite(track)) {
            removeFromPlaylist(ProtocolConstants.LIST_LOVE, track)
        } else {
            favorite(track)
        }
    }

    fun favorite(track: Track) {
        viewModelScope.launch {
            runCatching { container.library.addToPlaylist(ProtocolConstants.LIST_LOVE, track) }
                .onSuccess {
                    _library.update { it.copy(error = null, status = applicationContext.getString(R.string.added_to_favorites)) }
                }
                .onFailure { error -> _library.update { it.copy(error = message(error), status = null) } }
        }
    }

    fun removeFromSelected(track: Track) = removeFromSelected(listOf(track))

    fun removeFromSelected(tracks: List<Track>) {
        val playlist = _library.value.selected ?: return
        if (!playlist.canMutateOnline || tracks.isEmpty()) return
        if (tracks.size == 1) {
            removeFromPlaylist(playlist.id, tracks.first())
            return
        }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            var lastError: Throwable? = null
            tracks.forEach { track ->
                runCatching { container.library.removeFromPlaylist(playlist.id, track) }
                    .onSuccess { ok++ }
                    .onFailure { error -> fail++; lastError = error }
            }
            _library.update {
                it.copy(
                    error = if (ok == 0) lastError?.let(::message) else null,
                    status = applicationContext.getString(R.string.batch_result, ok, fail),
                )
            }
        }
    }

    fun startAddToPlaylist(track: Track) = startAddToPlaylist(listOf(track))

    fun startAddToPlaylist(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _library.update { it.copy(pendingAdd = tracks, error = null, status = null) }
    }

    fun cancelAddToPlaylist() {
        _library.update { it.copy(pendingAdd = null) }
    }

    fun confirmAddToPlaylist(playlist: Playlist) {
        val tracks = _library.value.pendingAdd ?: return
        _library.update { it.copy(pendingAdd = null) }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            var lastError: Throwable? = null
            tracks.forEach { track ->
                runCatching { container.library.addToPlaylist(playlist.id, track) }
                    .onSuccess { ok++ }
                    .onFailure { error -> fail++; lastError = error }
            }
            _library.update {
                it.copy(
                    error = if (ok == 0) lastError?.let(::message) else null,
                    status = if (tracks.size == 1 && fail == 0) {
                        applicationContext.getString(R.string.added_to_playlist, playlistLabel(playlist))
                    } else applicationContext.getString(R.string.batch_result, ok, fail),
                )
            }
        }
    }

    fun addTargets(): List<Playlist> = _library.value.snapshot.playlists.filter { it.canMutateOnline }

    fun playlistLabel(playlist: Playlist): String {
        val ctx = applicationContext
        return when (playlist.id) {
            ProtocolConstants.LIST_DEFAULT -> ctx.getString(R.string.list_default)
            ProtocolConstants.LIST_LOVE -> ctx.getString(R.string.list_love)
            ProtocolConstants.LIST_LAST_PLAYED -> ctx.getString(R.string.list_last_played)
            else -> playlist.name.ifBlank { playlist.id }
        }
    }

    fun batchLocal(tab: Int, keys: List<String>, action: LocalBatchAction) = viewModelScope.launch {
        val keySet = keys.toSet()
        val assets = (if (tab == 0) _downloadedAssets.value else _cachedAssets.value).filter { it.cacheKey in keySet }
        val tracks = assets.filter { it.completeness.audioReady }.map { it.track }
        when (action) {
            LocalBatchAction.PLAY -> if (tracks.isNotEmpty()) play(tracks)
            LocalBatchAction.LATER -> if (tracks.isNotEmpty()) playLater(tracks)
            LocalBatchAction.DELETE -> assets.forEach { if (tab == 0) container.downloads.deleteLocal(it.cacheKey) else container.offlineAssets.clearTrack(it.cacheKey) }
            LocalBatchAction.RETRY -> _downloads.value.filter { it.cacheKey in keySet && it.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED) }.forEach { container.downloads.retry(it.cacheKey) }
            LocalBatchAction.CANCEL -> _downloads.value.filter { it.cacheKey in keySet && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }.forEach { container.downloads.cancel(it.cacheKey) }
            LocalBatchAction.REMOVE -> keys.forEach { container.downloads.removeTask(it) }
        }
        refreshStorage()
    }

    fun retryDownload(cacheKey: String) = viewModelScope.launch { container.downloads.retry(cacheKey) }
    fun removeDownloadTask(cacheKey: String) = viewModelScope.launch {
        container.downloads.removeTask(cacheKey)
        refreshStorage()
    }

    fun cancelDownload(cacheKey: String) = viewModelScope.launch { container.downloads.cancel(cacheKey) }
    fun deleteDownload(cacheKey: String) = viewModelScope.launch {
        container.downloads.deleteLocal(cacheKey)
        refreshStorage()
        rebuildLocalInventory(_downloads.value, _library.value.snapshot)
    }

    fun deleteCache(cacheKey: String) = viewModelScope.launch {
        container.offlineAssets.clearTrack(cacheKey)
        refreshStorage()
        rebuildLocalInventory(_downloads.value, _library.value.snapshot)
    }

    fun resumeDownloads() = viewModelScope.launch { container.downloads.resumePaused(); container.refreshCachedResources() }

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch { container.settings.setThemeMode(value) }

    fun setAutoCacheAudio(value: Boolean) = viewModelScope.launch {
        container.settings.setAutoCacheAudio(value)
        container.offlineAssets.setAutomaticAudioCaching(value)
    }

    fun logout() {
        viewModelScope.launch {
            container.session.logout()
            _signedIn.value = false
            _profile.value = null
            releasePlayer()
        }
    }

    fun lastRefreshLabel(): String {
        val ts = _library.value.snapshot.refreshedAtEpochMs
        if (ts <= 0L) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
    }

    fun artworkUrl(track: Track?): String? {
        if (track != null && track.coverUrl.isNullOrBlank()) container.offlineAssets.requestCover(track)
        val raw = track?.let { container.offlineAssets.peekCoverUrl(it.cacheKey) } ?: track?.coverUrl?.takeIf { it.isNotBlank() } ?: return null
        return resolveArtworkUrl(raw)
    }

    fun playlistArtworkUrl(playlist: Playlist): String? = playlist.coverUrl?.takeIf { it.isNotBlank() }?.let(::resolveArtworkUrl)

    private fun resolveArtworkUrl(raw: String): String? {
        if (raw.startsWith("https://") || raw.startsWith("http://") || raw.startsWith("file:")) return raw
        val base = _profile.value?.baseUrl ?: container.sessionStore.current()?.profile?.baseUrl ?: return null
        return runCatching { UrlNormalizer.resolveArtwork(base, raw) }.getOrNull()
    }

    fun currentLyricIndex(): Int {
        val lines = _player.value.lyrics?.lines.orEmpty()
        if (lines.isEmpty()) return -1
        val pos = _player.value.positionMs
        return lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    }

    fun clearPlaybackCache() {
        viewModelScope.launch(Dispatchers.IO) {
            container.offlineAssets.clearAudio()
            container.cacheDir.deleteRecursively()
            container.cacheDir.mkdirs()
            File(applicationContext.cacheDir, "exoplayer").deleteRecursively()
            refreshStorage()
            rebuildLocalInventory(_downloads.value, _library.value.snapshot)
            _library.update { it.copy(status = applicationContext.getString(R.string.cache_cleared)) }
        }
    }

    fun shareDiagnostics(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(applicationContext.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "anylisten-diagnostics.txt")
            file.writeText(diagnosticsText())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            withContext(Dispatchers.Main) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Any Listen diagnostics")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("diagnostics", uri)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_export_diagnostics)))
            }
        }
    }

    private fun applyStoredSession(stored: StoredSession?) {
        if (stored == null) return
        _signedIn.value = true
        _profile.value = stored.profile
        _connect.update { it.copy(url = stored.profile.baseUrl) }
    }

    private suspend fun restoreSession() {
        val stored = container.sessionStore.current()
        applyStoredSession(stored)
        runCatching { container.session.restore() }
            .onSuccess { info ->
                val current = container.sessionStore.current()
                _signedIn.value = info != null || current != null
                _profile.value = current?.profile ?: info?.profile ?: stored?.profile
                if (_signedIn.value) {
                    bindPlayer()
                    resumeDownloads()
                }
            }
            .onFailure { error ->
                val current = container.sessionStore.current()
                val kind = (error as? AppError)?.kind
                val passwordRejected = kind == ErrorKind.AUTH_FAILED && current == null
                val needsPassword = kind == ErrorKind.AUTH_FAILED && current?.password.isNullOrBlank()
                if (passwordRejected || needsPassword) {
                    _signedIn.value = false
                    _profile.value = null
                    _connect.update {
                        it.copy(error = message(error), url = stored?.profile?.baseUrl.orEmpty())
                    }
                } else {
                    applyStoredSession(current ?: stored)
                    if (_signedIn.value) bindPlayer()
                }
            }
    }

    private fun bindPlayer() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                val bound = future.get()
                controller = bound
                bound.addListener(playerListener)
                publishController(bound)
            }
        }, ContextCompat.getMainExecutor(applicationContext))
    }

    private fun releasePlayer() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private fun publishController(player: Player) {
        // A newly bound service has no timeline; keep the restored queue and position.
        if (player.mediaItemCount == 0) return
        val mediaId = player.currentMediaItem?.mediaId
        val queued = _player.value.queue.firstOrNull { it.cacheKey == mediaId }
        val meta = player.currentMediaItem?.mediaMetadata
        val track = PlaybackService.service?.trackForKey(mediaId) ?: queued
            ?: _library.value.snapshot.tracksByPlaylist.values.asSequence().flatten().firstOrNull { it.cacheKey == mediaId }
            ?: _player.value.track?.takeIf { it.cacheKey == mediaId } ?: meta?.let { metadata ->
            if (mediaId.isNullOrBlank() && metadata.title.isNullOrBlank()) return@let null
            Track(
                identity = TrackIdentity("session", mediaId ?: metadata.title?.toString().orEmpty()),
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                album = metadata.albumTitle?.toString().orEmpty(),
                durationMs = player.duration.takeIf { it > 0 },
                coverUrl = metadata.artworkUri?.toString(),
            )
        }
        val duration = player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L
        val repeat = when (PlaybackService.service?.logicalRepeatMode() ?: player.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            else -> RepeatMode.ALL
        }
        _player.update {
            it.copy(
                track = track ?: it.track,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && player.playerError == null,
                error = if (player.playerError == null) null else it.error,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                availableOffline = track?.let { isAvailableOffline(it) } == true,
                shuffled = player.shuffleModeEnabled,
                playbackOrder = PlaybackService.service?.playbackOrderKeys()
                    ?: player.playbackOrderKeys(),
                repeat = repeat,
                laterKeys = it.laterKeys.filter { key -> key != track?.cacheKey },
            )
        }
        track?.let { loadLyrics(it) }
        persistPlayback()
    }

    private fun persistPlayback(force: Boolean = false) {
        // The service owns persistence while its timeline is active, including background playback.
        if (PlaybackService.service?.hasPlaybackQueue() == true) return
        val state = _player.value
        if (state.queue.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 1_500L) return
        lastPersistAt = now
        viewModelScope.launch {
            container.settings.setPlayback(
                PersistedPlayback(
                    cacheKeys = state.queue.map { it.cacheKey },
                    currentKey = state.track?.cacheKey,
                    shuffled = state.shuffled,
                    repeat = state.repeat.name,
                    positionMs = state.positionMs,
                    laterKeys = state.laterKeys,
                ),
            )
        }
    }

    private suspend fun restorePlayback() {
        if (_player.value.queue.isNotEmpty()) return
        val saved = container.settings.playback.first()
        if (saved.cacheKeys.isEmpty()) return
        val library = _library.value.snapshot.tracksByPlaylist.values.flatten().ifEmpty {
            runCatching { container.library.cached() }.getOrNull()
                ?.tracksByPlaylist?.values?.flatten()
                .orEmpty()
        }
        val fromLib = library.associateBy { it.cacheKey }
        val downloads = _downloads.value.ifEmpty {
            runCatching { container.downloads.observe().first() }.getOrDefault(emptyList())
        }
        val fromDl = downloads.associate { it.cacheKey to it.toTrack() }
        val tracks = saved.cacheKeys.mapNotNull { fromLib[it] ?: fromDl[it] }
        if (tracks.isEmpty() || _player.value.queue.isNotEmpty() || PlaybackService.service?.hasPlaybackQueue() == true) return
        val restoredTrack = tracks.firstOrNull { it.cacheKey == saved.currentKey } ?: tracks.first()
        _player.update {
            it.copy(
                queue = tracks,
                track = restoredTrack,
                durationMs = restoredTrack.durationMs ?: 0L,
                availableOffline = isAvailableOffline(restoredTrack),
                shuffled = saved.shuffled,
                repeat = saved.repeatMode(),
                positionMs = saved.positionMs,
                isPlaying = false, playWhenReady = false, isBuffering = false,
                laterKeys = saved.laterKeys,
            )
        }
        loadLyrics(restoredTrack)
    }

    private fun DownloadRecord.toTrack(): Track = Track(
        identity = identity,
        title = title,
        artist = artist,
        album = "",
        durationMs = null,
        fingerprint = fingerprint,
    )

    private fun loadLyrics(track: Track) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lyricsForKey == track.cacheKey && (lyricsJob?.isActive == true ||
            _player.value.lyrics?.lines?.isNotEmpty() == true || now - lyricsAttemptAt < 30_000L)) return
        lyricsJob?.cancel()
        if (lyricsForKey != track.cacheKey) _player.update { it.copy(lyrics = null) }
        lyricsForKey = track.cacheKey
        lyricsAttemptAt = now
        lyricsJob = viewModelScope.launch {
            // Restored/download-only queue metadata may omit isLocal, filePath and deviceId.
            val fullTrack = container.library.cachedTrack(track.cacheKey) ?: track
            val lyrics = try { container.offlineAssets.lyrics(fullTrack) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (lyricsForKey == track.cacheKey) _player.update { it.copy(lyrics = lyrics) }
        }
    }

    private fun removeFromPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            runCatching { container.library.removeFromPlaylist(playlistId, track) }
                .onSuccess {
                    val name = _library.value.snapshot.playlists.find { it.id == playlistId }
                        ?.let { playlistLabel(it) } ?: playlistId
                    _library.update {
                        it.copy(error = null, status = applicationContext.getString(R.string.removed_from_playlist, name))
                    }
                }
                .onFailure { error -> _library.update { it.copy(error = message(error), status = null) } }
        }
    }

    private fun diagnosticsText(): String {
        val snap = _library.value.snapshot
        val downloads = _downloads.value
        val profile = _profile.value
        return buildString {
            appendLine("Any Listen diagnostics")
            appendLine("client=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("compatible_server=${ProtocolConstants.TARGET_SERVER_VERSION}")
            appendLine("server_name=${profile?.serverName.orEmpty()}")
            appendLine("server_version=${profile?.reportedVersion.orEmpty()}")
            appendLine("server_host=${runCatching { Uri.parse(profile?.baseUrl.orEmpty()).host }.getOrNull().orEmpty()}")
            appendLine("signed_in=${_signedIn.value}")
            appendLine("offline=${snap.offline}")
            appendLine("playlists=${snap.playlists.size}")
            appendLine("tracks=${snap.tracksByPlaylist.values.sumOf { it.size }}")
            appendLine("last_refresh=${lastRefreshLabel()}")
            appendLine("downloads_total=${downloads.size}")
            downloads.groupingBy { it.status.name }.eachCount().forEach { (status, count) ->
                appendLine("downloads_$status=$count")
            }
            appendLine("download_bytes=${_storage.value.downloadBytes}")
            appendLine("cache_bytes=${_storage.value.cacheBytes}")
            appendLine("usable_bytes=${_storage.value.usableBytes}")
        }
    }

    private fun registerNetworkCallback() {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var availableNetwork: Network? = null
            private var observedDefaultNetwork = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable) {
                    if (availableNetwork != network) {
                        val networkChanged = observedDefaultNetwork
                        availableNetwork = network
                        observedDefaultNetwork = true
                        if (PlaybackReconnect.shouldRebuildSession(container.gateway.isOnline(), networkChanged)) {
                            onNetworkUsable()
                        } else {
                            resumeDownloads()
                        }
                    }
                } else if (availableNetwork == network) availableNetwork = null
            }
            override fun onLost(network: Network) {
                if (availableNetwork == network) {
                    availableNetwork = null
                    container.dropStaleConnections()
                    container.cancelInFlightCalls()
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun onNetworkUsable() {
        viewModelScope.launch {
            container.dropStaleConnections()
            runCatching { withContext(Dispatchers.IO) { container.session.restore() } }
            PlaybackService.service?.recoverConnection()
            resumeDownloads()
        }
    }

    suspend fun refreshStorage() {
        _storage.value = container.downloads.storage()
        rebuildLocalInventory(_downloads.value, _library.value.snapshot)
    }

    fun refreshLocal() {
        viewModelScope.launch { rebuildLocalInventory(_downloads.value, _library.value.snapshot) }
    }

    private suspend fun rebuildLocalInventory(records: List<DownloadRecord>, snap: LibrarySnapshot) {
        val libraryTracks = snap.tracksByPlaylist.values.flatten().associateBy { it.cacheKey }
        val completedKeys = records.filter { it.status == DownloadStatus.COMPLETED }.map { it.cacheKey }.toSet()
        val inventory = withContext(Dispatchers.IO) {
            val catalog = container.offlineAssets.catalog()
            LocalInventory.downloaded(
                records = records,
                inspect = { container.offlineAssets.inspect(it) },
                fileReady = { record -> record.filePath?.let(::File)?.isFile == true },
                libraryTracks = libraryTracks,
            ) to LocalInventory.cached(
                catalog = catalog,
                inspect = { container.offlineAssets.inspect(it) },
                completedDownloadKeys = completedKeys,
                libraryTracks = libraryTracks,
                audioBytes = { container.offlineAssets.audioFile(it)?.length() ?: 0L },
            )
        }
        _downloadedAssets.value = inventory.first
        _cachedAssets.value = inventory.second
    }

    private fun filter(
        snapshot: LibrarySnapshot,
        playlist: Playlist?,
        query: String,
        sort: TrackSortField,
        ascending: Boolean,
    ): List<Track> {
        val tracks = snapshot.tracksByPlaylist[playlist?.id].orEmpty()
        val q = query.trim().lowercase()
        val searched = if (q.isEmpty()) tracks else tracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        return TrackSort.apply(searched, sort, ascending)
    }

    private fun message(error: Throwable): String {
        val app = error as? AppError ?: AppError.fromThrowable(error)
        val ctx = applicationContext
        return when (app.kind) {
            ErrorKind.AUTH_FAILED -> ctx.getString(R.string.error_auth)
            ErrorKind.SESSION_EXPIRED -> ctx.getString(R.string.error_session)
            ErrorKind.RATE_LIMITED -> ctx.getString(R.string.error_rate_limited)
            ErrorKind.CERT_INVALID -> ctx.getString(R.string.error_cert)
            ErrorKind.NETWORK_UNREACHABLE -> ctx.getString(R.string.error_network)
            ErrorKind.TRACK_UNAVAILABLE -> ctx.getString(R.string.error_track)
            ErrorKind.WRITE_UNCONFIRMED -> ctx.getString(R.string.error_write)
            ErrorKind.OFFLINE_MUTATION -> ctx.getString(R.string.error_offline_write)
            ErrorKind.DISK_FULL -> ctx.getString(R.string.error_disk)
            else -> app.message.ifBlank { ctx.getString(R.string.error_generic) }
        }
    }

    override fun onCleared() {
        networkCallback?.let { callback ->
            val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        releasePlayer()
        super.onCleared()
    }

    private val applicationContext: Context
        get() = getApplication<Application>().applicationContext

    companion object {
        const val DOWNLOAD_CONFIRM_COUNT = 20
    }
}
