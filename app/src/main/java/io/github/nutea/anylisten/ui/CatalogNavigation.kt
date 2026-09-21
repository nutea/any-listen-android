package io.github.nutea.anylisten.ui

import android.net.Uri
import io.github.nutea.anylisten.core.model.MusicCatalog

internal fun artistRoute(key: MusicCatalog.ArtistKey) = "artist/${Uri.encode(key.serverId)}/${Uri.encode(key.name)}"
internal fun albumRoute(key: MusicCatalog.AlbumKey) = "album/${Uri.encode(key.serverId)}/${Uri.encode(key.artist.ifBlank { " " })}/${Uri.encode(key.name)}"
internal fun isCatalogRoute(route: String?) = route?.let { it.startsWith("catalog/") || it.startsWith("artist/") || it.startsWith("album/") } == true
