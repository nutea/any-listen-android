package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.data.gateway.*
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.CancellationException
import io.github.nutea.anylisten.core.data.repo.CommentRepository
import io.github.nutea.anylisten.core.data.repo.CommentThread
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(track: Track, service: CommentRepository, onDismiss: () -> Unit) {
    // Disposing or changing the song cancels all requests, including source matching.
    key(track.cacheKey) {
        var sources by remember { mutableStateOf<List<CommentSource>?>(null) }
        var source by remember { mutableStateOf<CommentSource?>(null) }
        var music by remember { mutableStateOf<CommentThread?>(null) }
        var type by remember { mutableStateOf("hot") }
        var page by remember { mutableIntStateOf(1) }
        var retry by remember { mutableIntStateOf(0) }
        var result by remember { mutableStateOf<CommentPage?>(null) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var unmatched by remember { mutableStateOf(false) }
        LaunchedEffect(source, type, page, retry) {
            loading = true; failed = false; unmatched = false; result = null
            try {
                if (sources == null) {
                    val available = service.sources()
                    sources = available
                    val selected = available.firstOrNull { it == source } ?: service.preferredSource(track, available)
                    if (selected != source) {
                        source = selected
                        return@LaunchedEffect
                    }
                }
                source?.let { selected ->
                    val matched = music ?: service.match(track, selected).also { music = it }
                    if (matched == null) unmatched = true
                    else result = matched.page(type, page)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        ModalBottomSheet(onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.song_comments), style = MaterialTheme.typography.titleLarge)
                        Text(track.title + " · " + track.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { sources = null; music = null; page = 1; retry++ }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.comments_refresh), Modifier.size(22.dp))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close_panel), Modifier.size(22.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf("hot" to R.string.comments_hot, "new" to R.string.comments_new).forEach { (value, label) ->
                        TextButton(onClick = { type = value; page = 1 },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (type == value)
                                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(label), fontWeight = if (type == value) FontWeight.Bold else FontWeight.Normal)
                                Box(Modifier.padding(top = 5.dp).size(16.dp, 3.dp).clip(CircleShape)
                                    .background(if (type == value) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    var expanded by remember { mutableStateOf(false) }
                    Box(Modifier.widthIn(max = 160.dp)) {
                        Surface(onClick = { expanded = true }, enabled = !sources.isNullOrEmpty(),
                            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(source?.name ?: stringResource(R.string.comment_source), style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp))
                            }
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            sources.orEmpty().forEach { item ->
                                DropdownMenuItem(text = { Text(item.name) },
                                    trailingIcon = { if (item == source) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = { music = null; source = item; page = 1; expanded = false })
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                val current = result
                val scroll = rememberLazyListState()
                LaunchedEffect(current) { if (current != null) scroll.scrollToItem(0) }
                if (loading || failed || current?.list.isNullOrEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        else {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Icon(if (failed) Icons.Default.CloudOff else Icons.Default.Forum, null,
                                    Modifier.padding(18.dp).size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(when {
                                failed -> R.string.comments_error
                                sources.isNullOrEmpty() -> R.string.comments_no_source
                                unmatched -> R.string.comments_no_match
                                else -> R.string.comments_empty
                            }), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.comments_retry)) }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                        items(current?.list.orEmpty()) { comment ->
                            CommentRow(comment)
                            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                        }
                    }
                }
                if (page > 1 || current?.list?.isNotEmpty() == true) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = !loading && page > 1) { Text(stringResource(R.string.comments_previous)) }
                        Text(stringResource(R.string.comments_page, current?.page ?: page, current?.total ?: 0),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        TextButton(onClick = { page++ }, enabled = !loading && current != null && current.list.isNotEmpty() && current.page.toLong() * current.limit < current.total) {
                            Text(stringResource(R.string.comments_next))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommentRow(comment: MusicComment, nested: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (nested) 8.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!nested) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Text(comment.userName.take(1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                comment.avatar?.let { coil.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.userName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                comment.likedCount?.let {
                    val description = stringResource(R.string.comments_likes, it)
                    Row(Modifier.padding(start = 8.dp).semantics(mergeDescendants = true) { contentDescription = description },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(java.text.NumberFormat.getIntegerInstance().format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val metadata = listOfNotNull(comment.time?.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }, comment.location)
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            SelectionContainer { Text(comment.text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
            comment.images.forEach { coil.compose.AsyncImage(it, stringResource(R.string.comments_image),
                Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp))) }
            if (comment.replies.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    comment.replies.forEach { CommentRow(it, nested = true) }
                }
            }
        }
    }
}
