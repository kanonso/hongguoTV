// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class TvDownloadParallelismTest {
    @Test
    fun identicalSeriesIdsFromDifferentProvidersNeverShareAGroup() {
        val hongguo = TvDownloadTask(
            id = "one", seriesId = "123456", seriesTitle = "同名剧", episodeId = "111",
            episodeNumber = 1, source = "hongguo", status = TvDownloadStatus.COMPLETED,
        )
        val community = hongguo.copy(id = "two", source = "openhongguo", episodeId = "222")
        assertEquals(false, downloadGroupKey(hongguo) == downloadGroupKey(community))
        assertEquals(downloadGroupKey(hongguo), downloadGroupKey(hongguo.copy(episodeNumber = 2)))
    }

    @Test
    fun configuredLimitDeterminesAvailableWorkers() {
        assertEquals(1, downloadWorkerSlots(1, 0))
        assertEquals(0, downloadWorkerSlots(1, 1))
        assertEquals(2, downloadWorkerSlots(3, 1))
        assertEquals(1, downloadWorkerSlots(3, 2))
        assertEquals(0, downloadWorkerSlots(2, 3))
    }

    @Test
    fun configuredLimitIsClampedToOneThroughThreeWorkers() {
        assertEquals(1, downloadWorkerSlots(0, 0))
        assertEquals(3, downloadWorkerSlots(99, 0))
        assertEquals(0, downloadWorkerSlots(-5, 1))
    }
}
