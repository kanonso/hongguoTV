// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class TvDownloadParallelismTest {
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
