package io.github.nutea.anylisten.core.playback

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.core.data.AppScopes
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.data.session.PersistedPlayback
import io.github.nutea.anylisten.core.data.connection.ConnectionState
import io.github.nutea.anylisten.core.model.PlaybackMode
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RepeatMode
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.AudioInfo
import io.github.nutea.anylisten.core.model.AudioLocation
import io.github.nutea.anylisten.core.model.SleepTimerState
import io.github.nutea.anylisten.core.model.QueueReorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
    val sourceListId: String? = null,
    val playbackOrder: List<String> = emptyList(),
)

class PlaybackService : MediaSessionService() {
    private val job = SupervisorJob()
    // A failed maintenance coroutine must never take the music player's process with it.
    private val scope = AppScopes.create("playback", Dispatchers.Main.immediate, job)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    @Volatile private var automaticAudioCaching = true
    private var playJob: Job? = null
    private var reconnectJob: Job? = null
    private var bufferingWatch: Job? = null
    private var lastNetworkChangeAt = 0L
    private var lastPrepareAt = 0L
    private val tracksByKey = ConcurrentHashMap<String, Track>()
    private val laterKeys = linkedSetOf<String>()
    private var playSourceListId: String? = null
    private var priority: PriorityQueue? = null
    private val favoriteCommand = SessionCommand(COMMAND_FAVORITE, Bundle.EMPTY)
    private val playModeCommand = SessionCommand(COMMAND_PLAY_MODE, Bundle.EMPTY)
    private var favoriteLiked = false
    private var sleepTimer: SleepTimerController? = null
    private val audioFormats = mutableMapOf<String, AudioInfo>()
    private val audioLocations = ConcurrentHashMap<String, AudioLocation>()
    private var widgetRestoreJob: Job? = null
    private var publishedQueue = false
    private var destroying = false

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
        // Media has its own OkHttp dispatcher: clearing out API calls after a handover must not
        // abort the audio stream, and aborting the stream must not abort the reconnect.
        val okHttpFactory = OkHttpDataSource.Factory(container.mediaHttp)
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
                { automaticAudioCaching },
            ) { spec, openedLength ->
                val track = spec.customData as? Track ?: return@StreamingCacheDataSource null
                container.offlineAssets.openStreamSink(track, spec.uri.toString(), spec.position, openedLength, token)
            }
        }
        val resolvingFactory = ResolvingDataSource.Factory(playbackDataSource) { spec ->
            val key = PlaybackUris.cacheKey(spec.uri) ?: return@Factory spec
            val track = tracksByKey[key] ?: throw IOException("Unknown track")
            val resolver = Holder.resolver ?: throw IOException("No resolver")
            // Runs on a Media3 loading thread. It may block: the resolver waits for a reconnect
            // in progress so an uncached track can re-resolve across a handover instead of
            // failing the load. Everything that leaves here must be an IOException, or Media3
            // reports a fatal error instead of retrying.
            val resource = try {
                runBlocking { resolver.resolve(track) }
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                throw IOException(error.message ?: "Media resolve failed", error)
            }
            audioLocations[key] = if (resource.url.startsWith("file:")) {
                if (android.net.Uri.parse(resource.url).path.orEmpty().startsWith(container.downloadsDir.path + "/"))
                    AudioLocation.DOWNLOAD else AudioLocation.CACHE
            } else AudioLocation.SERVER
            scope.launch { publishAudioInfo() }
            spec.buildUpon().setUri(android.net.Uri.parse(resource.url)).setKey(resolver.cacheKey(track,resource)).setCustomData(track).build()
        }
        val exo = ExoPlayer.Builder(this, AudioWaveRenderersFactory(this))
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
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        priority = PriorityQueue(exo, laterKeys)
        sleepTimer = SleepTimerController(exo, scope).also { timer ->
            scope.launch { timer.state.collect { mutableSleepTimer.value = it } }
        }
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
                val key = eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.mediaId
                audioFormats[key] = format.audioInfo(key)
                publishAudioInfo()
            }
        })
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode in 2000..2999) recoverConnection()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    watchBuffering(playbackState)
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) { effects?.attach(audioSessionId) }
                override fun onEvents(player: Player, events: Player.Events) {
                    sampleListening()
                    container.setPlaybackActive(player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE)
                    savePlayback()
                    publishAudioInfo()
                    publishSurface()
                    if (player.mediaItemCount == 0) sleepTimer?.cancel()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    sampleListening(newOccurrence = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                    mediaItem?.mediaId?.let { laterKeys.remove(it) }
                    if (TrackChangePlayback.shouldPlayAfterTransition(reason, exo.playWhenReady)) {
                        exo.play()
                    }
                    refreshSessionButtons()
                    cacheCurrentArtwork()
                    cacheCurrentTrack()
                    recordRecentlyPlayed(mediaItem?.mediaId)
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
        effects = AudioEffectsController(exo) { mutableEffects.value = it }
        effects?.attach(exo.audioSessionId)
        scope.launch { container.settings.audioEffects.collect { effects?.update(it) } }
        scope.launch { while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(1000); sampleListening()
        } }
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
        watchSession()
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
        if (intent?.action in WIDGET_ACTIONS) {
            showWidgetStartingNotification()
            handleWidgetAction(intent!!.action!!)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private var effects: AudioEffectsController? = null
    private val listening = io.github.nutea.anylisten.core.model.ListeningAccumulator()
    private fun sampleListening(newOccurrence: Boolean = false) {
        val exo = player ?: return
        val track = exo.currentMediaItem?.mediaId?.let(::trackForKey)
        val delta = listening.sample(SystemClock.elapsedRealtime(), track?.cacheKey, exo.isPlaying,
            exo.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0, newOccurrence)
        (application as ContainerHolder).container.listening.record(delta)
    }
    override fun onDestroy() {
        sampleListening()
        effects?.release()
        destroying = true
        AudioWaveSignal.reset()
        sleepTimer?.release()
        mutableSleepTimer.value = SleepTimerState()
        mutableAudioInfo.value = AudioInfo()
        mutableSurface.value = mutableSurface.value?.copy(isPlaying = false, playWhenReady = false, favoriteAvailable = false)
        val finalState = snapshot()
        if (finalState != null) persistPlayback((application as ContainerHolder).container.settings, finalState)
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
        sourceListId: String? = null,
        restoredPlaybackOrder: List<String> = emptyList(),
    ) {
        val exo = player ?: return
        if (Holder.resolver == null) return
        playJob?.cancel()
        playJob = scope.launch {
            tracksByKey.clear()
            audioFormats.clear()
            audioLocations.clear()
            laterKeys.clear()
            playSourceListId = sourceListId
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
            if (shuffled) { exo.restorePlaybackOrder(restoredPlaybackOrder); priority?.refresh() }
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

    /**
     * Playback follows the shared connection state instead of registering a second
     * `ConnectivityManager` callback and running a second restore loop. Deciding *when* to
     * reconnect is the connection manager's job; this only reacts to the socket it gets.
     */
    private fun watchSession() {
        val container = (application as ContainerHolder).container
        scope.launch {
            // StateFlow already conflates equal values, so this fires once per real transition.
            container.network.status.collect { lastNetworkChangeAt = SystemClock.elapsedRealtime() }
        }
        scope.launch {
            container.connection.state
                .map { (it as? ConnectionState.Online)?.generation ?: 0L }
                .distinctUntilChanged()
                .collect { generation -> if (generation != 0L) { onSessionEstablished(); refreshSessionButtons() }; publishSurface() }
        }
    }

    /**
     * A new socket exists. Previously resolved stream URLs belong to the session that is gone, so
     * they are invalidated and the timeline is replayed from its current position.
     */
    private fun onSessionEstablished() {
        Holder.resolver?.invalidateRemoteUrls()
        cacheCurrentTrack()
        replayTimeline()
    }

    /** `prepare()` preserves position and `playWhenReady`, including a user's pause. */
    private fun replayTimeline(force: Boolean = false) {
        val exo = player ?: return
        if (exo.mediaItemCount == 0) return
        if (!force && !PlaybackReconnect.shouldReplayTimeline(exo.playerError != null, exo.playbackState, exo.playWhenReady)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPrepareAt < MIN_PREPARE_INTERVAL_MS) return
        lastPrepareAt = now
        exo.prepare()
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

    /**
     * Ask for a session and replay what can already play.
     *
     * The retry loop that used to live here — its own backoff, its own `session.restore()`, its
     * own view of whether the network was usable — is gone. There is one reconnect policy, in
     * the connection manager, and [watchSession] reacts when it succeeds.
     */
    fun recoverConnection(playRequested: Boolean = false) {
        val exo = player ?: return
        if (playRequested) exo.playWhenReady = true
        val container = (application as ContainerHolder).container
        container.connection.requestConnect()
        if (playRequested) {
            // Explicit user intent: prepare now so the resolver starts waiting for the session.
            replayTimeline(force = true)
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val current = currentTrack()
            val playableOffline = current != null && (
                container.downloads.completedFile(current.cacheKey) != null ||
                    container.offlineAssets.audioFile(current.cacheKey) != null
                )
            if (playableOffline || container.isConnected()) replayTimeline()
        }
    }

    fun playbackOrderKeys(): List<String> = player?.playbackOrderKeys().orEmpty()
    fun laterQueueKeys(): List<String> = laterKeys.toList()

    fun hasPlaybackQueue(): Boolean = (player?.mediaItemCount ?: 0) > 0

    fun tracksInQueue(): List<Track> = player?.let { exo ->
        (0 until exo.mediaItemCount).mapNotNull { tracksByKey[exo.getMediaItemAt(it).mediaId] }
    }.orEmpty()

    fun startSleepTimer(durationMs: Long) { sleepTimer?.start(durationMs) }
    fun stopAfterCurrentTrack() { sleepTimer?.stopAfterTrack() }
    fun cancelSleepTimer() { sleepTimer?.cancel() }

    fun moveQueueTrack(from: String, to: String): Boolean {
        val exo = player ?: return false
        val byKey = tracksInQueue().associateBy { it.cacheKey }
        val displayed = playbackOrderKeys().mapNotNull(byKey::get)
        val moved = QueueReorder.move(displayed, currentTrack()?.cacheKey, laterKeys.toList(), from, to) ?: return false
        applyQueuePreservingCurrent(moved.queue, moved.laterKeys)
        if (exo.shuffleModeEnabled) {
            exo.restorePlaybackOrder(displayed.map { it.cacheKey }.let { original ->
                if (from in laterKeys) original else moved.queue.map { it.cacheKey }
            })
            priority?.refresh()
        }
        savePlayback()
        publishSurface()
        return true
    }

    private fun publishAudioInfo() {
        if (destroying) return
        val key = currentTrack()?.cacheKey
        mutableAudioInfo.value = (audioFormats[key] ?: AudioInfo(trackKey = key)).copy(
            location = audioLocations[key] ?: AudioLocation.UNKNOWN)
    }

    private fun publishSurface() {
        if (destroying) return
        val exo = player ?: return
        // Connecting a controller to an idle service must retain the widget's saved song.
        if (exo.mediaItemCount == 0 && !publishedQueue) return
        if (exo.mediaItemCount > 0) publishedQueue = true
        val artwork = exo.currentMediaItem?.mediaMetadata?.artworkUri?.takeIf { it.scheme == "file" }?.path
        mutableSurface.value = PlaybackSurfaceState(currentTrack(), exo.isPlaying, exo.playWhenReady,
            favoriteLiked, (application as ContainerHolder).container.isConnected(), artwork)
    }

    fun logicalRepeatMode(): Int = priority?.repeatMode ?: Player.REPEAT_MODE_ALL

    private fun snapshot() = player?.playbackSnapshot(laterKeys.toList())?.copy(
        playbackOrder = if (player?.shuffleModeEnabled == true) playbackOrderKeys() else emptyList(),
        repeat = when (logicalRepeatMode()) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            else -> RepeatMode.ALL
        }.name,
    )

    private fun savePlayback() {
        if (destroying) return
        val state = snapshot() ?: return
        persistPlayback((application as ContainerHolder).container.settings, state)
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
        publishSurface()
    }

    private fun refreshSessionButtons() {
        scope.launch {
            val track = currentTrack()
            val liked = if (track == null) {
                false
            } else {
                withContext(Dispatchers.IO) { isLoved(track) }
            }
            if (currentTrack()?.cacheKey != track?.cacheKey) return@launch
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
        playTracks(
            pending.tracks,
            pending.start,
            pending.shuffled,
            pending.repeat,
            pending.startPositionMs,
            pending.laterKeys,
            pending.sourceListId,
            pending.playbackOrder,
        )
    }

    /** Widget clicks operate the same player, including a cold process with a saved local queue. */
    fun handleWidgetAction(action: String) {
        val exo = player ?: return
        if (exo.mediaItemCount > 0) {
            when (action) {
                ACTION_WIDGET_TOGGLE -> if (exo.playWhenReady) exo.pause() else recoverConnection(playRequested = true)
                ACTION_WIDGET_NEXT -> { exo.seekToNextMediaItem(); exo.play() }
                ACTION_WIDGET_PREVIOUS -> { exo.seekToPreviousMediaItem(); exo.play() }
            }
            return
        }
        if (widgetRestoreJob?.isActive == true) return
        widgetRestoreJob = scope.launch {
            val container = (application as ContainerHolder).container
            try {
                val restored = withTimeout(5_000) { withContext(Dispatchers.IO) {
                    awaitPlaybackPersistence()
                    val saved = container.settings.playback.first()
                    val downloaded = container.downloads.observe().first().associateBy { it.cacheKey }
                    val tracks = saved.cacheKeys.mapNotNull { key -> container.library.cachedTrack(key)
                        ?: downloaded[key]?.let { record -> Track(record.identity, record.title, record.artist,
                            "", null, fingerprint = record.fingerprint) } }
                    saved to tracks
                } }
                if (exo.mediaItemCount > 0) { handleWidgetAction(action); return@launch }
                val (saved, tracks) = restored
                if (tracks.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@launch }
                val current = tracks.firstOrNull { it.cacheKey == saved.currentKey } ?: tracks.first()
                playTracks(tracks, current, saved.shuffled, saved.repeatMode(), saved.positionMs, saved.laterKeys,
                    restoredPlaybackOrder = saved.playbackOrder)
                if (action != ACTION_WIDGET_TOGGLE) handleWidgetAction(action)
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else throw cancelled
            } catch (_: Exception) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun showWidgetStartingNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = "widget_playback_start"
        manager.createNotificationChannel(NotificationChannel(channel, getString(R.string.widget_playback_start), NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_playback_note)
            .setContentTitle(getString(R.string.widget_playback_start)).setOngoing(true)
            .apply { if (launch != null) setContentIntent(PendingIntent.getActivity(this@PlaybackService, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }.build()
        startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
    }

    private fun recordRecentlyPlayed(cacheKey: String?) {
        val track = cacheKey?.let { tracksByKey[it] } ?: return
        val sourceListId = playSourceListId
        scope.launch(Dispatchers.IO) {
            runCatching {
                (application as ContainerHolder).container.recentlyPlayed.onTrackStarted(track, sourceListId)
            }
        }
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
        private val persistenceScope = AppScopes.create("playback-persistence", Dispatchers.IO)
        @Volatile private var persistenceJob: Job? = null
        suspend fun awaitPlaybackPersistence() { persistenceJob?.join() }
        // Calls originate on the player's main thread. Writes survive service destruction and
        // retain event order; DataStore transforms must never be waited on by a blocked main thread.
        private fun persistPlayback(settings: AppSettingsStore, state: PersistedPlayback) {
            val previous = persistenceJob
            persistenceJob = persistenceScope.launch { previous?.join(); settings.setPlayback(state) }
        }
        const val ACTION_WIDGET_TOGGLE = "io.github.nutea.anylisten.WIDGET_TOGGLE"
        const val ACTION_WIDGET_NEXT = "io.github.nutea.anylisten.WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS = "io.github.nutea.anylisten.WIDGET_PREVIOUS"
        private val WIDGET_ACTIONS = setOf(ACTION_WIDGET_TOGGLE, ACTION_WIDGET_NEXT, ACTION_WIDGET_PREVIOUS)
        private val mutableEffects = MutableStateFlow(io.github.nutea.anylisten.core.model.AudioEffectsState())
        val audioEffects = mutableEffects.asStateFlow()
        private val mutableSleepTimer = MutableStateFlow(SleepTimerState())
        val sleepTimerState = mutableSleepTimer.asStateFlow()
        private val mutableAudioInfo = MutableStateFlow(AudioInfo())
        val audioInfo = mutableAudioInfo.asStateFlow()
        private val mutableSurface = MutableStateFlow<PlaybackSurfaceState?>(null)
        val surfaceState = mutableSurface.asStateFlow()
        const val COMMAND_FAVORITE = "io.github.nutea.anylisten.FAVORITE"
        const val COMMAND_PLAY_MODE = "io.github.nutea.anylisten.PLAY_MODE"
        private const val MIN_PREPARE_INTERVAL_MS = 2_000L
        var service: PlaybackService? = null
        var resolver: PlaybackResolver? = null
        val pendingPlay = AtomicReference<PendingPlayback?>(null)
    }
}

interface ContainerHolder {
    val container: AppContainer
}
