package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {
    @Test
    fun completeOnlyAfterVerify() {
        val downloading = DownloadStateMachine.transition(DownloadStatus.QUEUED, DownloadEvent.START)
        assertEquals(DownloadStatus.DOWNLOADING, downloading)
        assertEquals(DownloadStatus.COMPLETED, DownloadStateMachine.transition(downloading, DownloadEvent.COMPLETE))
        val verifying = DownloadStateMachine.transition(downloading, DownloadEvent.VERIFY)
        assertEquals(DownloadStatus.COMPLETED, DownloadStateMachine.transition(verifying, DownloadEvent.COMPLETE))
    }

    @Test
    fun halfFileIsNotOffline() {
        assertFalse(DownloadStateMachine.canMarkOffline(DownloadStatus.DOWNLOADING, fileReady = true))
        assertFalse(DownloadStateMachine.canMarkOffline(DownloadStatus.COMPLETED, fileReady = false))
        assertTrue(DownloadStateMachine.canMarkOffline(DownloadStatus.COMPLETED, fileReady = true))
    }

    @Test
    fun retryReturnsToQueued() {
        val failed = DownloadStateMachine.transition(DownloadStatus.DOWNLOADING, DownloadEvent.FAIL)
        assertEquals(DownloadStatus.QUEUED, DownloadStateMachine.transition(failed, DownloadEvent.RETRY))
    }
}
