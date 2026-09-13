package io.github.nutea.anylisten.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
)

class PlaybackService : MediaSessionService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    private var playJob: Job? = null
    private val tracksByKey = ConcurrentHashMap<String, Track>()
    private val favoriteCommand = SessionCommand(COMMAND_FAVORITE, Bundle.EMPTY)
    private val playModeCommand = SessionCommand(COMMAND_PLAY_MODE, Bundle.EMPTY)
    private var favoriteLiked = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as ContainerHolder).container
        val cacheDir = File(cacheDir, "exoplayer").apply { mkdirs() }
        val simpleCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        )
        cache = simpleCache
        val okHttpFactory = OkHttpDataSource.Factory(container.http)
        val upstream = DefaultDataSource.Factory(this, okHttpFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val resolvingFactory = ResolvingDataSource.Factory(cacheFactory) { spec ->
            val key = PlaybackUris.cacheKey(spec.uri) ?: return@Factory spec
            val track = tracksByKey[key] ?: throw IOException("Unknown track")
            val resolver = Holder.resolver ?: throw IOException("No resolver")
            val resource = runBlocking { resolver.resolve(track) }
            spec.buildUpon().setUri(android.net.Uri.parse(resource.url)).build()
        }
        val exo = ExoPlayer.Builder(this)
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
        exo.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshSessionButtons()
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
        val sessionBuilder = MediaSession.Builder(this, exo)
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
        Holder.resolver = PlaybackResolver(container.downloads, container.gateway)
        consumePending()
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
    ) {
        val exo = player ?: return
        val resolver = Holder.resolver ?: return
        playJob?.cancel()
        playJob = scope.launch {
            tracksByKey.clear()
            tracks.forEach { tracksByKey[it.cacheKey] = it }
            if (tracks.isEmpty()) return@launch
            val startIndex = tracks.indexOfFirst { it.identity == start?.identity }.coerceAtLeast(0)
            runCatching { resolver.resolve(tracks[startIndex]) }
            val items = tracks.map { track ->
                MediaItem.Builder()
                    .setMediaId(track.cacheKey)
                    .setUri(PlaybackUris.forTrack(track.cacheKey))
                    .setMediaMetadata(baseMetadata(track))
                    .build()
            }
            exo.setMediaItems(items, startIndex, startPositionMs.coerceAtLeast(0L))
            exo.shuffleModeEnabled = shuffled
            exo.repeatMode = when (repeat) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            exo.prepare()
            exo.play()
            refreshSessionButtons()
            prefetchAround(tracks, startIndex)
        }
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

    private fun currentTrack(): Track? {
        val id = player?.currentMediaItem?.mediaId ?: return null
        return tracksByKey[id]
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
        val repeat = when (exo.repeatMode) {
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
        exo.repeatMode = when (next.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        publishSessionButtons()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun prefetchAround(tracks: List<Track>, startIndex: Int) {
        val resolver = Holder.resolver ?: return
        scope.launch(Dispatchers.IO) {
            listOf(startIndex - 1, startIndex + 1)
                .filter { it in tracks.indices }
                .forEach { runCatching { resolver.resolve(tracks[it]) } }
        }
    }

    private fun consumePending() {
        val pending = pendingPlay.getAndSet(null) ?: return
        playTracks(pending.tracks, pending.start, pending.shuffled, pending.repeat, pending.startPositionMs)
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
