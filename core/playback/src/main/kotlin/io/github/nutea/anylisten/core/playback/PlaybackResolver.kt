package io.github.nutea.anylisten.core.playback

import android.net.Uri
import io.github.nutea.anylisten.core.data.download.DownloadCoordinator
import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.Track
import java.util.concurrent.ConcurrentHashMap

class PlaybackResolver(
    private val downloads: DownloadCoordinator,
    private val gateway: AnyListenGateway,
) {
    private val remoteUrls = ConcurrentHashMap<String, String>()

    suspend fun resolve(track: Track): MediaResource {
        val local = downloads.completedFile(track.cacheKey)
        if (local != null) {
            return MediaResource(url = Uri.fromFile(local).toString())
        }
        remoteUrls[track.cacheKey]?.let { cached ->
            return MediaResource(url = cached)
        }
        val remote = gateway.resolveMedia(track, refresh = false)
        remoteUrls[track.cacheKey] = remote.url
        return remote
    }
}
