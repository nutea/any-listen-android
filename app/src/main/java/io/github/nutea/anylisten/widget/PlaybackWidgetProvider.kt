package io.github.nutea.anylisten.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.nutea.anylisten.AnyListenApp
import io.github.nutea.anylisten.MainActivity
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.data.AppScopes
import io.github.nutea.anylisten.core.data.connection.ConnectionState
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.playback.PlaybackService
import io.github.nutea.anylisten.core.playback.PlaybackSurfaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/** RemoteViews stay small and refresh on changes, never on a periodic playback-position timer. */
class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAsync(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = updateAsync(context)

    private fun updateAsync(context: Context) {
        val result = goAsync()
        widgetScope.launch { try { PlaybackWidget.update(context) } finally { result.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            ACTION_FAVORITE -> {
                val result = goAsync()
                widgetScope.launch {
                    try {
                        withTimeout(8_000) {
                            val app = context.applicationContext as AnyListenApp
                            val track = PlaybackService.surfaceState.value?.track ?: app.container.settings.playback.first().currentKey
                                ?.let { app.container.library.cachedTrack(it) } ?: return@withTimeout
                            val liked = app.container.library.cached().tracksByPlaylist[ProtocolConstants.LIST_LOVE].orEmpty()
                                .any { it.identity == track.identity }
                            if (liked) app.container.library.removeFromPlaylist(ProtocolConstants.LIST_LOVE, track)
                            else app.container.library.addToPlaylist(ProtocolConstants.LIST_LOVE, track)
                            PlaybackWidget.updateStoredFavorite(context, track.cacheKey, !liked)
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, R.string.widget_favorite_failed, Toast.LENGTH_SHORT).show() }
                    } finally { result.finish() }
                }
            }
            PlaybackService.ACTION_WIDGET_TOGGLE, PlaybackService.ACTION_WIDGET_NEXT, PlaybackService.ACTION_WIDGET_PREVIOUS -> {
                val service = PlaybackService.service
                if (service?.hasPlaybackQueue() == true) service.handleWidgetAction(action!!)
                else try {
                    ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(action))
                } catch (_: IllegalStateException) {
                    context.startActivity(openPlayerIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_FAVORITE = "io.github.nutea.anylisten.WIDGET_FAVORITE"
        private val widgetScope = AppScopes.create("widget", Dispatchers.IO)
        fun openPlayerIntent(context: Context) = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

object PlaybackWidget {
    private val scope = AppScopes.create("widget-state", Dispatchers.IO)
    fun observe(context: Context) {
        scope.launch {
            val connected = (context.applicationContext as AnyListenApp).container.connectionState
                .map { it is ConnectionState.Online }.distinctUntilChanged()
            combine(PlaybackService.surfaceState, connected) { state, online -> state to online }
                .distinctUntilChanged().collect { (state, _) ->
                if (state != null) {
                    val data = JSONObject().put("key", state.track?.cacheKey.orEmpty()).put("title", state.track?.title.orEmpty())
                        .put("artist", state.track?.artist.orEmpty()).put("favorite", state.favorite)
                        .put("artwork", state.artworkPath.orEmpty())
                    context.getSharedPreferences("playback_widget", Context.MODE_PRIVATE).edit { putString("snapshot", data.toString()) }
                }
                update(context, state)
            }
        }
    }

    internal fun updateStoredFavorite(context: Context, key: String, favorite: Boolean) {
        val data = stored(context)
        if (data.optString("key") != key) return
        data.put("favorite", favorite)
        context.getSharedPreferences("playback_widget", Context.MODE_PRIVATE).edit { putString("snapshot", data.toString()) }
        val live = PlaybackService.surfaceState.value
        update(context, live?.let { if (it.track?.cacheKey == key) it.copy(favorite = favorite) else it })
    }

    private fun stored(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences("playback_widget", Context.MODE_PRIVATE).getString("snapshot", "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    internal fun update(context: Context, state: PlaybackSurfaceState? = PlaybackService.surfaceState.value) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlaybackWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val saved = stored(context)
        val title = state?.track?.title ?: saved.optString("title")
        val artist = state?.track?.artist ?: saved.optString("artist")
        val path = state?.artworkPath ?: saved.optString("artwork")
        val liked = state?.favorite ?: saved.optBoolean("favorite")
        val active = state?.playWhenReady == true
        val enabled = title.isNotBlank()
        val bitmap = path.takeIf { it.isNotBlank() }?.let { file ->
            // Decode only files inside this app's storage, and keep binder payloads bounded.
            val target = File(file)
            if (!target.path.startsWith(context.filesDir.path + "/") && !target.path.startsWith(context.cacheDir.path + "/")) null
            else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 192) sample *= 2
                BitmapFactory.decodeFile(file, BitmapFactory.Options().apply { inSampleSize = sample })?.let { decoded ->
                    // Round the bitmap itself: XML outline clipping is unavailable on older widgets.
                    val edge = 192
                    val scale = edge.toFloat() / minOf(decoded.width, decoded.height)
                    val shader = BitmapShader(decoded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply {
                            setScale(scale, scale)
                            postTranslate((edge - decoded.width * scale) / 2, (edge - decoded.height * scale) / 2)
                        })
                    }
                    val rounded = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                    Canvas(rounded).drawRoundRect(0f, 0f, edge.toFloat(), edge.toFloat(), 32f, 32f,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
                    decoded.recycle()
                    rounded
                }
            }
        }
        try {
            ids.forEach { id ->
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
                val views = RemoteViews(context.packageName, R.layout.playback_widget)
                views.setTextViewText(R.id.widget_title, title.ifBlank { context.getString(R.string.app_name) })
                views.setTextViewText(R.id.widget_artist, artist.ifBlank { if (enabled) context.getString(R.string.widget_open) else context.getString(R.string.widget_empty) })
                if (bitmap != null) views.setImageViewBitmap(R.id.widget_cover, bitmap)
                else views.setImageViewResource(R.id.widget_cover, R.drawable.widget_note)
                views.setImageViewResource(R.id.widget_toggle, if (active) R.drawable.widget_pause else R.drawable.widget_play)
                views.setContentDescription(R.id.widget_toggle, context.getString(if (active) R.string.cd_pause else R.string.cd_play))
                views.setImageViewResource(R.id.widget_favorite, if (liked) R.drawable.widget_heart_filled else R.drawable.widget_heart)
                views.setContentDescription(R.id.widget_favorite, context.getString(if (liked) R.string.cd_unfavorite else R.string.cd_favorite))
                views.setViewVisibility(R.id.widget_cover, if (width >= 260) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_favorite, if (width >= 308) View.VISIBLE else View.GONE)
                val open = PendingIntent.getActivity(context, 0, PlaybackWidgetProvider.openPlayerIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_cover, open)
                views.setOnClickPendingIntent(R.id.widget_titles, open)
                listOf(R.id.widget_toggle to PlaybackService.ACTION_WIDGET_TOGGLE,
                    R.id.widget_next to PlaybackService.ACTION_WIDGET_NEXT,
                    R.id.widget_previous to PlaybackService.ACTION_WIDGET_PREVIOUS,
                    R.id.widget_favorite to PlaybackWidgetProvider.ACTION_FAVORITE).forEach { (view, action) ->
                    val command = PendingIntent.getBroadcast(context, view,
                        Intent(context, PlaybackWidgetProvider::class.java).setAction(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(view, if (enabled) command else open)
                    // Favorites remain server-owned; offline widgets show the last confirmed value.
                    views.setBoolean(view, "setEnabled", view != R.id.widget_favorite || enabled &&
                        (context.applicationContext as AnyListenApp).container.isConnected())
                }
                manager.updateAppWidget(id, views)
            }
        } finally { bitmap?.recycle() }
    }
}
