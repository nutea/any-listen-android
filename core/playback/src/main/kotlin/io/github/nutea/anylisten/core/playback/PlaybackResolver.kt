package io.github.nutea.anylisten.core.playback

import android.net.Uri
import io.github.nutea.anylisten.core.data.download.DownloadCoordinator
import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.data.OfflineAssets
import java.util.concurrent.ConcurrentHashMap

class PlaybackResolver(
    private val downloads: DownloadCoordinator,
    private val gateway: AnyListenGateway,
    private val offlineAssets: OfflineAssets,
) {
    private val remoteUrls = ConcurrentHashMap<String, Pair<String, Long>>()
    private val streamSession = java.util.UUID.randomUUID().toString()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    private val refreshKeys = ConcurrentHashMap.newKeySet<String>()
    private val resolutions = ConcurrentHashMap<String, io.github.nutea.anylisten.core.data.repo.SingleFlight<MediaResource>>()
    fun invalidateRemoteUrls(key: String? = null) {
        if (key != null) {
            refreshKeys.add(key)
            remoteUrls.remove(key)
        } else {
            refreshKeys.addAll(remoteUrls.keys)
            remoteUrls.clear()
            generation.incrementAndGet()
        }
    }

    fun cacheKey(track: Track, resource: MediaResource): String {
        val uri = Uri.parse(resource.url)
        return if (uri.scheme == "file") {
            val file = java.io.File(uri.path.orEmpty())
            "${track.cacheKey}:${file.path}:${file.lastModified()}:${file.length()}"
        } else "${track.cacheKey}:$streamSession:${generation.get()}:${remoteUrls[track.cacheKey]?.second}:${resource.url}"
    }

    suspend fun resolve(track: Track): MediaResource {
        val local = downloads.completedFile(track.cacheKey) ?: offlineAssets.audioFile(track.cacheKey)
        if (local != null) {
            return MediaResource(url = Uri.fromFile(local).toString())
        }
        return resolutions.getOrPut(track.cacheKey) { io.github.nutea.anylisten.core.data.repo.SingleFlight() }.join {
            val cached = remoteUrls[track.cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.second < io.github.nutea.anylisten.core.data.CACHE_CHECK_INTERVAL_MS)
                return@join MediaResource(url = cached.first)
            val remote = gateway.resolveMedia(track, refresh = refreshKeys.remove(track.cacheKey) || cached != null)
            UrlNormalizer.requireEncryptedOrLocal(remote.url)
            remoteUrls[track.cacheKey] = remote.url to System.currentTimeMillis()
            remote
        }
    }
}
