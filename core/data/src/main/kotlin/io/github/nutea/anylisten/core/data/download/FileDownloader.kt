package io.github.nutea.anylisten.core.data.download

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.IntegrityKind
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

data class DownloadResult(
    val file: File,
    val bytes: Long,
    val integrity: IntegrityKind,
    val restarted: Boolean,
)

class FileDownloader(
    private val http: OkHttpClient,
) {
    fun download(
        url: String,
        dest: File,
        expectedBytes: Long? = null,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): DownloadResult {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var restarted = false
        var existing = if (tmp.exists()) tmp.length() else 0L
        if (existing > 0) {
            val probe = request(url, existing)
            probe.use { response ->
                when (response.code) {
                    206 -> Unit
                    200 -> {
                        tmp.delete()
                        existing = 0
                        restarted = true
                    }
                    else -> throw AppError.fromHttp(response.code)
                }
            }
        }
        val response = request(url, if (existing > 0) existing else null)
        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw AppError.fromHttp(resp.code)
            if (existing > 0 && resp.code == 200) {
                tmp.delete()
                existing = 0
                restarted = true
                return download(url, dest, expectedBytes, onProgress, isCancelled)
            }
            val total = resp.header("Content-Length")?.toLongOrNull()?.let { it + existing } ?: expectedBytes
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(existing)
                val body = resp.body ?: throw AppError(ErrorKind.TRACK_UNAVAILABLE, "Empty body")
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var downloaded = existing
                    while (true) {
                        if (isCancelled()) throw AppError(ErrorKind.UNKNOWN, "Cancelled")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            val size = tmp.length()
            if (expectedBytes != null && expectedBytes > 0 && size != expectedBytes) {
                tmp.delete()
                throw AppError(ErrorKind.INTEGRITY_FAILED, "Length mismatch")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (!dest.exists() || dest.length() == 0L) {
                throw AppError(ErrorKind.INTEGRITY_FAILED, "File missing after commit")
            }
            return DownloadResult(dest, dest.length(), IntegrityKind.NO_REMOTE_CHECKSUM, restarted)
        }
    }

    private fun request(url: String, start: Long?) = http.newCall(
        Request.Builder().url(url).apply {
            if (start != null && start > 0) header("Range", "bytes=$start-")
        }.build(),
    ).execute()

    private companion object {
        const val DEFAULT_BUFFER = 16 * 1024
    }
}
