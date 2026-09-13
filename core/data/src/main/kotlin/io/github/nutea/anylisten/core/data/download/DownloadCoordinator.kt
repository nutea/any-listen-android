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
import io.github.nutea.anylisten.core.data.OfflineAssets
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.security.MessageDigest
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
    private val usableBytes: () -> Long,
    private val offlineAssets: OfflineAssets? = null,
    private val extrasWarning: () -> String = { "Audio saved; cover or lyrics could not be saved. Download again to retry." },
    private val streamingCacheDir: File? = null,
    private val resourceBytes: () -> Long = { 0L },
    private val repairWarning: () -> String = { "Legacy download identity is ambiguous. Download again to repair." },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(2)
    private val writeLock = Mutex()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun observe(): Flow<List<DownloadRecord>> = dao.observe().onStart { migrateLegacyFiles() }.map { list -> list.map { it.toModel() } }

    suspend fun enqueue(tracks: List<Track>) {
        migrateLegacyFiles()
        tracks.forEach { original ->
            val track = hydrate(original)
            if (jobs[track.cacheKey]?.isActive == true) return@forEach
            val existing = dao.find(track.cacheKey)?.toModel()
            if (existing?.status == DownloadStatus.COMPLETED && existing.filePath?.let(::File)?.exists() == true) {
                scope.launch {
                    val synced = offlineAssets?.cacheExtras(track) != false
                    update(track.cacheKey) { it.copy(error = if (synced) null else extrasWarning()) }
                }
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
            start(track)
        }
    }

    suspend fun pause(cacheKey: String) {
        cancelled.add(cacheKey)
        update(cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.PAUSE)) }
    }

    suspend fun cancel(cacheKey: String) {
        cancelled.add(cacheKey)
        update(cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.CANCEL)) }
        File(downloadsDir, safeName(cacheKey) + ".part").delete()
        File(downloadsDir, safeName(cacheKey) + ".part.validator").delete()
    }

    suspend fun resumePaused() {
        migrateLegacyFiles()
        if (!gateway.isOnline()) return
        dao.all().filter { it.status == DownloadStatus.PAUSED.name }.forEach { retry(it.cacheKey) }
    }

    suspend fun retry(cacheKey: String) {
        migrateLegacyFiles()
        val entity = dao.find(cacheKey) ?: return
        val original = entity.toModel()
        val track = hydrate(Track(identity = original.identity, title = entity.title, artist = entity.artist,
            album = "", durationMs = null, fingerprint = entity.fingerprint))
        if (track.cacheKey != cacheKey) {
            enqueue(listOf(track))
            removeTask(cacheKey)
            return
        }
        if (jobs[cacheKey]?.isActive == true) return
        cancelled.remove(cacheKey)
        val next = DownloadStateMachine.transition(original.status, DownloadEvent.RETRY)
        dao.upsert(entity.copy(status = next.name, error = null))
        start(track)
    }

    /** Resolve legacy media-session wrappers by exact stored key, never by song title. */
    private suspend fun hydrate(track: Track): Track {
        libraryDao.track(track.cacheKey)?.let { return it.toModel() }
        if (track.identity.serverProfileId == "session") {
            libraryDao.track(track.identity.remoteTrackId)?.let { return it.toModel() }
        }
        return track
    }

    private fun start(track: Track) {
        val job = scope.launch(start = CoroutineStart.LAZY) { run(track) }
        val previous = jobs.putIfAbsent(track.cacheKey, job)
        if (previous != null) { job.cancel(); return }
        job.invokeOnCompletion { jobs.remove(track.cacheKey, job) }
        job.start()
    }

    suspend fun removeTask(cacheKey: String) {
        migrateLegacyFiles()
        val record = dao.find(cacheKey)?.toModel() ?: return
        if (record.status !in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED)) return
        cancelled.add(cacheKey)
        jobs[cacheKey]?.cancelAndJoin()
        writeLock.withLock {
            File(downloadsDir, safeName(cacheKey) + ".part").delete()
        File(downloadsDir, safeName(cacheKey) + ".part.validator").delete()
            File(downloadsDir, safeName(cacheKey)).delete()
            dao.delete(cacheKey)
            lastProgressAt.remove(cacheKey)
        }
    }

    suspend fun deleteLocal(cacheKey: String) {
        migrateLegacyFiles()
        cancelled.add(cacheKey)
        jobs[cacheKey]?.cancelAndJoin()
        val entity = dao.find(cacheKey) ?: return
        entity.filePath?.let { File(it).delete() }
        File(downloadsDir, safeName(cacheKey)).delete()
        File(downloadsDir, safeName(cacheKey) + ".http.json").delete()
        File(downloadsDir, safeName(cacheKey) + ".part.validator").delete()
        dao.delete(cacheKey)
    }

    suspend fun completedFile(cacheKey: String): File? {
        migrateLegacyFiles()
        val record = dao.find(cacheKey)?.toModel() ?: return null
        val file = record.filePath?.let(::File)
        return if (DownloadStateMachine.canMarkOffline(record.status, file?.exists() == true)) file else null
    }

    /** Refresh an existing download without making it unavailable or interrupting its open reader. */
    fun refreshCompleted(track: Track, force: Boolean = false): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            limiter.withPermit {
                if (!gateway.isOnline()) return@withPermit
                val file = completedFile(track.cacheKey) ?: return@withPermit
                if (!downloader.revalidator.due(file,force)) return@withPermit
                try {
                    val full = hydrate(track)
                    val media = gateway.resolveMedia(full,refresh = true)
                    io.github.nutea.anylisten.core.data.gateway.UrlNormalizer.requireEncryptedOrLocal(media.url)
                    downloader.revalidator.update(media.url,file,force,canCommit = { !cancelled.contains(track.cacheKey) })
                    update(track.cacheKey) { it.copy(bytesDownloaded = file.length(),bytesTotal = file.length()) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the existing verified download. */ }
            }
        }
        val prior = jobs.putIfAbsent(track.cacheKey,job)
        if (prior != null) { job.cancel(); return prior }
        job.invokeOnCompletion { jobs.remove(track.cacheKey,job) }
        job.start()
        return job
    }

    suspend fun storage(): StorageSummary = withContext(Dispatchers.IO) {
        StorageSummary(
            downloadBytes = downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            cacheBytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                (offlineAssets?.audioBytes() ?: 0) +
                (streamingCacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0),
            usableBytes = usableBytes(),
            resourceBytes = resourceBytes(),
        )
    }

    private suspend fun run(track: Track) {
        limiter.withPermit {
            if (cancelled.contains(track.cacheKey)) return
            if (usableBytes() < MIN_FREE) {
                fail(track.cacheKey, AppError(ErrorKind.DISK_FULL, "Not enough disk space"))
                return
            }
            update(track.cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.START)) }
            try {
                val hydrated = hydrate(track)
                val dest = File(downloadsDir, safeName(track.cacheKey))
                val cached = offlineAssets?.audioFile(track.cacheKey)
                val result = if (cached != null) {
                    cached.copyTo(dest, overwrite = true)
                    DownloadResult(dest, dest.length(), io.github.nutea.anylisten.core.model.IntegrityKind.NO_REMOTE_CHECKSUM, false)
                } else downloader.download(
                    url = resolvePlayable(hydrated).url,
                    dest = dest,
                    onProgress = { downloaded, total ->
                        reportProgress(track.cacheKey, downloaded, total)
                    },
                    isCancelled = { cancelled.contains(track.cacheKey) },
                )
                update(track.cacheKey) { it.copy(status = DownloadStateMachine.transition(it.status, DownloadEvent.VERIFY)) }
                val extrasSynced = offlineAssets?.cacheExtras(hydrated) != false
                if (cancelled.contains(track.cacheKey)) return
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
                        error = if (extrasSynced) null else extrasWarning(),
                    )
                }
                lastProgressAt.remove(track.cacheKey)
            } catch (error: Throwable) {
                if (cancelled.contains(track.cacheKey)) return
                if (!gateway.isOnline()) {
                    update(track.cacheKey) { it.copy(status = DownloadStatus.PAUSED, error = error.message) }
                    return
                }
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

    private fun safeName(cacheKey: String): String = downloadFileName(cacheKey)

    private val migrationLock = Mutex()
    @Volatile private var migrated = false

    /** Run before exposing or using old files. Never guess which song a shared file contains. */
    suspend fun migrateLegacyFiles() = withContext(Dispatchers.IO) {
        migrationLock.withLock {
            if (migrated) return@withLock
            val records = dao.all()
            val groups = records.groupBy { legacyName(it.cacheKey) }
            for ((name, group) in groups) {
                val old = File(downloadsDir, name)
                val legacy = group.filter { it.filePath == old.absolutePath }
                for (record in legacy) {
                    val dest = File(downloadsDir, safeName(record.cacheKey))
                    if (group.size == 1 && record.status == DownloadStatus.COMPLETED.name &&
                        old.isFile && old.length() > 0 && old.length() == record.bytesDownloaded) {
                        old.copyTo(dest, overwrite = true)
                        dao.upsert(record.copy(filePath = dest.absolutePath))
                    } else {
                        dao.upsert(record.copy(status = DownloadStatus.PAUSED.name, filePath = null,
                            bytesDownloaded = 0, bytesTotal = null, integrity = null, error = repairWarning()))
                    }
                }
                // A cancelled/failed legacy partial cannot safely be resumed into a new identity.
                File(downloadsDir, name + ".part").delete()
                if (legacy.isNotEmpty()) old.delete()
            }
            migrated = true
        }
    }

    private fun legacyName(cacheKey: String) = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val MIN_FREE = 8L * 1024 * 1024
    }
}

/** Lossless identity mapping for Unicode paths and long server keys. */
internal fun downloadFileName(cacheKey: String): String = MessageDigest.getInstance("SHA-256")
    .digest(cacheKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
