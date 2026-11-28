// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class TvDownloadRestoreStatusTest {
    @Test
    fun aProcessRestartMarksInFlightDownloadsInterrupted() {
        assertEquals(
            TvDownloadStatus.INTERRUPTED,
            restoredDownloadStatus(TvDownloadStatus.DOWNLOADING, executionSessionWasActive = false),
        )
    }

    @Test
    fun aUserStoppedQueueDoesNotAutoRestartPendingItems() {
        assertEquals(
            TvDownloadStatus.INTERRUPTED,
            restoredDownloadStatus(TvDownloadStatus.PENDING, executionSessionWasActive = true),
        )
    }

    @Test
    fun aNeverStartedPendingTaskKeepsItsExplicitRequest() {
        assertEquals(
            TvDownloadStatus.PENDING,
            restoredDownloadStatus(TvDownloadStatus.PENDING, executionSessionWasActive = false),
        )
    }

    @Test
    fun manuallyPausedAndFinishedItemsKeepTheirStates() {
        assertEquals(
            TvDownloadStatus.PAUSED,
            restoredDownloadStatus(TvDownloadStatus.PAUSED, executionSessionWasActive = true),
        )
        assertEquals(
            TvDownloadStatus.COMPLETED,
            restoredDownloadStatus(TvDownloadStatus.COMPLETED, executionSessionWasActive = true),
        )
    }
}
