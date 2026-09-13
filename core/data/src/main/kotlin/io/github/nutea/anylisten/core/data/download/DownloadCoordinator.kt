package io.github.nutea.anylisten.core.data.download

import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.local.DownloadDao
import io.github.nutea.anylisten.core.data.local.LibraryDao
import io.github.nutea.anylisten.core.data.local.DownloadEntity
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.DownloadEvent
import io.github.nutea.anylisten.core.model.DownloadRecord
import io.github.nutea.anylisten.core.model.DownloadStateMachine
import io.github.nutea.anylisten.core.model.DownloadStatus
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.StorageSummary
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadCoordinator(
    private val dao: DownloadDao,
    private val libraryDao: LibraryDao,
    private val gateway: AnyListenGateway,
    private val downloader: FileDownloader,
    private val downloadsDir: File,
    private val cacheDir: File,
    private val wifiOnly: () -> Boolean,
    private val onWifi: () -> Boolean,
    private val usableBytes: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(2)
    private val writeLock = Mutex()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()
    private val cancelled = mutableSetOf<String>()

    fun observe(): Flow<List<DownloadRecord>> = dao.observe().map { list -> list.map { it.toModel() } }

    suspend fun enqueue(tracks: List<Track>) {
        tracks.forEach { track ->
            val existing = dao.find(track.cacheKey)?.toModel()
            if (existing?.status == DownloadStatus.COMPLETED && existing.filePath?.let(::File)?.exists() == true) {
                return@forEach
            }
            val record = DownloadRecord(
                cacheKey = track.cacheKey,
                identity = track.identity,
                title = track.title,
                artist = track.artist,
                status = DownloadStatus.QUEUED,
                fingerprint = track.fingerprint,
            )
            dao.upsert(DownloadEntity.from(record))
            cancelled.remove(track.cacheKey)
            scope.launch { run(track) }
        }
    }

    suspend fun pause(cacheKey: String) {
        cancelled.add(cacheKey)
        update(cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.PAUSE)) }
    }

    suspend fun cancel(cacheKey: String) {
        cancelled.add(cacheKey)
        update(cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.CANCEL)) }
        File(downloadsDir, "$cacheKey.part").delete()
    }

    suspend fun resumePaused() {
        if (wifiOnly() && !onWifi()) return
        dao.all().filter { it.status == DownloadStatus.PAUSED.name }.forEach { retry(it.cacheKey) }
    }

    suspend fun retry(cacheKey: String) {
        val entity = dao.find(cacheKey) ?: return
        cancelled.remove(cacheKey)
        val next = DownloadStateMachine.transition(entity.toModel().status, DownloadEvent.RETRY)
        dao.upsert(entity.copy(status = next.name, error = null))
        val track = Track(
            identity = entity.toModel().identity,
            title = entity.title,
            artist = entity.artist,
            album = "",
            durationMs = null,
            fingerprint = entity.fingerprint,
        )
        scope.launch { run(track) }
    }

    suspend fun deleteLocal(cacheKey: String) {
        val entity = dao.find(cacheKey) ?: return
        entity.filePath?.let { File(it).delete() }
        File(downloadsDir, cacheKey).delete()
        dao.delete(cacheKey)
    }

    suspend fun completedFile(cacheKey: String): File? {
        val record = dao.find(cacheKey)?.toModel() ?: return null
        val file = record.filePath?.let(::File)
        return if (DownloadStateMachine.canMarkOffline(record.status, file?.exists() == true)) file else null
    }

    suspend fun storage(): StorageSummary = withContext(Dispatchers.IO) {
        StorageSummary(
            downloadBytes = downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            cacheBytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            usableBytes = usableBytes(),
        )
    }

    private suspend fun run(track: Track) {
        limiter.withPermit {
            if (cancelled.contains(track.cacheKey)) return
            if (wifiOnly() && !onWifi()) {
                update(track.cacheKey) { it.copy(status = DownloadStatus.PAUSED, error = "Waiting for Wi-Fi") }
                return
            }
            if (usableBytes() < MIN_FREE) {
                fail(track.cacheKey, AppError(ErrorKind.DISK_FULL, "Not enough disk space"))
                return
            }
            update(track.cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.START)) }
            try {
                val hydrated = libraryDao.track(track.cacheKey)?.toModel() ?: track
                val media = resolvePlayable(hydrated)
                val dest = File(downloadsDir, safeName(track.cacheKey))
                val result = downloader.download(
                    url = media.url,
                    dest = dest,
                    onProgress = { downloaded, total ->
                        reportProgress(track.cacheKey, downloaded, total)
                    },
                    isCancelled = { cancelled.contains(track.cacheKey) },
                )
                update(track.cacheKey) { current ->
                    val verified = current.copy(
                        status = DownloadStateMachine.transition(current.status, DownloadEvent.VERIFY),
                    )
                    if (result.file.length() <= 0L) {
                        throw AppError(ErrorKind.INTEGRITY_FAILED, "Empty file")
                    }
                    verified.copy(
                        status = DownloadStateMachine.transition(verified.status, DownloadEvent.COMPLETE),
                        filePath = result.file.absolutePath,
                        bytesDownloaded = result.bytes,
                        bytesTotal = result.bytes,
                        integrity = result.integrity,
                        error = null,
                    )
                }
                lastProgressAt.remove(track.cacheKey)
            } catch (error: Throwable) {
                if (cancelled.contains(track.cacheKey)) return
                fail(track.cacheKey, if (error is AppError) error else AppError.fromThrowable(error))
            }
        }
    }

    private suspend fun resolvePlayable(track: Track) = try {
        gateway.resolveMedia(track, refresh = false)
    } catch (error: Throwable) {
        if (track.isLocalOnServer || looksLocal(track)) throw error
        gateway.resolveMedia(track, refresh = true)
    }

    private fun looksLocal(track: Track): Boolean =
        track.rawJson?.contains("\"isLocal\":true") == true ||
            track.rawJson?.contains("\"isLocal\": true") == true

    private fun reportProgress(cacheKey: String, downloaded: Long, total: Long?) {
        val done = total != null && total > 0 && downloaded >= total
        val now = System.currentTimeMillis()
        val last = lastProgressAt[cacheKey] ?: 0L
        if (!done && now - last < 200L) return
        lastProgressAt[cacheKey] = now
        runBlocking {
            update(cacheKey) { current ->
                if (current.status != DownloadStatus.DOWNLOADING) current
                else current.copy(bytesDownloaded = downloaded, bytesTotal = total)
            }
        }
    }

    private suspend fun fail(cacheKey: String, error: AppError) {
        update(cacheKey) {
            it.copy(
                status = DownloadStateMachine.transition(it.status, DownloadEvent.FAIL),
                error = error.message,
            )
        }
    }

    private suspend fun update(cacheKey: String, transform: (DownloadRecord) -> DownloadRecord) {
        writeLock.withLock {
            val current = dao.find(cacheKey)?.toModel() ?: return
            dao.upsert(DownloadEntity.from(transform(current)))
        }
    }

    private fun safeName(cacheKey: String): String = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val MIN_FREE = 8L * 1024 * 1024
    }
}
