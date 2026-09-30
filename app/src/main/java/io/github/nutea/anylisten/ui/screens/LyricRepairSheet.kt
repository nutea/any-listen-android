package io.github.nutea.anylisten.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.data.gateway.*
import kotlinx.coroutines.*

internal data class LyricRepairActions(
    val service: LyricRepair, val hasLocal: () -> Boolean,
    val apply: suspend (Lyrics, Boolean) -> Unit, val reset: suspend (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LyricRepairSheet(track: Track, actions: LyricRepairActions, offline: Boolean, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sources by remember { mutableStateOf(emptyList<LyricSource>()) }
    var source by remember { mutableStateOf<LyricSource?>(null) }
    var searched by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<LyricCandidate>()) }
    var preview by remember { mutableStateOf<Lyrics?>(null) }
    var local by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingSources by remember { mutableStateOf(false) }
    val working = busy || loadingSources
    var error by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    fun run(allowSourceLoading: Boolean = false, block: suspend () -> Unit) {
        if (busy || (loadingSources && !allowSourceLoading)) return
        busy = true; error = false; applied = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(offline) {
        local = withContext(Dispatchers.IO) { actions.hasLocal() }
        if (!offline) {
            loadingSources = true
            try { sources = actions.service.sources(); source = sources.firstOrNull() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
            finally { loadingSources = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run(allowSourceLoading = true) {
            val lyrics = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(LyricImport::read) ?: error("Missing document")
            }
            preview = lyrics
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!working) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp).testTag("lyric_repair_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            key("heading") {
                Column {
                    Text(stringResource(R.string.lyric_repair), style = MaterialTheme.typography.titleLarge)
                    Text(track.title + " · " + track.artist, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.lyric_repair_scope), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (working) key("progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error) key("error") { Text(stringResource(R.string.lyric_repair_failed), color = MaterialTheme.colorScheme.error) }
            applied?.let { key("status") { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) } }
            key("import") {
                OutlinedButton({ importer.launch(arrayOf("*/*")) }, enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("lyric_import")) { Text(stringResource(R.string.lyric_import_lrc)) }
            }
            if (!offline) {
                key("name") { OutlinedTextField(name, { name = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyric_search_title)) }, singleLine = true, enabled = !working) }
                key("artist") { OutlinedTextField(artist, { artist = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyric_search_artist)) }, singleLine = true, enabled = !working) }
                key("sources") {
                    Column {
                        if (sources.isEmpty() && !working) Text(stringResource(R.string.lyric_sources_empty), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sources.forEach { option -> FilterChip(source == option, { source = option; candidates = emptyList(); searched = false; preview = null },
                                enabled = !working, label = { Text(option.name) }) }
                        }
                    }
                }
                key("search") {
                    Button({ source?.let { chosen -> run { candidates = actions.service.search(chosen, name, artist); searched = true; preview = null } } },
                        enabled = !working && source != null && name.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("lyric_search")) { Text(stringResource(R.string.lyric_search_candidates)) }
                }
            }
            if (searched && candidates.isEmpty() && !working) key("no-results") { Text(stringResource(R.string.lyric_candidates_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            candidates.forEach { candidate ->
                key("candidate_${candidate.id}") {
                Surface(Modifier.fillMaxWidth().clickable(enabled = !working) { run { preview = actions.service.detail(candidate) } },
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(14.dp)) { Text(candidate.title); Text(candidate.artist, style = MaterialTheme.typography.bodySmall) }
                }
                }
            }
            preview?.let { lyrics ->
                key("preview-title") { Text(stringResource(R.string.lyric_preview), style = MaterialTheme.typography.titleMedium) }
                key("preview") { Text(lyrics.lines.take(10).joinToString("\n") { it.text }.ifBlank { lyrics.raw.take(2000) }, Modifier.testTag("lyric_preview"), style = MaterialTheme.typography.bodyMedium) }
                key("apply-local") {
                    Button({ run { actions.apply(lyrics, false); local = true; applied = R.string.lyric_applied_local } }, enabled = !working && lyrics.raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("lyric_apply_local")) { Text(stringResource(R.string.lyric_apply_local)) }
                }
                key("save-server") {
                    OutlinedButton({ run { actions.apply(lyrics, true); local = false; applied = R.string.lyric_saved_server } }, enabled = !working && !offline && lyrics.raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("lyric_save_server")) { Text(stringResource(R.string.lyric_save_server)) }
                }
            }
            key("reset-local") {
                TextButton({ run { actions.reset(false); local = false; applied = R.string.lyric_restored } }, enabled = local && !working,
                    modifier = Modifier.testTag("lyric_reset_local")) { Text(stringResource(R.string.lyric_reset_local)) }
            }
            key("reset-server") {
                TextButton({ run { actions.reset(true); local = false; applied = R.string.lyric_restored } }, enabled = !offline && !working) {
                    Text(stringResource(R.string.lyric_reset_server))
                }
            }
            key("footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}