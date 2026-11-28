// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Long-running downloads explicitly started by the viewer, managed by Android JobScheduler. */
internal class TvDownloadTransferJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private var manager: TvDownloadManager? = null
    private var stopObserving: (() -> Unit)? = null
    private var currentParams: JobParameters? = null
    private var finished = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        currentParams = params
        finished = false
        DownloadTransferScheduler.jobStarted()
        TvDownloadNotification.createChannel(this)
        val downloads = TvDownloadRuntime.get(applicationContext)
        manager = downloads
        setNotification(params, TvDownloadNotification.NOTIFICATION_ID,
            TvDownloadNotification.build(this, downloads.snapshot()), JOB_END_NOTIFICATION_POLICY_REMOVE)
        stopObserving = downloads.observe { tasks ->
            main.post {
                if (finished || currentParams?.jobId != params.jobId) return@post
                setNotification(params, TvDownloadNotification.NOTIFICATION_ID,
                    TvDownloadNotification.build(this, tasks), JOB_END_NOTIFICATION_POLICY_REMOVE)
                if (tasks.none { it.status == TvDownloadStatus.PENDING || it.status == TvDownloadStatus.DOWNLOADING }) {
                    finish(params)
                }
            }
        }
        downloads.resumePending()
        return true
    }

    private fun finish(params: JobParameters) {
        if (finished || currentParams?.jobId != params.jobId) return
        finished = true
        stopObserving?.invoke()
        stopObserving = null
        DownloadTransferScheduler.jobStopped(rescheduled = false)
        jobFinished(params, false)
        currentParams = null
        manager = null
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopObserving?.invoke()
        stopObserving = null
        manager?.suspendForSystemStop()
        val reschedule = manager?.hasRunnableTasks() == true
        finished = true
        currentParams = null
        manager = null
        DownloadTransferScheduler.jobStopped(rescheduled = reschedule)
        return reschedule
    }
}

internal object TvDownloadNotification {
    const val NOTIFICATION_ID = 0x48475456
    private const val CHANNEL_ID = "tv-downloads"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "下載工作", android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "顯示短劇下載進度"
                setShowBadge(false)
            })
        }
    }

    fun build(context: Context, tasks: List<TvDownloadTask>): Notification {
        val active = tasks.filter { it.status == TvDownloadStatus.PENDING || it.status == TvDownloadStatus.DOWNLOADING }
        val bytes = active.sumOf { it.bytes.coerceAtLeast(0) }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val progress = if (total > 0) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
        val title = if (active.isEmpty()) "下載工作已完成" else "hongguoTV 正在下載"
        val detail = if (active.isEmpty()) "所有下載任務已暫停或完成" else "佇列中 \${active.size} 個任務"
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = android.app.PendingIntent.getActivity(context, NOTIFICATION_ID, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
        else Notification.Builder(context)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail).setContentIntent(pending)
            .setOngoing(active.isNotEmpty()).setOnlyAlertOnce(true)
            .setProgress(100, progress, total <= 0).build()
    }
}
