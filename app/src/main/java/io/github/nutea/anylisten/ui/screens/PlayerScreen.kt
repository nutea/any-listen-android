package io.github.nutea.anylisten.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.AppViewModel
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.rememberMotionEnabled
import io.github.nutea.anylisten.ui.tickHaptic

data class PlayerActions(
    val back: () -> Unit, val toggle: () -> Unit, val previous: () -> Unit, val next: () -> Unit,
    val favorite: () -> Unit, val seek: (Long) -> Unit, val mode: (PlaybackMode) -> Unit,
    val queuePlay: (Track) -> Unit, val queueRemove: (Track) -> Unit,
    val download: () -> Unit = {}, val more: () -> Unit = {},
    val translation: (Boolean) -> Unit = {}, val lyricOffset: (Long) -> Unit = {},
    val romanization: (Boolean) -> Unit = {}, val karaoke: (Boolean) -> Unit = {},
    val comments: () -> Unit = {},
    val artist: () -> Unit = {}, val album: () -> Unit = {},
)

@Composable
fun PlayerScreen(vm: AppViewModel, onBack: () -> Unit, onArtist: (Track) -> Unit = {}, onAlbum: (Track) -> Unit = {}) {
    val state by vm.player.collectAsState()
    var showComments by remember { mutableStateOf(false) }
    if (showComments) state.track?.let { CommentsSheet(it, vm.musicComments) { showComments = false } }
    val library by vm.library.collectAsState()
    val requested by vm.playerSheet.collectAsState()
    val downloadedKeys = downloadedTrackKeys(vm)
    PlayerContent(state, vm::artworkUrl, state.track?.let { vm.isFavorite(it) } == true, library.snapshot.offline,
        PlayerActions({ vm.cancelAddToPlaylist(); onBack() }, vm::togglePlayPause, vm::skipPrevious, vm::skipNext,
            { state.track?.let(vm::toggleFavorite) }, vm::seekTo, vm::setPlayMode, vm::playQueueItem, vm::removeQueueItem,
            download = { state.track?.let { vm.download(listOf(it)) } },
            more = { state.track?.let(vm::startAddToPlaylist) },
            translation = vm::setShowTranslation, lyricOffset = vm::setLyricOffset,
            romanization = vm::setShowRomanization, karaoke = vm::setKaraokeEnabled, comments = { showComments = true },
            artist = { state.track?.let(onArtist) }, album = { state.track?.let(onAlbum) }),
        source = library.selected?.let { playlistName(it) }, startSheet = requested,
        onSheetConsumed = vm::consumePlayerSheet, downloaded = state.track?.cacheKey in downloadedKeys)

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    state: PlayerUiState, artwork: (Track?) -> String?, favorite: Boolean, offline: Boolean, actions: PlayerActions,
    source: String? = null, startSheet: String? = null, onSheetConsumed: () -> Unit = {},
    downloaded: Boolean = false,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val pager = rememberPagerState(pageCount = { 2 })
    val pageScope = rememberCoroutineScope()

    val motion = rememberMotionEnabled()
    val hapticView = LocalView.current
    fun showPage(index: Int) {
        pageScope.launch { if (motion) pager.animateScrollToPage(index) else pager.scrollToPage(index) }
    }
    LaunchedEffect(startSheet) {
        if (startSheet != null) {
            sheet = startSheet
            onSheetConsumed()
        }
    }
    BackHandler(enabled = pager.currentPage == 1 || sheet != null) {
        if (sheet != null) sheet = null else showPage(0)
    }
    val track = state.track
    val mode = PlaybackMode.from(state.repeat, state.shuffled)
    val cover = artwork(track)
    val accent = rememberCoverAccent(cover, io.github.nutea.anylisten.ui.theme.LocalDarkTheme.current)
    Column(Modifier.fillMaxSize().background(accent).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_library)) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                listOf(R.string.cover_tab, R.string.open_lyrics).forEachIndexed { index, label ->
                    TextButton(onClick = { hapticView.tickHaptic(); showPage(index) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(label), color = if (pager.currentPage == index) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (pager.currentPage == index) FontWeight.SemiBold else FontWeight.Normal)
                            Box(Modifier.padding(top = 5.dp).size(width = 16.dp, height = 2.dp)
                                .background(if (pager.currentPage == index) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
                        }
                    }
                }
            }
            if (pager.currentPage == 1 && track != null) IconButton(onClick = { sheet = "lyrics" }) {
                Icon(Icons.Default.Tune, stringResource(R.string.lyric_settings))
            } else Spacer(Modifier.size(48.dp))
        }
        if (track == null) {
            EmptyContent(Icons.Default.MusicNote, stringResource(R.string.player_empty), stringResource(R.string.player_empty_detail),
                Modifier.weight(1f))
        } else {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("player_pages"),
                verticalAlignment = Alignment.Top) { page ->
                if (page == 0) {
                    Column(Modifier.fillMaxSize().testTag("cover_page")) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val edge = minOf(maxWidth, maxHeight * .88f, 360.dp)
                            Artwork(cover, Modifier.size(edge), seed = track.album.ifBlank { track.title }, radius = 20.dp)
                        }
                        val lines = state.lyrics?.displayLines(state.karaokeEnabled).orEmpty()
                        val lyricIndex = lines.indexOfLast { it.timeMs <= LyricTiming.position(state.positionMs, state.lyricOffsetMs) }
                        val preview = lines.getOrNull(lyricIndex)?.text?.takeIf { it.isNotBlank() }
                            ?: lines.firstOrNull { it.text.isNotBlank() }?.text
                            ?: state.lyrics?.raw?.lineSequence()?.firstOrNull { it.isNotBlank() && !it.startsWith("[") }
                            ?: stringResource(R.string.lyrics_empty)
                        Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (track.artist.isNotBlank()) TextButton(onClick = actions.artist,
                                modifier = Modifier.weight(1f).testTag("player_artist"), contentPadding = PaddingValues(end = 8.dp)) {
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                            }
                            if (track.album.isNotBlank()) TextButton(onClick = actions.album,
                                modifier = Modifier.weight(1f).testTag("player_album"), contentPadding = PaddingValues(start = 8.dp)) {
                                Icon(Icons.Default.Album, null, Modifier.padding(end = 4.dp).size(16.dp))
                                Text(track.album, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                            }
                            if (state.availableOffline) Icon(Icons.Default.OfflinePin, stringResource(R.string.track_available_offline),
                                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().testTag("cover_lyric_preview").clickable { showPage(1) }
                                .padding(horizontal = 8.dp, vertical = 16.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = actions.favorite, enabled = !offline) {
                                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    stringResource(if (favorite) R.string.cd_unfavorite else R.string.cd_favorite))
                            }
                            IconButton(onClick = actions.comments) { Icon(Icons.Default.Comment, stringResource(R.string.song_comments)) }
                            IconButton(onClick = actions.download, enabled = !downloaded) {
                                Icon(if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                    stringResource(if (downloaded) R.string.cd_downloaded else R.string.cd_download))
                            }
                            IconButton(onClick = actions.more, enabled = !offline) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.cd_add_to_playlist))
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().testTag("lyrics_page")) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                        Text(track.artist.ifBlank { source.orEmpty() }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = track.artist.isNotBlank(), onClick = actions.artist).padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                        key(track.cacheKey) { LyricsPane(state, actions.seek, motion, pager.currentPage == 1) }
                    }
                }
            }
            state.error?.let { Notice(it, error = true) }
            PlaybackSlider(state, actions.seek)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sheet = "mode" }) { Icon(modeIcon(mode), stringResource(modeLabel(mode)), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = actions.previous, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_previous), Modifier.size(32.dp)) }
                FilledIconButton(onClick = actions.toggle, modifier = Modifier.size(68.dp)) {
                    PlaybackToggleGlyph(state, large = true)
                }
                IconButton(onClick = actions.next, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), Modifier.size(32.dp)) }
                IconButton(onClick = { sheet = "queue" }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.open_queue))
                }
            }
        }
    }
    sheet?.let { showing ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (showing) {
                "mode" -> {
                    Text(stringResource(R.string.choose_play_mode), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    PlaybackMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { actions.mode(option); sheet = null }.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(modeIcon(option), null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(modeLabel(option)), Modifier.weight(1f))
                            if (option == mode) Icon(Icons.Default.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "lyrics" -> LyricSettingsContent(state, actions)
                "queue" -> QueueSheetContent(state, artwork, actions.queuePlay, actions.queueRemove)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(state: PlayerUiState, artwork: (Track?) -> String?, onPlay: (Track) -> Unit,
    onRemove: (Track) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        QueueSheetContent(state, artwork, onPlay, onRemove)
    }
}

@Composable
private fun QueueSheetContent(state: PlayerUiState, artwork: (Track?) -> String?, onPlay: (Track) -> Unit, onRemove: (Track) -> Unit) {
    val tracksByKey = state.queue.associateBy { it.cacheKey }
    val displayedQueue = if (state.shuffled && state.playbackOrder.isNotEmpty())
        state.playbackOrder.mapNotNull(tracksByKey::get) else state.queue
    val sections = QueueSections.from(displayedQueue, state.track?.cacheKey, state.laterKeys)
    Column(Modifier.fillMaxHeight(.75f)) {
    Text(stringResource(R.string.queue_count, state.queue.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (state.queue.isEmpty()) item { EmptyContent(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_empty), stringResource(R.string.player_empty_detail)) }
        sections.nowPlaying?.let { item ->
            item { QueueSectionLabel(stringResource(R.string.queue_now_playing)) }
            item { QueueTrackRow(item, artwork(item), true, state.isPlaying, onPlay, onRemove) }
        }
        if (sections.later.isNotEmpty()) {
            item { QueueSectionLabel(stringResource(R.string.queue_later)) }
            itemsIndexed(sections.later, key = { index, item -> "later:${item.cacheKey}:$index" }) { _, item ->
                QueueTrackRow(item, artwork(item), false, state.isPlaying, onPlay, onRemove)
            }
        }
        if (sections.rest.isNotEmpty()) {
            item { QueueSectionLabel(stringResource(R.string.queue_next)) }
            itemsIndexed(sections.rest, key = { index, item -> "rest:${item.cacheKey}:$index" }) { _, item ->
                QueueTrackRow(item, artwork(item), false, state.isPlaying, onPlay, onRemove)
            }
        }
    }
    }
}

@Composable
private fun LyricsPane(state: PlayerUiState, onSeek: (Long) -> Unit, motion: Boolean, visible: Boolean) {
    val lines = state.lyrics?.displayLines(state.karaokeEnabled).orEmpty()
    val raw = state.lyrics?.raw.orEmpty()
    val playbackPosition by rememberLyricPlaybackPosition(state, visible && motion && state.karaokeEnabled && state.lyrics?.karaokeLines?.isNotEmpty() == true)
    val lyricPosition = LyricTiming.position(playbackPosition, state.lyricOffsetMs)
    val current = lines.indexOfLast { it.timeMs <= lyricPosition }
    val scroll = rememberLazyListState()
    var following by rememberSaveable { mutableStateOf(true) }
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(current, following) {
        if (following && current >= 0) {
            if (motion) scroll.animateScrollToItem(current) else scroll.scrollToItem(current)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topSpace = maxHeight * .25f
        val bottomSpace = maxHeight * .55f
        val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .07f to Color.Black,
                    .88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
        if (lines.isEmpty()) {
            val plain = listOfNotNull(raw.takeIf { it.isNotBlank() },
                state.lyrics?.romanizationRaw?.takeIf { state.showRomanization && it.isNotBlank() },
                state.lyrics?.translationRaw?.takeIf { state.showTranslation && it.isNotBlank() }).joinToString("\n\n")
            if (plain.isNotBlank()) Text(plain, fontSize = 24.sp, lineHeight = 38.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxSize().then(fade).testTag("plain_lyrics")
                    .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 32.dp))
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyContent(Icons.Default.Lyrics, stringResource(R.string.lyrics_empty), stringResource(R.string.lyrics_empty_detail))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().then(fade).testTag("timed_lyrics"), state = scroll,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = topSpace, bottom = bottomSpace)) {
                itemsIndexed(lines) { index, line ->
                    val active = index == current
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable { onSeek(LyricTiming.seek(line.timeMs, state.lyricOffsetMs)); following = true }
                        .padding(vertical = 14.dp)) {
                        if (active && state.karaokeEnabled && line.words.isNotEmpty()) {
                            KaraokeLyricText(line, lyricPosition)
                        } else Text(line.text.ifBlank { "♪" }, fontSize = 26.sp, lineHeight = 39.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = if (index < current) .40f else .58f))
                        if (state.showRomanization && !line.romanization.isNullOrBlank()) {
                            Text(line.romanization.orEmpty(), fontSize = 15.sp, lineHeight = 23.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (active) 1f else .7f),
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        if (state.showTranslation && !line.translation.isNullOrBlank()) {
                            Text(line.translation.orEmpty(), fontSize = 18.sp, lineHeight = 27.sp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (!following) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp)) {
                    TextButton(onClick = { following = true }) { Text(stringResource(R.string.return_to_lyric)) }
                }
            }
        }
    }
}

@Composable
private fun QueueSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun QueueTrackRow(item: Track, cover: String?, current: Boolean, isPlaying: Boolean, onPlay: (Track) -> Unit, onRemove: (Track) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPlay(item) }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(cover, Modifier.size(44.dp), seed = item.album, radius = 8.dp)
        TrackHeading(item.title, item.artist, Modifier.weight(1f))
        if (current) PlaybackIndicator(isPlaying)
        IconButton(onClick = { onRemove(item) }) {
            Icon(Icons.Default.Close, stringResource(R.string.remove_queue_track, item.title))
        }
    }
}

@Composable
fun MiniPlayer(vm: AppViewModel, onOpenPlayer: () -> Unit, onOpenQueue: () -> Unit = onOpenPlayer) {
    val state by vm.player.collectAsState()
    MiniPlayerContent(state, vm.artworkUrl(state.track), onOpenPlayer, vm::togglePlayPause, vm::skipNext, onOpenQueue)
}

@Composable
fun MiniPlayerContent(
    state: PlayerUiState, artwork: String?, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onQueue: () -> Unit = {},
) {
    val track = state.track ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(artwork, Modifier.size(40.dp), seed = track.album, radius = 8.dp)
                TrackHeading(track.title, track.artist, Modifier.weight(1f))
                IconButton(onClick = onToggle) { PlaybackToggleGlyph(state) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next)) }
                IconButton(onClick = onQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.open_queue))
                }
            }
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    }
}

