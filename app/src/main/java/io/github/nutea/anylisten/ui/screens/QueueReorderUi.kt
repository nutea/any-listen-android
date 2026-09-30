package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.QueueSections
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.delay

internal class QueueDrag(private val list: LazyListState) {
    var key by mutableStateOf<String?>(null)
    private var center by mutableFloatStateOf(0f)
    var sections: QueueSections = QueueSections(null, emptyList(), emptyList())
    var move: (String, String) -> Unit = { _, _ -> }
    fun start(trackKey: String) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == trackKey } ?: return
        key = trackKey; center = item.offset + item.size / 2f
    }
    fun drag(delta: Float) { center += delta; moveUnderPointer() }
    fun stop() { key = null }
    private fun moveUnderPointer() {
        val from = key ?: return
        val group = if (sections.later.any { it.cacheKey == from }) sections.later else sections.rest
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }
        val to = target?.key as? String ?: return
        if (to != from && group.any { it.cacheKey == to }) move(from, to)
    }
    suspend fun scrollAtEdge() {
        val info = list.layoutInfo
        val top = info.viewportStartOffset + 56
        val bottom = info.viewportEndOffset - 56
        val scroll = when { center < top -> -12f; center > bottom -> 12f; else -> 0f }
        if (scroll != 0f) { list.scroll { scrollBy(scroll) }; moveUnderPointer() }
    }
    fun Modifier.rowModifier(trackKey: String): Modifier = this.zIndex(if (key == trackKey) 1f else 0f).graphicsLayer {
        translationY = if (key == trackKey) {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == trackKey }
            item?.let { center - it.offset - it.size / 2f } ?: 0f
        } else 0f
        alpha = if (key == trackKey) .88f else 1f
    }
}

@Composable
internal fun rememberQueueDrag(list: LazyListState, sections: QueueSections, move: (String, String) -> Unit): QueueDrag {
    val drag = remember(list) { QueueDrag(list) }
    SideEffect { drag.sections = sections; drag.move = move }
    LaunchedEffect(drag.key) { while (drag.key != null) { drag.scrollAtEdge(); delay(16) } }
    return drag
}

@Composable
internal fun QueueDragHandle(drag: QueueDrag, track: Track, group: List<Track>) {
    val index = group.indexOfFirst { it.cacheKey == track.cacheKey }
    val up = stringResource(R.string.queue_move_up)
    val down = stringResource(R.string.queue_move_down)
    Box(Modifier.size(48.dp).testTag("queue_drag_${track.identity.remoteTrackId}").semantics {
        customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction(up) { drag.move(track.cacheKey, group[index - 1].cacheKey); true })
            if (index < group.lastIndex) add(CustomAccessibilityAction(down) { drag.move(track.cacheKey, group[index + 1].cacheKey); true })
        }
    }.pointerInput(track.cacheKey) {
        detectDragGesturesAfterLongPress(onDragStart = { drag.start(track.cacheKey) },
            onDragEnd = drag::stop, onDragCancel = drag::stop,
            onDrag = { change, amount -> change.consume(); drag.drag(amount.y) })
    }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.DragHandle, stringResource(R.string.queue_reorder_track, track.title))
    }
}
