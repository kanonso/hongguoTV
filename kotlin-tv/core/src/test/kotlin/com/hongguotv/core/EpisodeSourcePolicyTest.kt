// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSourcePolicyTest {
    @Test
    fun onlyNativeHongguoSeriesAndEpisodeIdsCanSyncToHongguoCloud() {
        val hongguo = Series(id = "123456", title = "红果剧", source = "hongguo")
        val community = Series(id = "123456", title = "社区剧", source = "openhongguo")

        assertTrue(EpisodeSourcePolicy.canSyncHongguoCloudProgress(hongguo, "hongguo"))
        assertFalse(EpisodeSourcePolicy.canSyncHongguoCloudProgress(hongguo, "openhongguo"))
        assertFalse(EpisodeSourcePolicy.canSyncHongguoCloudProgress(community, "openhongguo"))
        assertFalse(EpisodeSourcePolicy.canSyncHongguoCloudProgress(community, "hongguo"))
    }
}
