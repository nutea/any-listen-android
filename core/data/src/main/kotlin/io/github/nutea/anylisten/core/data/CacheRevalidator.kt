package io.github.nutea.anylisten.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

const val CACHE_CHECK_INTERVAL_MS = 15 * 60 * 1000L

/** Conditional GET with atomic replacement. Validators always describe the committed bytes. */
class CacheRevalidator(private val http: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) {
    private val locks = Array(32) { Mutex() }
    private val failures = ConcurrentHashMap<String, Long>()

    fun due(destination: File, force: Boolean = false): Boolean {
        if (force) return true
        if (now() - (failures[destination.path] ?: Long.MIN_VALUE / 2) in 0 until 60_000L) return false
        val checked = readMetadata(File(destination.path + ".http.json"))?.checkedAt ?: 0
        return now() - checked !in 0 until CACHE_CHECK_INTERVAL_MS
    }

    fun recordDownloaded(url: String, destination: File, etag: String?, modified: String?) {
        File(destination.path + ".http.json").writeText(Json.encodeToString(ValidationMetadata(url,now(),etag.orEmpty(),modified.orEmpty())))
    }

    suspend fun remove(destination: File) = withContext(Dispatchers.IO) {
        locks[(destination.path.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            destination.delete()
            File(destination.path + ".http.json").delete()
        }
    }

    suspend fun update(url: String, destination: File, force: Boolean = false,
        validate: (File) -> Unit = {}, canCommit: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        locks[(destination.path.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val metadata = File(destination.path + ".http.json")
            val previous = readMetadata(metadata)
            val exists = destination.isFile && destination.length() > 0
            val sameUrl = previous?.url == url
            val checked = previous?.checkedAt ?: 0
            if (!force && exists && sameUrl && now() - checked in 0 until CACHE_CHECK_INTERVAL_MS) return@withLock false
            if (!force && exists && now() - (failures[destination.path] ?: Long.MIN_VALUE / 2) in 0 until 60_000L) return@withLock false
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("refresh-", ".part", destination.parentFile)
            try {
                val request = Request.Builder().url(url).header("Cache-Control", "no-cache").apply {
                    if (exists && sameUrl) {
                        previous?.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                        previous?.modified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
                    }
                }.build()
                var changed = false
                val context = currentCoroutineContext()
                val call = http.newCall(request)
                val cancellation = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try { call.execute().use { response ->
                    if (response.code != 304) {
                        if (response.code != 200) throw IOException("Cache refresh HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty cache response")
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (type.contains("text/html") || type.contains("application/json")) throw IOException("Unexpected resource content type")
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(16384)
                            while (true) {
                                context.ensureActive()
                                if (!canCommit()) throw IOException("Cache refresh cancelled")
                                val n = input.read(buffer); if (n < 0) break
                                output.write(buffer,0,n)
                            }
                        } }
                        if (temporary.length() <= 0 || (body.contentLength() >= 0 && temporary.length() != body.contentLength())) throw IOException("Incomplete cache response")
                        validate(temporary)
                        context.ensureActive()
                        if (!canCommit() || (exists && !destination.exists())) throw IOException("Cache removed during refresh")
                        // Keep inode/version stable when a validator-less server returns identical bytes.
                        changed = !exists || !sameBytes(destination, temporary)
                        if (changed) Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                    } else if (!exists || !sameUrl) throw IOException("Unexpected cache 304")
                    val next = ValidationMetadata(url,now(),
                        response.header("ETag") ?: if (response.code == 304) previous?.etag.orEmpty() else "",
                        response.header("Last-Modified") ?: if (response.code == 304) previous?.modified.orEmpty() else "")
                    val metaTemp = File.createTempFile("validator-", ".part",destination.parentFile)
                    try { metaTemp.writeText(Json.encodeToString(next)); Files.move(metaTemp.toPath(),metadata.toPath(),StandardCopyOption.REPLACE_EXISTING) }
                    finally { metaTemp.delete() }
                }
                } finally { cancellation.cancel() }
                failures.remove(destination.path)
                changed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures[destination.path] = now()
                throw error
            } finally { temporary.delete() }
        }
    }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        java.io.DataInputStream(a.inputStream().buffered()).use { left ->
            java.io.DataInputStream(b.inputStream().buffered()).use { right ->
                val x = ByteArray(16384); val y = ByteArray(16384)
                var remaining = a.length()
                while (remaining > 0) {
                    val n = minOf(remaining, x.size.toLong()).toInt()
                    left.readFully(x,0,n); right.readFully(y,0,n)
                    for (i in 0 until n) if (x[i] != y[i]) return false
                    remaining -= n
                }
            }
        }
        return true
    }
}

@Serializable
private data class ValidationMetadata(val url: String = "", val checkedAt: Long = 0, val etag: String = "", val modified: String = "")
private fun readMetadata(file: File): ValidationMetadata? = runCatching { Json.decodeFromString<ValidationMetadata>(file.readText()) }.getOrNull()
