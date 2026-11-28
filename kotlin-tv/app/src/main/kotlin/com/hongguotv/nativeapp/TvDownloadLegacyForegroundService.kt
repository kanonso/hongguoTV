// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Compatibility runner for Android 6–13; Android 14+ uses UIDT jobs. */
internal class TvDownloadLegacyForegroundService : Service() {
    private var manager: TvDownloadManager? = null
    private var stopObserving: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        TvDownloadNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = TvDownloadNotification.build(this, emptyList())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(TvDownloadNotification.NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(TvDownloadNotification.NOTIFICATION_ID, initial)
        }
        val downloads = TvDownloadRuntime.get(applicationContext)
        manager = downloads
        stopObserving?.invoke()
        downloads.resumePending()
        stopObserving = downloads.observe { tasks ->
            val active = tasks.any { it.status == TvDownloadStatus.PENDING || it.status == TvDownloadStatus.DOWNLOADING }
            val notification = TvDownloadNotification.build(this, tasks)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(TvDownloadNotification.NOTIFICATION_ID, notification)
            if (!active) stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        manager?.suspendForManualRecovery()
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopObserving?.invoke()
        stopObserving = null
        manager = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
