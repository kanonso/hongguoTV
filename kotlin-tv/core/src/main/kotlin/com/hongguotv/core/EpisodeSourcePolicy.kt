// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Cross-provider episode IDs must never be uploaded as Red Fruit cloud playback progress. */
object EpisodeSourcePolicy {
    fun canSyncHongguoCloudProgress(series: Series, episodeSource: String): Boolean =
        series.source == "hongguo" && episodeSource == "hongguo"
}
