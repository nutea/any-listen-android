package io.github.nutea.anylisten.core.playback

import android.net.Uri

object PlaybackUris {
    const val SCHEME = "anylisten"
    const val HOST = "track"

    fun forTrack(cacheKey: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(cacheKey).build()

    fun cacheKey(uri: Uri): String? =
        uri.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment
}