/** Buffering keeps the pause action, with a progress ring around it. */
@Composable
private fun PlaybackToggleGlyph(state: PlayerUiState, large: Boolean = false) {
    val pause = state.isPlaying || state.playWhenReady
    val loading = stringResource(R.string.playback_loading)
    Box(Modifier.size(if (large) 46.dp else 34.dp).semantics {
        if (state.isBuffering) stateDescription = loading
    }, contentAlignment = Alignment.Center) {
        if (state.isBuffering) CircularProgressIndicator(
            modifier = Modifier.fillMaxSize().testTag("playback_loading_spinner"),
            color = LocalContentColor.current, strokeWidth = 2.dp)
        Icon(if (pause) Icons.Default.Pause else Icons.Default.PlayArrow,
            stringResource(if (pause) R.string.cd_pause else R.string.cd_play),
            Modifier.size(if (large) 32.dp else 22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSlider(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs.coerceAtLeast(0L)
    var dragging by remember(state.track?.cacheKey) { mutableStateOf<Float?>(null) }
    val progress = dragging ?: if (duration <= 0) 0f else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Slider(value = progress, onValueChange = { dragging = it },
            onValueChangeFinished = { if (duration > 0) onSeek(((dragging ?: progress) * duration).toLong()); dragging = null },
            enabled = duration > 0, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            thumb = { Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) },
            track = { SliderDefaults.Track(sliderState = it, modifier = Modifier.height(4.dp),
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
                thumbTrackGapSize = 0.dp, drawStopIndicator = null) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(if (dragging != null) (progress * duration).toLong() else state.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMs(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun modeIcon(mode: PlaybackMode) = when (mode) {
    PlaybackMode.LIST_LOOP -> Icons.Default.Repeat
    PlaybackMode.RANDOM -> Icons.Default.Shuffle
    PlaybackMode.SEQUENCE -> Icons.AutoMirrored.Filled.PlaylistPlay
    PlaybackMode.SINGLE -> Icons.Default.RepeatOne
}
internal fun modeLabel(mode: PlaybackMode) = when (mode) {
    PlaybackMode.LIST_LOOP -> R.string.cd_play_mode_list_loop
    PlaybackMode.RANDOM -> R.string.cd_play_mode_random
    PlaybackMode.SEQUENCE -> R.string.cd_play_mode_sequence
    PlaybackMode.SINGLE -> R.string.cd_play_mode_single
}
internal fun formatMs(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}


@Composable
private fun LyricSettingsContent(state: PlayerUiState, actions: PlayerActions) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.lyric_settings), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.lyric_settings_subtitle), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                LyricOptionRow(Icons.Default.Translate, R.string.lyric_translation,
                    if (state.lyrics?.translationRaw.isNullOrBlank()) R.string.lyric_no_translation else R.string.lyric_translation_hint,
                    state.showTranslation, actions.translation, "lyric_translation_toggle")
                HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                LyricOptionRow(Icons.Default.Abc, R.string.lyric_romanization,
                    if (state.lyrics?.romanizationRaw.isNullOrBlank()) R.string.lyric_no_romanization else R.string.lyric_romanization_hint,
                    state.showRomanization, actions.romanization, "lyric_romanization_toggle")
                HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                LyricOptionRow(Icons.Default.GraphicEq, R.string.lyric_karaoke,
                    if (state.lyrics?.karaokeLines.isNullOrEmpty()) R.string.lyric_no_karaoke else R.string.lyric_karaoke_hint,
                    state.karaokeEnabled, actions.karaoke, "lyric_karaoke_toggle")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lyric_sync), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { actions.lyricOffset(0) }, enabled = state.lyricOffsetMs != 0L) { Text(stringResource(R.string.lyric_offset_reset)) }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.lyric_offset, state.lyricOffsetMs), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("lyric_offset_value").padding(bottom = 14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { actions.lyricOffset(LyricTiming.clamp(state.lyricOffsetMs - 100)) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        enabled = state.lyricOffsetMs > -LyricTiming.LIMIT_MS) { Text(stringResource(R.string.lyric_earlier)) }
                    FilledTonalButton(onClick = { actions.lyricOffset(LyricTiming.clamp(state.lyricOffsetMs + 100)) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        enabled = state.lyricOffsetMs < LyricTiming.LIMIT_MS) { Text(stringResource(R.string.lyric_later)) }
                }
            }
        }
        Text(stringResource(R.string.lyric_offset_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
    }
}

@Composable
private fun LyricOptionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, subtitle: Int,
    checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
