package io.github.nutea.anylisten.core.data

import android.graphics.BitmapFactory
import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/** Durable artwork: immediate offline access, stale-while-revalidate when connected. */
class ArtworkStore(private val directory: File, http: OkHttpClient, private val online: () -> Boolean = { true }, private val canRefresh: () -> Boolean = { true }) {
    private val revalidator = CacheRevalidator(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val changes = MutableStateFlow(0L)
    val updates = changes.asStateFlow()
    private val versions = ConcurrentHashMap<String, Long>()
    fun updatesFor(url: String?) = updates.map {
        url?.let { UrlNormalizer.artworkFetchUrls(it).sumOf { candidate -> versions[candidate] ?: 0L } } ?: 0L
    }.distinctUntilChanged()
    fun bytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    fun cached(url: String): File? = UrlNormalizer.artworkFetchUrls(url).firstNotNullOfOrNull { candidate ->
        fileFor(candidate).takeIf { it.isFile && it.length() > 0 }
    }
    suspend fun get(url: String, force: Boolean = false): File {
        val saved = cached(url)
        if (!online()) return saved ?: throw IOException("Artwork unavailable offline")
        if (saved != null && !force) {
            if (online() && canRefresh()) synchronized(jobs) {
                if (jobs[url]?.isActive != true) jobs[url] = scope.launch { runCatching { refresh(url,false) } }
            }
            return saved
        }
        if (saved != null && !online()) return saved
        return try { refresh(url,force) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { saved ?: throw error }
    }
    private suspend fun refresh(url: String, force: Boolean): File {
        var last: Exception? = null
        val candidates = UrlNormalizer.artworkFetchUrls(url)
        // Prefer the previously working variant (HTTPS upgrade / HTTP fallback).
        val ordered = candidates.sortedByDescending { fileFor(it).isFile }
        for (candidate in ordered) {
            try {
                val file = fileFor(candidate)
                val changed = revalidator.update(candidate,file,force,validate = { image ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(image.path,bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid artwork image")
                })
                if (changed) {
                    versions.merge(candidate, 1L, Long::plus)
                    changes.update { it + 1 }
                }
                return file
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { last = error }
        }
        throw last ?: IOException("Artwork unavailable")
    }
    private fun fileFor(url: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(directory,key)
    }
}
