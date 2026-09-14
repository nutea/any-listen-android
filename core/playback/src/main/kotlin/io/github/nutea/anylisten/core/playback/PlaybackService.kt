package io.github.nutea.anylisten.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.core.model.PlaybackMode
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RepeatMode
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class PendingPlayback(
    val tracks: List<Track>,
    val start: Track? = null,
    val shuffled: Boolean = false,
    val repeat: RepeatMode = RepeatMode.ALL,
    val startPositionMs: Long = 0L,
    val laterKeys: List<String> = emptyList(),
)

class PlaybackService : MediaSessionService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    @Volatile private var automaticAudioCaching = true
    private var playJob: Job? = null
    private var reconnectJob: Job? = null
    private var bufferingWatch: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeAt = 0L
    private val tracksByKey = ConcurrentHashMap<String, Track>()
    private val laterKeys = linkedSetOf<String>()
    private var priority: PriorityQueue? = null
    private val favoriteCommand = SessionCommand(COMMAND_FAVORITE, Bundle.EMPTY)
    private val playModeCommand = SessionCommand(COMMAND_PLAY_MODE, Bundle.EMPTY)
    private var favoriteLiked = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as ContainerHolder).container
        scope.launch {
            container.settings.autoCacheAudio.collect { enabled ->
                automaticAudioCaching = enabled
                container.offlineAssets.setAutomaticAudioCaching(enabled)
            }
        }
        val cacheDir = File(cacheDir, "exoplayer").apply { mkdirs() }
        val simpleCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        )
        cache = simpleCache
        // A streaming response may remain open for the duration of the song.
        // Keep connect/read timeouts, but do not apply the API client's whole-call deadline.
        val playbackHttp = container.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val okHttpFactory = OkHttpDataSource.Factory(playbackHttp)
        val upstream = DefaultDataSource.Factory(this, okHttpFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val readOnlyCacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val playbackDataSource = androidx.media3.datasource.DataSource.Factory {
            val token = container.offlineAssets.streamToken()
            StreamingCacheDataSource(
                if (automaticAudioCaching) cacheFactory.createDataSource() else readOnlyCacheFactory.createDataSource(),
                File(cacheDir, "recording"), { automaticAudioCaching },
            ) { spec, file ->
                val track = spec.customData as? Track
                if (track == null) file.delete() else {
                    val save = scope.launch(Dispatchers.IO) {
                        container.offlineAssets.adoptStream(track, spec.uri.toString(), file, token)
                    }
                    save.invokeOnCompletion { file.delete() }
                }
            }
        }
        val resolvingFactory = ResolvingDataSource.Factory(playbackDataSource) { spec ->
            val key = PlaybackUris.cacheKey(spec.uri) ?: return@Factory spec
            val track = tracksByKey[key] ?: throw IOException("Unknown track")
            val resolver = Holder.resolver ?: throw IOException("No resolver")
            val resource = try {
                runBlocking { resolver.resolve(track) }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException(error.message ?: "Media resolve failed", error)
            }
            spec.buildUpon().setUri(android.net.Uri.parse(resource.url)).setKey(resolver.cacheKey(track,resource)).setCustomData(track).build()
        }
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(streamingLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        priority = PriorityQueue(exo, laterKeys)
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode in 2000..2999) recoverConnection()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    watchBuffering(playbackState)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    container.setPlaybackActive(player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE)
                    savePlayback()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.mediaId?.let { laterKeys.remove(it) }
                    refreshSessionButtons()
                    cacheCurrentArtwork()
                    cacheCurrentTrack()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    refreshSessionButtons()
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    refreshSessionButtons()
                }
            },
        )
        player = exo
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val buttons = sessionButtons(liked = false)
        val sessionBuilder = MediaSession.Builder(this, object : ForwardingPlayer(exo) {
            override fun getRepeatMode(): Int = logicalRepeatMode()
            override fun setRepeatMode(repeatMode: Int) { priority?.setRepeatMode(repeatMode) }
        })
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(buttons)
            .setCustomLayout(buttons)
        if (launch != null) {
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = sessionBuilder.build()
        Holder.service = this
        Holder.resolver = PlaybackResolver(container.downloads, container.gateway, container.offlineAssets)
        watchNetwork()
        consumePending()
        scope.launch {
            while (isActive) {
                savePlayback()
                delay(1_000L)
            }
        }
        scope.launch {
            container.library.observeLibrary().collect { refreshSessionButtons() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        consumePending()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        val finalState = snapshot()
        if (finalState != null) runBlocking {
            (application as ContainerHolder).container.settings.setPlayback(finalState)
        }
        (application as ContainerHolder).container.setPlaybackActive(false)
        Holder.service = null
        session?.release()
        player?.release()
        cache?.release()
        job.cancel()
        super.onDestroy()
    }

    fun playTracks(
        tracks: List<Track>,
        start: Track? = null,
        shuffled: Boolean = false,
        repeat: RepeatMode = RepeatMode.ALL,
        startPositionMs: Long = 0L,
        restoredLaterKeys: List<String> = emptyList(),
    ) {
        val exo = player ?: return
        if (Holder.resolver == null) return
        playJob?.cancel()
        playJob = scope.launch {
            tracksByKey.clear()
            laterKeys.clear()
            tracks.forEach { tracksByKey[it.cacheKey] = it }
            if (tracks.isEmpty()) return@launch
            val startIndex = tracks.indexOfFirst { it.identity == start?.identity }.coerceAtLeast(0)
            // ResolvingDataSource resolves on the loading thread. Populate the timeline
            // immediately so queue controls also work while the first track is buffering.
            val items = tracks.map { track ->
                MediaItem.Builder()
                    .setMediaId(track.cacheKey)
                    .setUri(PlaybackUris.forTrack(track.cacheKey))
                    .setMediaMetadata(baseMetadata(track))
                    .build()
            }
            (application as ContainerHolder).container.setPlaybackActive(true)
            exo.setMediaItems(items, startIndex, startPositionMs.coerceAtLeast(0L))
            exo.shuffleModeEnabled = shuffled
            priority?.setRepeatMode(when (repeat) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            })
            priority?.restore(restoredLaterKeys)
            exo.prepare()
            exo.play()
            refreshSessionButtons()
        }
    }

    fun applyQueuePreservingCurrent(tracks: List<Track>, later: List<String>) {
        val exo = player ?: return
        tracks.forEach { tracksByKey[it.cacheKey] = it }
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.cacheKey)
                .setUri(PlaybackUris.forTrack(track.cacheKey))
                .setMediaMetadata(baseMetadata(track))
                .build()
        }
        val wasEmpty = exo.mediaItemCount == 0
        priority?.apply(items, later)
        if (wasEmpty && items.isNotEmpty()) {
            exo.prepare()
            exo.playWhenReady = false
        }
        savePlayback()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var usable: Network? = null
            private var observedDefaultNetwork = false
            override fun onLost(network: Network) {
                if (usable != network) return
                usable = null
                lastNetworkChangeAt = SystemClock.elapsedRealtime()
                val container = (application as ContainerHolder).container
                container.dropStaleConnections()
                container.cancelInFlightCalls()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (usable == network) {
                        usable = null
                        lastNetworkChangeAt = SystemClock.elapsedRealtime()
                    }
                    return
                }
                if (usable == network) return
                val networkChanged = observedDefaultNetwork
                usable = network
                observedDefaultNetwork = true
                lastNetworkChangeAt = SystemClock.elapsedRealtime()
                val container = (application as ContainerHolder).container
                if (PlaybackReconnect.shouldRebuildSession(container.gateway.isOnline(), networkChanged)) {
                    scope.launch { recoverConnection() }
                }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun watchBuffering(playbackState: Int) {
        val exo = player ?: return
        if (playbackState == Player.STATE_BUFFERING && exo.playWhenReady && exo.playerError == null) {
            if (bufferingWatch?.isActive == true) return
            bufferingWatch = scope.launch {
                delay(PlaybackReconnect.BUFFERING_STALL_MS)
                val current = player ?: return@launch
                val container = (application as ContainerHolder).container
                if (PlaybackReconnect.shouldRecoverStalledBuffer(
                        stillBuffering = current.playbackState == Player.STATE_BUFFERING,
                        playWhenReady = current.playWhenReady,
                        gatewayOnline = container.gateway.isOnline(),
                        msSinceNetworkChange = if (lastNetworkChangeAt == 0L) Long.MAX_VALUE
                            else SystemClock.elapsedRealtime() - lastNetworkChangeAt,
                    )
                ) {
                    recoverConnection()
                }
            }
        } else {
            bufferingWatch?.cancel()
            bufferingWatch = null
        }
    }

    /** Rebuild the IPC session and expired URLs before preparing the failed timeline. */
    fun recoverConnection(playRequested: Boolean = false) {
        val exo = player ?: return
        if (playRequested) exo.playWhenReady = true
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val container = (application as ContainerHolder).container
            container.dropStaleConnections()
            val current = currentTrack()
            if (current != null && (container.downloads.completedFile(current.cacheKey) != null ||
                    container.offlineAssets.audioFile(current.cacheKey) != null)) {
                if (PlaybackReconnect.shouldReplayTimeline(exo.playerError != null, exo.playbackState, exo.playWhenReady)) {
                    exo.prepare()
                }
                // Local playback can recover immediately; still restore the network session below.
            }
            var backoff = 1_000L
            while (isActive && container.sessionStore.current() != null) {
                if (!hasUsableNetwork()) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    continue
                }
                delay(500L)
                val restored = try {
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(30_000L) { container.session.restore() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (restored != null) {
                    container.http.connectionPool.evictAll()
                    Holder.resolver?.invalidateRemoteUrls()
                    cacheCurrentTrack()
                    if (exo.mediaItemCount > 0 &&
                        PlaybackReconnect.shouldReplayTimeline(exo.playerError != null, exo.playbackState, exo.playWhenReady)
                    ) {
                        // prepare preserves position and playWhenReady, including a user's pause.
                        exo.prepare()
                    }
                    return@launch
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun playbackOrderKeys(): List<String> = player?.playbackOrderKeys().orEmpty()

    fun hasPlaybackQueue(): Boolean = (player?.mediaItemCount ?: 0) > 0

    fun logicalRepeatMode(): Int = priority?.repeatMode ?: Player.REPEAT_MODE_ALL

    private fun snapshot() = player?.playbackSnapshot(laterKeys.toList())?.copy(
        repeat = when (logicalRepeatMode()) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            else -> RepeatMode.ALL
        }.name,
    )

    private fun savePlayback() {
        val state = snapshot() ?: return
        scope.launch { (application as ContainerHolder).container.settings.setPlayback(state) }
    }

    fun removeQueueTrack(mediaId: String): Boolean {
        laterKeys.remove(mediaId)
        val removed = player?.removeQueuedTrack(mediaId) ?: false
        priority?.refresh()
        return removed
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        player?.shuffleModeEnabled = mode.shuffled
        priority?.setRepeatMode(when (mode.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        })
    }

    private fun baseMetadata(track: Track): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle(track.title)
            .setDisplayTitle(track.title)
            .setArtist(track.artist.takeIf { it.isNotBlank() })
            .setAlbumTitle(track.album.takeIf { it.isNotBlank() })
            .setSubtitle(track.artist.takeIf { it.isNotBlank() })
            .apply {
                track.coverUrl
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("file:") }
                    ?.let { setArtworkUri(android.net.Uri.parse(it)) }
            }
            .build()
    }

    fun trackForKey(key: String?): Track? = key?.let { tracksByKey[it] }

    private fun currentTrack(): Track? {
        val id = player?.currentMediaItem?.mediaId ?: return null
        return tracksByKey[id]
    }

    private fun cacheCurrentArtwork() {
        val track = currentTrack() ?: return
        val raw = track.coverUrl?.takeIf { it.isNotBlank() } ?: return
        if (raw.startsWith("file:")) return
        scope.launch {
            val container = (application as ContainerHolder).container
            try {
                val base = container.sessionStore.current()?.profile?.baseUrl.orEmpty()
                val url = io.github.nutea.anylisten.core.data.gateway.UrlNormalizer.resolveArtwork(base, raw)
                val file = container.artwork.get(url)
                val exo = player ?: return@launch
                val item = exo.currentMediaItem ?: return@launch
                if (item.mediaId != track.cacheKey) return@launch
                exo.replaceMediaItem(exo.currentMediaItemIndex, item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon()
                        .setArtworkUri(android.net.Uri.fromFile(file)).build()).build())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Missing artwork must not interrupt audio playback.
            }
        }
    }

    private fun cacheCurrentTrack() {
        val track = currentTrack() ?: return
        scope.launch {
            val container = (application as ContainerHolder).container
            container.offlineAssets.cachePlayed(track, container.downloads.completedFile(track.cacheKey) != null,
                autoCacheAudio = automaticAudioCaching, streaming = true)
        }
    }

    private suspend fun isLoved(track: Track): Boolean {
        val love = (application as ContainerHolder).container.library.cached()
            .tracksByPlaylist[ProtocolConstants.LIST_LOVE]
            .orEmpty()
        return love.any { it.identity.remoteTrackId == track.identity.remoteTrackId }
    }

    private fun favoriteButton(liked: Boolean): CommandButton {
        val icon = if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val name = getString(if (liked) R.string.session_unfavorite else R.string.session_favorite)
        return CommandButton.Builder(icon)
            .setDisplayName(name)
            .setSessionCommand(favoriteCommand)
            .setEnabled(currentTrack() != null)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private fun playModeButton(mode: PlaybackMode): CommandButton {
        val icon = when (mode) {
            PlaybackMode.LIST_LOOP -> CommandButton.ICON_REPEAT_ALL
            PlaybackMode.RANDOM -> CommandButton.ICON_SHUFFLE_ON
            PlaybackMode.SEQUENCE -> CommandButton.ICON_REPEAT_OFF
            PlaybackMode.SINGLE -> CommandButton.ICON_REPEAT_ONE
        }
        val name = getString(
            when (mode) {
                PlaybackMode.LIST_LOOP -> R.string.session_play_mode_list_loop
                PlaybackMode.RANDOM -> R.string.session_play_mode_random
                PlaybackMode.SEQUENCE -> R.string.session_play_mode_sequence
                PlaybackMode.SINGLE -> R.string.session_play_mode_single
            },
        )
        return CommandButton.Builder(icon)
            .setDisplayName(name)
            .setSessionCommand(playModeCommand)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private fun sessionButtons(liked: Boolean = favoriteLiked): ImmutableList<CommandButton> =
        ImmutableList.of(favoriteButton(liked), playModeButton(currentPlayMode()))

    private fun currentPlayMode(): PlaybackMode {
        val exo = player ?: return PlaybackMode.LIST_LOOP
        val repeat = when (logicalRepeatMode()) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            else -> RepeatMode.ALL
        }
        return PlaybackMode.from(repeat, exo.shuffleModeEnabled)
    }

    private fun publishSessionButtons(liked: Boolean = favoriteLiked) {
        favoriteLiked = liked
        val buttons = sessionButtons(liked)
        session?.setMediaButtonPreferences(buttons)
        session?.setCustomLayout(buttons)
    }

    private fun refreshSessionButtons() {
        scope.launch {
            val track = currentTrack()
            val liked = if (track == null) {
                false
            } else {
                withContext(Dispatchers.IO) { isLoved(track) }
            }
            publishSessionButtons(liked)
        }
    }

    private fun toggleFavorite(): ListenableFuture<SessionResult> {
        val track = currentTrack() ?: return Futures.immediateFuture(
            SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE),
        )
        val future = SettableFuture.create<SessionResult>()
        scope.launch {
            runCatching {
                val library = (application as ContainerHolder).container.library
                withContext(Dispatchers.IO) {
                    if (isLoved(track)) {
                        library.removeFromPlaylist(ProtocolConstants.LIST_LOVE, track)
                    } else {
                        library.addToPlaylist(ProtocolConstants.LIST_LOVE, track)
                    }
                }
            }.onSuccess {
                refreshSessionButtons()
                future.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }.onFailure {
                refreshSessionButtons()
                future.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return future
    }

    private fun cyclePlayMode(): ListenableFuture<SessionResult> {
        val exo = player ?: return Futures.immediateFuture(
            SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE),
        )
        val next = currentPlayMode().next()
        exo.shuffleModeEnabled = next.shuffled
        priority?.setRepeatMode(when (next.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        })
        publishSessionButtons()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun consumePending() {
        val pending = pendingPlay.getAndSet(null) ?: return
        playTracks(pending.tracks, pending.start, pending.shuffled, pending.repeat, pending.startPositionMs, pending.laterKeys)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            val buttons = sessionButtons()
            return AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(favoriteCommand)
                        .add(playModeCommand)
                        .build(),
                )
                .setMediaButtonPreferences(buttons)
                .setCustomLayout(buttons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                COMMAND_FAVORITE -> toggleFavorite()
                COMMAND_PLAY_MODE -> cyclePlayMode()
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    companion object Holder {
        const val COMMAND_FAVORITE = "io.github.nutea.anylisten.FAVORITE"
        const val COMMAND_PLAY_MODE = "io.github.nutea.anylisten.PLAY_MODE"
        var service: PlaybackService? = null
        var resolver: PlaybackResolver? = null
        val pendingPlay = AtomicReference<PendingPlayback?>(null)
    }
}

interface ContainerHolder {
    val container: AppContainer
}
