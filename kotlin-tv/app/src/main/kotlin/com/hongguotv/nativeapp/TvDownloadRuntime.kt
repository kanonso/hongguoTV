// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PlaybackDeviceSession
import java.io.IOException

private class DownloadStreamOpener(context: Context) {
    private val app = context.applicationContext
    private val strategyPack = PlaybackStrategyPackManager(app)
    private val repository = ContentRepository(
        playbackSession = PlaybackDeviceSession.shared,
        playbackStrategy = { strategyPack.policy() },
    )
    private val sourceSettings = PlaybackSourceSettings(app)
    private val adapter = OpenHongguoAdapter(app)
    private val recentSources = PlaybackSourceCache()

    init {
        CodecCapabilities.init(app)
    }

    fun resolveHls(
        episodeId: String,
        seriesTitle: String,
        episodeNumber: Int,
        episodeCount: Int,
        source: String,
        maxQuality: Int,
    ): HlsDownloadPlan? = if (source == "openhongguo") adapter.resolveHls(episodeId, maxQuality) else null

    fun open(
        episodeId: String,
        seriesTitle: String,
        episodeNumber: Int,
        episodeCount: Int,
        source: String,
        maxQuality: Int,
    ): OpenedStream {
        val enabled = sourceSettings.enabled()
        val selected = if (source == "openhongguo") enabled.intersect(setOf("openhongguo")) else enabled
        return openStream(
            repository.http,
            repository,
            episodeId,
            maxQuality,
            CodecCapabilities::supports,
            recentSources,
            sourceOrder = sourceSettings.order(),
            enabledSources = selected,
            openHongguoLadder = {
                if (source == "openhongguo") adapter.resolvePlayback(episodeId)
                else adapter.resolvePlaybackBySeriesTitle(seriesTitle, episodeNumber, episodeCount)
            },
        ) { track -> track == null || CodecCapabilities.supports(track) }
            ?: throw IOException("没有电视可播放的清晰度")
    }
}

/** A single process-wide queue using only application-context dependencies. */
@UnstableApi
internal object TvDownloadRuntime {
    @Volatile private var instance: TvDownloadManager? = null
    @Volatile private var hlsStore: HlsOfflineStore? = null

    fun get(context: Context, schedulePending: Boolean = false): TvDownloadManager {
        val app = context.applicationContext
        val value = instance ?: synchronized(this) {
            instance ?: run {
                val opener = DownloadStreamOpener(app)
                val offlineHls = HlsOfflineStore(app)
                hlsStore = offlineHls
                TvDownloadManager(
                    app,
                    open = { episodeId, title, number, count, source, quality ->
                        opener.open(episodeId, title, number, count, source, quality)
                    },
                    openHls = { episodeId, title, number, count, source, quality ->
                        opener.resolveHls(episodeId, title, number, count, source, quality)
                    },
                    hlsStore = offlineHls,
                    requestExecution = { DownloadTransferScheduler.request(app) },
                ).also { instance = it }
            }
        }
        if (schedulePending && value.hasRunnableTasks()) DownloadTransferScheduler.request(app)
        return value
    }

    fun createHlsMediaSource(context: Context, taskId: String, offlineUri: String): androidx.media3.exoplayer.hls.HlsMediaSource {
        get(context)
        val store = hlsStore ?: throw IOException("HLS 离线快取尚未初始化")
        return store.createMediaSource(taskId, offlineUri)
    }
}

/** Prefer quota-exempt user-initiated transfer jobs; old Android TV uses a visible FGS fallback. */
internal object DownloadTransferScheduler {
    private const val JOB_ID = 0x48475456
    @Volatile private var uidtScheduled = false

    fun request(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 34 && runCatching { scheduleUidt(app) }.getOrDefault(false)) return
        val intent = Intent(app, TvDownloadLegacyForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    @androidx.annotation.RequiresApi(34)
    private fun scheduleUidt(context: Context): Boolean = synchronized(this) {
        if (uidtScheduled) return@synchronized true
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) {
            uidtScheduled = true
            return@synchronized true
        }
        val info = android.app.job.JobInfo.Builder(
            JOB_ID,
            ComponentName(context, TvDownloadTransferJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .build()
        if (scheduler.schedule(info) != android.app.job.JobScheduler.RESULT_SUCCESS) return@synchronized false
        uidtScheduled = true
        true
    }

    fun jobStarted() { uidtScheduled = true }
    fun jobStopped(rescheduled: Boolean) { if (!rescheduled) uidtScheduled = false }
}
