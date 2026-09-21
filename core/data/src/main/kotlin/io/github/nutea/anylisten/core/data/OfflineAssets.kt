package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.model.AssetCompleteness
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.OfflineCatalogEntry
import io.github.nutea.anylisten.core.model.SidecarState
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Complete audio and sidecar resources, independent of ExoPlayer's partial stream cache. */
class OfflineAssets(
    private val directory: File,
    private val gateway: AnyListenGateway,
    private val downloader: FileDownloader,
    private val artwork: ArtworkStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: () -> String,
) {
    private val changes = MutableStateFlow(0L)
    val updates = changes.asStateFlow()
    private val coverJobs = ConcurrentHashMap<String, Job>()
    private val coverAttempts = ConcurrentHashMap<String, Long>()
    private val coverLimit = Semaphore(2)
    private val sidecarFailures = ConcurrentHashMap<String, Long>()

    /** Resolve visible missing artwork independently of playing/downloading the track. */
    fun requestCover(track: Track): Job? {
        if (!gateway.isOnline()) return null
        return synchronized(coverJobs) {
            coverJobs[track.cacheKey]?.takeIf { it.isActive }?.let { return@synchronized it }
            val now = System.currentTimeMillis()
            if (now - (coverAttempts[track.cacheKey] ?: 0L) in 0 until 60_000L) return@synchronized null
            coverAttempts[track.cacheKey] = now
            val job = scope.launch(start = CoroutineStart.LAZY) {
                coverLimit.withPermit {
                    if (gateway.isOnline()) attempt { cacheCover(track) }
                }
            }
            coverJobs[track.cacheKey] = job
            job.invokeOnCompletion { coverJobs.remove(track.cacheKey, job) }
            job.start()
            job
        }
    }

    suspend fun cacheCover(track: Track, force: Boolean = false) = withContext(Dispatchers.IO) { persistCover(track, force) }

    private val lyricJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val extraJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var automaticAudioCaching = true
    private val audioLimit = Semaphore(2)
    private val audioLocks = Array(32) { Mutex() }
    private val lyricLocks = Array(32) { Mutex() }
    private val coverLocks = Array(32) { Mutex() }
    private val catalogLock = Any()
    private val streamLocks = Array(32) { Any() }
    private val streamAssemblers = ConcurrentHashMap<String, StreamCacheAssembler>()
    private val streamEpoch = java.util.concurrent.atomic.AtomicLong()
    fun streamToken(): Long = streamEpoch.get()

    /**
     * Record playback bytes at their file offsets. Safe to call from a Media3 loader thread.
     * A completed file is published synchronously from [StreamCacheSink.close] so the cache
     * index exists before the player reports the next state.
     */
    fun openStreamSink(track: Track, url: String, position: Long, openedLength: Long, token: Long = streamEpoch.get()): StreamCacheSink? {
        if (!automaticAudioCaching || token != streamEpoch.get()) return null
        val gate = streamLocks[(track.cacheKey.hashCode() and Int.MAX_VALUE) % streamLocks.size]
        synchronized(gate) {
            if (!automaticAudioCaching || token != streamEpoch.get()) return null
            if (audioFile(track.cacheKey) != null) return null
            val assembler = streamAssemblers.getOrPut(track.cacheKey) {
                StreamCacheAssembler(
                    part = file(track.cacheKey, ".stream.part"),
                    meta = file(track.cacheKey, ".stream.ranges"),
                    complete = file(track.cacheKey, ".audio"),
                )
            }
            assembler.opened(position, openedLength)
            return object : StreamCacheSink {
                override fun write(position: Long, buffer: ByteArray, offset: Int, count: Int) {
                    synchronized(gate) {
                        if (!automaticAudioCaching || token != streamEpoch.get() || audioFile(track.cacheKey) != null) return
                        assembler.write(position, buffer, offset, count)
                    }
                }

                override fun close(endOfInput: Boolean) {
                    synchronized(gate) {
                        // Cache clearing already invalidated the old recording. A late close
                        // must not delete paths now owned by a new recording of the same song.
                        if (token != streamEpoch.get()) return
                        if (audioFile(track.cacheKey) != null) {
                            assembler.discard()
                            streamAssemblers.remove(track.cacheKey, assembler)
                            return
                        }
                        if (!automaticAudioCaching) return
                        if (!assembler.finish(endOfInput)) return
                        streamAssemblers.remove(track.cacheKey, assembler)
                        val destination = file(track.cacheKey, ".audio")
                        if (!destination.isFile || destination.length() == 0L) return
                        runCatching { downloader.revalidator.recordDownloaded(url, destination, null, null) }
                        remember(track)
                        changes.update { it + 1 }
                    }
                }
            }
        }
    }

    /** Adopt bytes already consumed by playback; never fetch the resource a second time. */
    suspend fun adoptStream(track: Track, url: String, temporary: File, token: Long) = withContext(Dispatchers.IO) {
        try {
            if (!automaticAudioCaching || token != streamEpoch.get() || !temporary.isFile || temporary.length() == 0L) return@withContext
            val sink = openStreamSink(track, url, 0L, temporary.length(), token) ?: return@withContext
            try {
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(16_384)
                    var position = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(position, buffer, 0, count)
                        position += count
                    }
                }
                sink.close(endOfInput = true)
            } catch (error: Exception) {
                runCatching { sink.close(endOfInput = false) }
                throw error
            }
        } finally { temporary.delete() }
    }
    private val catalogFile = File(directory, "catalog.json")

    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun file(cacheKey: String, suffix: String) = File(directory, key(cacheKey) + suffix)
    private fun lock(locks: Array<Mutex>, cacheKey: String) = locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size]

    fun audioFile(cacheKey: String): File? = file(cacheKey, ".audio").takeIf { it.isFile && it.length() > 0 }
    private val coverUrls = ConcurrentHashMap<String, String>()
    fun peekCoverUrl(cacheKey: String): String? = coverUrls[cacheKey]?.takeIf { it.isNotBlank() }
    fun savedCoverUrl(cacheKey: String): String? = coverUrls.getOrPut(cacheKey) {
        runCatching { file(cacheKey, ".cover").takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
    }.takeIf { it.isNotBlank() }

    fun inspect(cacheKey: String): AssetCompleteness = AssetCompleteness(
        audioReady = audioFile(cacheKey) != null,
        lyrics = sidecarState(cacheKey, ".lrc"),
        cover = sidecarState(cacheKey, ".cover"),
    )

    fun catalog(): List<OfflineCatalogEntry> = synchronized(catalogLock) { readCatalog() }

    fun remember(track: Track) = synchronized(catalogLock) {
        val items = readCatalog().toMutableList()
        items.removeAll { it.cacheKey == track.cacheKey }
        items.add(OfflineCatalogEntry.from(track))
        writeCatalog(items)
    }

    fun forget(cacheKey: String) = synchronized(catalogLock) {
        writeCatalog(readCatalog().filter { it.cacheKey != cacheKey })
    }

    fun cachedLyrics(track: Track): Lyrics? = file(track.cacheKey, ".lrc").takeIf { it.isFile && it.length() > 0 }?.let {
        fun extra(suffix: String) = runCatching { file(track.cacheKey, suffix).readText() }.getOrNull()
        runCatching { LrcParser.parse(it.readText(), extra(".tlrc"), extra(".rlrc"), extra(".awlrc")) }.getOrNull()
    }

    suspend fun lyrics(track: Track, force: Boolean = false): Lyrics = withContext(Dispatchers.IO) {
        val saved = cachedLyrics(track)
        val absent = file(track.cacheKey, ".lrc.none").isFile
        if (!force && gateway.isOnline() && (saved != null || absent)) {
            scheduleLyricRevalidate(track)
            return@withContext saved ?: Lyrics(emptyList(), "")
        }
        if (!gateway.isOnline() && saved != null) return@withContext saved
        if (!gateway.isOnline() && absent) return@withContext Lyrics(emptyList(),"")
        refreshLyrics(track, force, skipTtl = false)
    }

    /**
     * Web playback calls getMusicLyric on every track change and omits isRefresh.
     * Show the sidecar immediately and refresh in the background; only coalesce
     * the duplicate player + cachePlayed calls that share one play start.
     */
    private fun scheduleLyricRevalidate(track: Track) {
        synchronized(lyricJobs) {
            lyricJobs[track.cacheKey]?.takeIf { it.isActive }?.let { return }
            val job = scope.launch { attempt { refreshLyrics(track, force = false, skipTtl = true) } }
            lyricJobs[track.cacheKey] = job
            job.invokeOnCompletion { lyricJobs.remove(track.cacheKey, job) }
        }
    }

    private suspend fun refreshLyrics(track: Track, force: Boolean, skipTtl: Boolean): Lyrics =
        lock(lyricLocks,track.cacheKey).withLock {
        val saved = cachedLyrics(track)
        val (checked, _) = readLyricCheck(track.cacheKey)
        val absent = file(track.cacheKey, ".lrc.none").isFile
        val interval = if (skipTtl) LYRIC_PLAY_REVALIDATE_MS else CACHE_CHECK_INTERVAL_MS
        if (!force && (saved != null || absent) && now() - checked in 0 until interval)
            return@withLock saved ?: Lyrics(emptyList(), "")
        if (!force && recentlyFailed(track.cacheKey + ".lrc")) {
            return@withLock saved ?: throw java.io.IOException("Lyrics retry deferred")
        }
        try {
            val fetched = withTimeout(20_000L) { gateway.resolveLyrics(track) }
            val previous = saved?.let(::lyricRevision)
            sidecarFailures.remove(track.cacheKey + ".lrc")
            persistLyrics(track.cacheKey,fetched,failed = false)
            writeLyricCheck(track.cacheKey, lyricRevision(fetched))
            if (previous != lyricRevision(fetched)) changes.update { it + 1 }
            fetched
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                sidecarFailures[track.cacheKey + ".lrc"] = System.currentTimeMillis()
                if (saved != null) return@withLock saved
            }
            throw cancelled
        } catch (error: Exception) {
            sidecarFailures[track.cacheKey + ".lrc"] = System.currentTimeMillis()
            if (saved != null) return@withLock saved
            persistLyrics(track.cacheKey,null,failed = true)
            throw error
        }
    }

    private fun lyricRevision(lyrics: Lyrics): String {
        val parts = listOf(lyrics.toTimedLrc(), lyrics.translationRaw, lyrics.romanizationRaw, lyrics.karaokeRaw)
        return if (parts.all { it.isBlank() }) "none" else key(parts.joinToString("\u0000"))
    }

    private fun readLyricCheck(cacheKey: String): Pair<Long, String> {
        val raw = runCatching { file(cacheKey, ".lrc.checked").readText() }.getOrDefault("")
        val lines = raw.split('\n', limit = 2)
        val checked = lines.getOrNull(0)?.toLongOrNull() ?: 0L
        val revision = lines.getOrNull(1).orEmpty()
        return checked to revision
    }

    private fun writeLyricCheck(cacheKey: String, revision: String) {
        writeAtomic(file(cacheKey, ".lrc.checked"), "${now()}\n$revision")
    }

    /** Missing server metadata is valid; failed requests can be retried separately from audio. */
    suspend fun cacheExtras(track: Track, force: Boolean = false): Boolean = supervisorScope {
        remember(track)
        val lyric = async { attempt { lyrics(track,force) } }
        val cover = async(Dispatchers.IO) { attempt { persistCover(track,force) } }
        lyric.await() && cover.await()
    }

    suspend fun cacheAudio(track: Track, force: Boolean = false, existingOnly: Boolean = false): File = withContext(Dispatchers.IO) {
        lock(audioLocks, track.cacheKey).withLock {
            audioFile(track.cacheKey)?.let { saved ->
                if (gateway.isOnline() && downloader.revalidator.due(saved,force)) {
                    try {
                        audioLimit.withPermit {
                            val media = withTimeout(30_000L) { gateway.resolveMedia(track,refresh = true) }
                            UrlNormalizer.requireEncryptedOrLocal(media.url)
                            downloader.revalidator.update(media.url,saved,force)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep complete cached audio on validation failure. */ }
                }
                remember(track)
                return@withLock saved
            }
            if (existingOnly) throw java.io.IOException("Cached audio was removed")
            audioLimit.withPermit {
                val media = withTimeout(30_000L) { gateway.resolveMedia(track, refresh = false) }
                val context = currentCoroutineContext()
                val saved = downloader.download(media.url, file(track.cacheKey, ".audio"),
                    isCancelled = { !context.isActive }).file
                remember(track)
                saved
            }
        }
    }

    fun cachePlayed(track: Track, audioAlreadyDownloaded: Boolean = false, autoCacheAudio: Boolean = true, streaming: Boolean = false) {
        synchronized(extraJobs) {
            if (extraJobs[track.cacheKey]?.isActive != true) {
                extraJobs[track.cacheKey] = scope.launch { attempt { cacheExtras(track) } }
            }
        }
        if (streaming) return
        synchronized(jobs) {
            if (audioAlreadyDownloaded || jobs[track.cacheKey]?.isActive == true) return
            if ((!automaticAudioCaching || !autoCacheAudio) && audioFile(track.cacheKey) == null) return
            jobs[track.cacheKey] = scope.launch { attempt { cacheAudio(track) } }
        }
    }

    suspend fun setAutomaticAudioCaching(enabled: Boolean) {
        val pending = synchronized(jobs) {
            automaticAudioCaching = enabled
            if (enabled) emptyList() else jobs.values.toList().also { jobs.clear() }
        }
        pending.forEach { it.cancel() }
        pending.joinAll()
    }

    suspend fun clearAudio() = withContext(Dispatchers.IO) {
        streamEpoch.incrementAndGet()
        streamAssemblers.values.forEach { it.discard() }
        streamAssemblers.clear()
        val pending = synchronized(jobs) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach { it.cancel() }
        pending.joinAll()
        directory.listFiles().orEmpty().filter {
            it.name.endsWith(".audio") || it.name.endsWith(".audio.part") ||
                it.name.endsWith(".stream.part") || it.name.endsWith(".stream.ranges")
        }
            .forEach { if (it.name.endsWith(".audio")) downloader.revalidator.remove(it) else it.delete() }
    }

    suspend fun clearTrack(cacheKey: String) = withContext(Dispatchers.IO) {
        streamEpoch.incrementAndGet()
        streamAssemblers.remove(cacheKey)?.discard()
        synchronized(jobs) { jobs.remove(cacheKey) }?.cancelAndJoin()
        synchronized(extraJobs) { extraJobs.remove(cacheKey) }?.cancelAndJoin()
        synchronized(lyricJobs) { lyricJobs.remove(cacheKey) }?.cancelAndJoin()
        synchronized(coverJobs) { coverJobs.remove(cacheKey) }?.cancelAndJoin()
        lock(audioLocks,cacheKey).withLock { lock(lyricLocks,cacheKey).withLock { lock(coverLocks,cacheKey).withLock {
        downloader.revalidator.remove(file(cacheKey,".audio"))
        listOf(".audio", ".audio.part", ".audio.http.json", ".stream.part", ".stream.ranges", ".rlrc", ".awlrc", ".tlrc", ".lrc.checked", ".cover.checked", ".cover.source", ".lrc", ".lrc.none", ".lrc.fail", ".cover", ".cover.none", ".cover.fail")
            .forEach { file(cacheKey, it).delete() }
        coverUrls.remove(cacheKey)
        sidecarFailures.remove(cacheKey + ".lrc")
        sidecarFailures.remove(cacheKey + ".cover")
        coverAttempts.remove(cacheKey)
        forget(cacheKey)
        } } }
    }

    fun audioBytes(): Long = directory.listFiles().orEmpty()
        .filter {
            it.name.endsWith(".audio") || it.name.endsWith(".audio.part") || it.name.endsWith(".stream.part")
        }.sumOf { it.length() }

    fun resourceBytes(): Long = directory.listFiles().orEmpty()
        .filter { file -> RESOURCE_SUFFIXES.any { file.name.endsWith(it) } }.sumOf { it.length() }

    private fun sidecarState(cacheKey: String, readySuffix: String): SidecarState = when {
        file(cacheKey, readySuffix).isFile && file(cacheKey, readySuffix).length() > 0 -> SidecarState.READY
        file(cacheKey, "$readySuffix.none").isFile -> SidecarState.NONE
        file(cacheKey, "$readySuffix.fail").isFile -> SidecarState.FAILED
        else -> SidecarState.UNKNOWN
    }

    private fun persistLyrics(cacheKey: String, lyrics: Lyrics?, failed: Boolean) {
        when {
            failed -> writeMarker(cacheKey, ".lrc.fail", keepReady = ".lrc")
            lyrics.hasContent() -> {
                writeAtomic(file(cacheKey, ".lrc"), lyrics!!.toTimedLrc())
                for ((suffix, raw) in listOf(".tlrc" to lyrics.translationRaw, ".rlrc" to lyrics.romanizationRaw, ".awlrc" to lyrics.karaokeRaw)) {
                    if (raw.isNotBlank()) writeAtomic(file(cacheKey, suffix), raw) else file(cacheKey, suffix).delete()
                }
                file(cacheKey, ".lrc.none").delete()
                file(cacheKey, ".lrc.fail").delete()
            }
            else -> {
                writeAtomic(file(cacheKey, ".lrc.none"), "")
                file(cacheKey, ".lrc").delete()
                listOf(".tlrc", ".rlrc", ".awlrc").forEach { file(cacheKey, it).delete() }
                file(cacheKey, ".lrc.fail").delete()
            }
        }
    }

    private suspend fun persistCover(track: Track, force: Boolean = false) = lock(coverLocks,track.cacheKey).withLock {
        try {
            val previous = savedCoverUrl(track.cacheKey)
            if (previous != null && !gateway.isOnline()) return@withLock
            val checked = file(track.cacheKey,".cover.checked").let { runCatching { it.readText().toLong() }.getOrDefault(0) }
            val source = track.coverUrl?.takeIf { it.isNotBlank() }?.let { UrlNormalizer.resolveArtwork(baseUrl(), it) }.orEmpty()
            val savedSource = file(track.cacheKey, ".cover.source").let { runCatching { it.readText() }.getOrNull() }
            if (!force && (previous != null || file(track.cacheKey, ".cover.none").isFile) && (source == savedSource || (previous != null && source == previous)) &&
                System.currentTimeMillis() - checked in 0 until CACHE_CHECK_INTERVAL_MS) return@withLock
            if (!force && recentlyFailed(track.cacheKey + ".cover")) throw java.io.IOException("Cover retry deferred")
            val raw = if (gateway.isOnline()) withTimeout(20_000L) { gateway.resolveCover(track) }
                ?: track.coverUrl?.takeIf { it.isNotBlank() } ?: previous
                else previous ?: track.coverUrl?.takeIf { it.isNotBlank() } ?: throw java.io.IOException("Cover unavailable offline")
            if (raw.isNullOrBlank()) {
                writeAtomic(file(track.cacheKey, ".cover.source"), source)
                writeAtomic(file(track.cacheKey, ".cover.checked"), System.currentTimeMillis().toString())
                sidecarFailures.remove(track.cacheKey + ".cover")
                writeAtomic(file(track.cacheKey, ".cover.none"), "")
                file(track.cacheKey, ".cover").delete()
                coverUrls.remove(track.cacheKey)
                file(track.cacheKey, ".cover.fail").delete()
                return@withLock
            }
            val url = UrlNormalizer.resolveArtwork(baseUrl(), raw)
            artwork.get(url,force)
            writeAtomic(file(track.cacheKey, ".cover"), url)
            coverUrls[track.cacheKey] = url
            writeAtomic(file(track.cacheKey, ".cover.source"), source)
            sidecarFailures.remove(track.cacheKey + ".cover")
            writeAtomic(file(track.cacheKey,".cover.checked"),System.currentTimeMillis().toString())
            if (previous != url) changes.update { it + 1 }
            file(track.cacheKey, ".cover.none").delete()
            file(track.cacheKey, ".cover.fail").delete()
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                sidecarFailures[track.cacheKey + ".cover"] = System.currentTimeMillis()
                writeMarker(track.cacheKey, ".cover.fail", keepReady = ".cover")
            }
            throw cancelled
        } catch (error: Exception) {
            sidecarFailures[track.cacheKey + ".cover"] = System.currentTimeMillis()
            writeMarker(track.cacheKey, ".cover.fail", keepReady = ".cover")
            throw error
        }
    }

    private fun writeMarker(cacheKey: String, suffix: String, keepReady: String) {
        writeAtomic(file(cacheKey, suffix), "")
        if (!file(cacheKey, keepReady).isFile) file(cacheKey, keepReady).delete()
        file(cacheKey, "$keepReady.none").delete()
    }

    private fun Lyrics?.hasContent(): Boolean =
        this != null && (lines.any { it.text.isNotBlank() } || raw.isNotBlank())

    private fun readCatalog(): List<OfflineCatalogEntry> {
        val raw = catalogFile.takeIf { it.isFile }?.readText().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                OfflineCatalogEntry(
                    cacheKey = item.getString("cacheKey"),
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() },
                    serverProfileId = item.optString("serverProfileId"),
                    remoteTrackId = item.optString("remoteTrackId"),
                    fingerprint = item.optString("fingerprint").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCatalog(items: List<OfflineCatalogEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(JSONObject().apply {
                put("cacheKey", entry.cacheKey)
                put("title", entry.title)
                put("artist", entry.artist)
                put("album", entry.album)
                put("coverUrl", entry.coverUrl.orEmpty())
                put("serverProfileId", entry.serverProfileId)
                put("remoteTrackId", entry.remoteTrackId)
                put("fingerprint", entry.fingerprint.orEmpty())
            })
        }
        writeAtomic(catalogFile, array.toString())
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) false else throw cancelled
    } catch (_: Exception) { false }

    private fun recentlyFailed(key: String): Boolean =
        sidecarFailures[key]?.let { System.currentTimeMillis() - it in 0 until 60_000L } == true

    private fun writeAtomic(destination: File, text: String) {
        if (destination.isFile && destination.readText() == text) return
        directory.mkdirs()
        val temporary = File.createTempFile("sidecar-", ".part", directory)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private companion object {
        val RESOURCE_SUFFIXES = listOf(".rlrc", ".awlrc", ".tlrc", ".lrc", ".lrc.none", ".lrc.fail", ".cover", ".cover.none", ".cover.fail")
    }
}
