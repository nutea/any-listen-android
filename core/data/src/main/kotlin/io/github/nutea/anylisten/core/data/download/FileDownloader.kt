package io.github.nutea.anylisten.core.data.download

import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.IntegrityKind
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

data class DownloadResult(val file: File, val bytes: Long, val integrity: IntegrityKind, val restarted: Boolean)

class FileDownloader(http: OkHttpClient) {
    // Large files have no whole-call deadline; stalled connections retain their read timeout.
    private val transferHttp = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    val revalidator = io.github.nutea.anylisten.core.data.CacheRevalidator(transferHttp)

    fun download(url: String, dest: File, expectedBytes: Long? = null,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }): DownloadResult {
        UrlNormalizer.requireEncryptedOrLocal(url)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val validators = File(dest.path + ".part.validator")
        val saved = Properties().apply { runCatching { validators.inputStream().use { load(it) } } }
        var existing = tmp.takeIf { it.isFile }?.length() ?: 0L
        var restarted = false
        val validator = saved.getProperty("validator").orEmpty()
        if (existing > 0 && (saved.getProperty("url") != url || validator.isBlank())) {
            tmp.delete(); existing = 0; restarted = true
        }
        if (isCancelled()) throw AppError(ErrorKind.UNKNOWN,"Cancelled")
        val request = Request.Builder().url(url).apply {
            if (existing > 0) { header("Range","bytes=$existing-"); header("If-Range",validator) }
        }.build()
        transferHttp.newCall(request).execute().use { resp ->
            if (resp.code == 416 && existing > 0) {
                tmp.delete(); validators.delete()
                return download(url,dest,expectedBytes,onProgress,isCancelled).copy(restarted = true)
            }
            if (resp.code != 200 && resp.code != 206) throw AppError.fromHttp(resp.code)
            if (existing > 0 && resp.code == 200) { tmp.delete(); existing = 0; restarted = true }
            val body = resp.body ?: throw AppError(ErrorKind.TRACK_UNAVAILABLE,"Empty body")
            val type = resp.header("Content-Type").orEmpty().lowercase()
            if (type.contains("text/html") || type.contains("application/json"))
                throw AppError(ErrorKind.INTEGRITY_FAILED,"Unexpected download content type")
            val range = if (resp.code == 206) Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(resp.header("Content-Range").orEmpty()) else null
            val rangeStart = range?.groupValues?.get(1)?.toLongOrNull()
            val rangeEnd = range?.groupValues?.get(2)?.toLongOrNull()
            val rangeTotal = range?.groupValues?.get(3)?.toLongOrNull()
            if (resp.code == 206 && (rangeStart != existing || rangeEnd == null || rangeTotal == null || rangeEnd < existing || rangeEnd != rangeTotal - 1 ||
                (body.contentLength() >= 0 && body.contentLength() != rangeEnd - existing + 1))) {
                tmp.delete(); validators.delete()
                throw AppError(ErrorKind.INTEGRITY_FAILED,"Invalid Content-Range")
            }
            val total = rangeTotal ?: body.contentLength().takeIf { it >= 0 } ?: expectedBytes
            val nextValidator = resp.header("ETag")?.takeUnless { it.startsWith("W/") }
                ?: resp.header("Last-Modified").orEmpty()
            Properties().apply { setProperty("url",url); setProperty("validator",nextValidator) }
                .also { props -> validators.outputStream().use { props.store(it,null) } }
            RandomAccessFile(tmp,"rw").use { raf ->
                raf.setLength(existing); raf.seek(existing)
                body.byteStream().use { input ->
                    val buffer = ByteArray(16384)
                    var downloaded = existing
                    while (true) {
                        if (isCancelled()) throw AppError(ErrorKind.UNKNOWN,"Cancelled")
                        val count = input.read(buffer); if (count < 0) break
                        raf.write(buffer,0,count); downloaded += count
                        onProgress(downloaded,total)
                    }
                }
            }
            val size = tmp.length()
            if (size == 0L || (total != null && size != total) || (expectedBytes != null && expectedBytes > 0 && size != expectedBytes)) {
                tmp.delete(); validators.delete()
                throw AppError(ErrorKind.INTEGRITY_FAILED,"Length mismatch")
            }
            if (isCancelled()) throw AppError(ErrorKind.UNKNOWN,"Cancelled")
            Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            validators.delete()
            revalidator.recordDownloaded(url,dest,resp.header("ETag"),resp.header("Last-Modified"))
            return DownloadResult(dest,size,IntegrityKind.NO_REMOTE_CHECKSUM,restarted)
        }
    }
}
