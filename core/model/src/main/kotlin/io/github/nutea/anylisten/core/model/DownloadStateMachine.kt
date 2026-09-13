package io.github.nutea.anylisten.core.model

object DownloadStateMachine {
    fun transition(from: DownloadStatus, event: DownloadEvent): DownloadStatus {
        return when (event) {
            DownloadEvent.START -> when (from) {
                DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.FAILED -> DownloadStatus.DOWNLOADING
                else -> from
            }
            DownloadEvent.PAUSE -> if (from == DownloadStatus.DOWNLOADING) DownloadStatus.PAUSED else from
            DownloadEvent.VERIFY -> if (from == DownloadStatus.DOWNLOADING) DownloadStatus.VERIFYING else from
            DownloadEvent.COMPLETE -> when (from) {
                DownloadStatus.VERIFYING, DownloadStatus.DOWNLOADING -> DownloadStatus.COMPLETED
                else -> from
            }
            DownloadEvent.FAIL -> when (from) {
                DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING, DownloadStatus.QUEUED -> DownloadStatus.FAILED
                else -> from
            }
            DownloadEvent.CANCEL -> when (from) {
                DownloadStatus.COMPLETED -> from
                else -> DownloadStatus.CANCELLED
            }
            DownloadEvent.RETRY -> if (from == DownloadStatus.FAILED || from == DownloadStatus.CANCELLED) {
                DownloadStatus.QUEUED
            } else {
                from
            }
        }
    }

    fun canMarkOffline(status: DownloadStatus, fileReady: Boolean): Boolean =
        status == DownloadStatus.COMPLETED && fileReady
}

enum class DownloadEvent {
    START,
    PAUSE,
    VERIFY,
    COMPLETE,
    FAIL,
    CANCEL,
    RETRY,
}
