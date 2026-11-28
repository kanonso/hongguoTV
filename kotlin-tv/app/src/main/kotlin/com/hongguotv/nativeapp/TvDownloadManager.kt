// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.RemoteVideo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal enum class TvDownloadStatus { PENDING, DOWNLOADING, INTERRUPTED, PAUSED, COMPLETED, FAILED, CANCELLED }

internal fun restoredDownloadStatus(status: TvDownloadStatus, executionSessionWasActive: Boolean): TvDownloadStatus =
    if (status == TvDownloadStatus.DOWNLOADING ||
        (executionSessionWasActive && status == TvDownloadStatus.PENDING)
    ) TvDownloadStatus.INTERRUPTED else status

internal data class TvDownloadTask(
    val id: String,
    val seriesId: String,
    val seriesTitle: String,
    val episodeId: String,
    val episodeNumber: Int,
    val episodeCount: Int = 0,
    val maxQuality: Int = 1080,
    val source: String,
    val status: TvDownloadStatus,
    val mediaFormat: String = "progressive",
    val mediaUri: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val filePath: String = "",
    val error: String = "",
    val fingerprint: String = "",
) {
    val progress: Int get() = if (total <= 0) 0 else ((bytes * 100L) / total).toInt().coerceIn(0, 100)
}

/**
 * Android TV download queue. Downloads use the same verified, decoder-compatible, decrypted
 * RemoteVideo pipeline as playback; only complete app-authorized progressive streams are saved.
 * Work is serialized, persisted, cancellable and resumable after verifying the media identity.
 */
internal class TvDownloadManager(
    context: Context,
    private val open: (episodeId: String, seriesTitle: String, episodeNumber: Int, episodeCount: Int, source: String, maxQuality: Int) -> OpenedStream,
    private val openHls: (episodeId: String, seriesTitle: String, episodeNumber: Int, episodeCount: Int, source: String, maxQuality: Int) -> HlsDownloadPlan?,
    private val hlsStore: HlsOfflineStore,
    private val requestExecution: () -> Unit,
) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "downloads").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("tv-download-queue", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val activeVideos = mutableMapOf<String, RemoteVideo>()
    private val activeHlsDownloaders = mutableMapOf<String, androidx.media3.exoplayer.offline.Downloader>()
    private val tasks = linkedMapOf<String, TvDownloadTask>()
    private val listeners = CopyOnWriteArrayList<(List<TvDownloadTask>) -> Unit>()
    private var draining = false
    private var executionAllowed = false
    private var executionSessionActive = preferences.getBoolean("execution_active", false)

    init {
        val interruptedSession = executionSessionActive
        val array = runCatching { JSONArray(preferences.getString("tasks", "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val restored = decode(row) ?: continue
            if (restored.status == TvDownloadStatus.CANCELLED && restored.mediaFormat == "hls") {
                runCatching { hlsStore.remove(restored.id) }
            }
            val recoveredStatus = restoredDownloadStatus(restored.status, interruptedSession)
            val recoveredBytes = when {
                restored.mediaFormat == "hls" -> hlsStore.downloadedBytes(restored.id)
                else -> File(restored.filePath + ".part").takeIf(File::exists)?.length() ?: restored.bytes
            }
            tasks[restored.id] = restored.copy(status = recoveredStatus, bytes = recoveredBytes)
        }
        // A fresh JobService start can re-arm INTERRUPTED items. A plain Activity launch cannot;
        // this prevents Android Task Manager's Stop action from silently restarting the queue.
        executionSessionActive = false
        persistLocked()
    }

    fun snapshot(): List<TvDownloadTask> = synchronized(lock) { tasks.values.toList() }

    fun observe(listener: (List<TvDownloadTask>) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot())
        return { listeners -= listener }
    }

    fun enqueue(
        seriesId: String, seriesTitle: String, episodeId: String, episodeNumber: Int,
        source: String = "", episodeCount: Int = 0, maxQuality: Int = 1080,
    ): String {
        require(seriesId.matches(Regex("[0-9]{1,30}")) && episodeId.matches(Regex("[0-9]{1,30}")))
        require(episodeNumber in 1..10000)
        val id = UUID.randomUUID().toString()
        val safeTitle = seriesTitle.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(48).ifBlank { "短剧" }
        val target = File(directory, "${safeTitle}_第${episodeNumber}集_${id.take(8)}.mp4")
        synchronized(lock) {
            tasks[id] = TvDownloadTask(
                id, seriesId, seriesTitle, episodeId, episodeNumber,
                episodeCount = episodeCount, maxQuality = maxQuality.coerceAtLeast(1),
                source = source, status = TvDownloadStatus.PENDING, filePath = target.absolutePath,
            )
            persistLocked()
        }
        publish()
        requestWork()
        return id
    }

    fun enqueueAll(
        seriesId: String, seriesTitle: String, episodeIds: List<String>,
        source: String = "", episodeCount: Int = episodeIds.size, maxQuality: Int = 1080,
    ) {
        require(seriesId.matches(Regex("[0-9]{1,30}")))
        val items = episodeIds.take(500)
        require(items.all { it.matches(Regex("[0-9]{1,30}")) })
        synchronized(lock) {
            items.forEachIndexed { index, episodeId ->
                val number = index + 1
                val id = UUID.randomUUID().toString()
                val safeTitle = seriesTitle.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(48).ifBlank { "短剧" }
                val target = File(directory, "${safeTitle}_第${number}集_${id.take(8)}.mp4")
                tasks[id] = TvDownloadTask(
                    id, seriesId, seriesTitle, episodeId, number,
                    episodeCount = episodeCount, maxQuality = maxQuality.coerceAtLeast(1),
                    source = source, status = TvDownloadStatus.PENDING, filePath = target.absolutePath,
                )
            }
            persistLocked()
        }
        if (items.isNotEmpty()) {
            publish()
            requestWork()
        }
    }

    fun pause(id: String) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status !in setOf(TvDownloadStatus.PENDING, TvDownloadStatus.DOWNLOADING)) return
            tasks[id] = task.copy(status = TvDownloadStatus.PAUSED)
            activeVideos[id]?.close()
            activeHlsDownloaders[id]?.cancel()
            persistLocked()
        }
        publish()
    }

    fun resume(id: String) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status !in setOf(TvDownloadStatus.INTERRUPTED, TvDownloadStatus.PAUSED, TvDownloadStatus.FAILED)) return
            tasks[id] = task.copy(status = TvDownloadStatus.PENDING, error = "")
            persistLocked()
        }
        publish()
        requestWork()
    }

    private fun requestWork() {
        val alreadyExecuting = synchronized(lock) { executionAllowed && !executor.isShutdown }
        if (alreadyExecuting) drain() else requestExecution()
    }

    /** Called only by a JobService/foreground-service restart initiated by the OS scheduler. */
    fun resumePending() {
        synchronized(lock) {
            tasks.entries.toList().forEach { (id, task) ->
                if (task.status == TvDownloadStatus.INTERRUPTED) {
                    tasks[id] = task.copy(status = TvDownloadStatus.PENDING, error = "")
                }
            }
            executionAllowed = true
            executionSessionActive = true
            // Flush this marker before any transfer starts. If Android's Task Manager Stop kills
            // the process without onStopJob(), the next process must not auto-resume this queue.
            preferences.edit().putBoolean("execution_active", true).commit()
            persistLocked()
        }
        drain()
    }

    /** The OS explicitly stopped this transfer; retain partial data but don't auto-restart it. */
    fun suspendForManualRecovery() {
        synchronized(lock) {
            executionAllowed = false
            tasks.entries.toList().forEach { (id, task) ->
                if (task.status in setOf(TvDownloadStatus.PENDING, TvDownloadStatus.DOWNLOADING)) {
                    val bytes = if (task.mediaFormat == "hls") hlsStore.downloadedBytes(id)
                        else File(task.filePath + ".part").takeIf(File::exists)?.length() ?: task.bytes
                    tasks[id] = task.copy(status = TvDownloadStatus.INTERRUPTED, bytes = bytes)
                }
            }
            activeVideos.values.toList().forEach { it.close() }
            activeHlsDownloaders.values.toList().forEach { it.cancel() }
            executionSessionActive = false
            persistLocked()
        }
        publish()
    }

    fun suspendForSystemStop() {
        synchronized(lock) {
            executionAllowed = false
            tasks.entries.toList().forEach { (id, task) ->
                if (task.status == TvDownloadStatus.DOWNLOADING) {
                    val bytes = if (task.mediaFormat == "hls") hlsStore.downloadedBytes(id)
                        else File(task.filePath + ".part").takeIf(File::exists)?.length() ?: task.bytes
                    tasks[id] = task.copy(status = TvDownloadStatus.PENDING, bytes = bytes, error = "")
                }
            }
            activeVideos.values.toList().forEach { it.close() }
            activeHlsDownloaders.values.toList().forEach { it.cancel() }
            persistLocked()
        }
        publish()
    }

    fun hasRunnableTasks(): Boolean = synchronized(lock) {
        tasks.values.any { it.status == TvDownloadStatus.PENDING || it.status == TvDownloadStatus.DOWNLOADING }
    }

    fun cancel(id: String) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            activeVideos[id]?.close()
            activeHlsDownloaders[id]?.cancel()
            tasks[id] = task.copy(status = TvDownloadStatus.CANCELLED, error = "已取消")
            File(task.filePath + ".part").delete()
            persistLocked()
        }
        publish()
        if (synchronized(lock) { activeHlsDownloaders[id] == null && tasks[id]?.status == TvDownloadStatus.CANCELLED && tasks[id]?.mediaFormat == "hls" }) {
            runCatching { executor.execute { hlsStore.remove(id) } }
        }
    }

    fun remove(id: String, deleteFile: Boolean = false) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status == TvDownloadStatus.DOWNLOADING) return
            if (task.mediaFormat == "hls") {
                // HLS is cache-backed rather than a standalone file; removing its task makes
                // the task-scoped offline cache unreachable, so always remove it.
                hlsStore.remove(id)
            } else if (deleteFile) {
                File(task.filePath).delete()
            }
            File(task.filePath + ".part").delete()
            tasks.remove(id)
            persistLocked()
        }
        publish()
    }

    fun close() {
        synchronized(lock) {
            executionAllowed = false
            activeVideos.values.toList().forEach { it.close() }
            activeHlsDownloaders.values.toList().forEach { it.cancel() }
        }
        executor.shutdownNow()
    }

    private fun drain() {
        synchronized(lock) {
            if (!executionAllowed || draining || executor.isShutdown) return
            draining = true
        }
        try {
            executor.execute {
                try {
                    while (true) {
                    val task = synchronized(lock) {
                        if (!executionAllowed) null else tasks.values.firstOrNull { it.status == TvDownloadStatus.PENDING }?.also {
                            tasks[it.id] = it.copy(status = TvDownloadStatus.DOWNLOADING, error = "")
                            persistLocked()
                        }
                    } ?: break
                    publish()
                    execute(task.id)
                }
                } finally {
                    val continueQueue = synchronized(lock) {
                        draining = false
                        val pending = tasks.values.any { it.status == TvDownloadStatus.PENDING }
                        if (!pending) {
                            executionAllowed = false
                            executionSessionActive = false
                            persistLocked()
                        }
                        pending && executionAllowed
                    }
                    if (continueQueue) drain()
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { draining = false }
        }
    }

    private fun execute(id: String) {
        var opened: OpenedStream? = null
        try {
            val task = synchronized(lock) { tasks[id] } ?: return
            val hlsPlan = openHls(task.episodeId, task.seriesTitle, task.episodeNumber, task.episodeCount, task.source, task.maxQuality)
            if (hlsPlan != null) {
                downloadHls(task, hlsPlan)
                return
            }
            if (task.mediaFormat == "hls") hlsStore.remove(id)
            val progressivePath = progressiveMediaPath(task.filePath)
            opened = open(task.episodeId, task.seriesTitle, task.episodeNumber, task.episodeCount, task.source, task.maxQuality)
            update(id) { it.copy(filePath = progressivePath, mediaFormat = "progressive", mediaUri = "") }
            val video = opened.video
            synchronized(lock) {
                if (tasks[id]?.status != TvDownloadStatus.DOWNLOADING) throw IOException("下载已暂停或取消")
                activeVideos[id] = video
            }
            val total = video.total
            if (total <= 0L || total > MAX_FILE_BYTES) throw IOException("文件大小无效或超过 2GB 下载上限")
            val part = File(progressivePath + ".part")
            val target = File(progressivePath)
            part.parentFile?.mkdirs()
            var offset = part.length()
            if (offset > total) { part.delete(); offset = 0 }
            val prefixLength = minOf(64 * 1024L, total).toInt()
            val prefix = video.read(0, prefixLength)
            val fingerprint = sha256(prefix)
            val saved = synchronized(lock) { tasks[id]?.fingerprint.orEmpty() }
            val localPrefix = if (offset >= prefixLength) {
                runCatching {
                    FileInputStream(part).use { input ->
                        val buffer = ByteArray(prefixLength)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read <= 0) break
                            count += read
                        }
                        if (count == buffer.size) buffer else ByteArray(0)
                    }
                }.getOrDefault(ByteArray(0))
            } else ByteArray(0)
            val localMatches = localPrefix.size == prefixLength && sha256(localPrefix) == fingerprint
            if (offset > 0 && (saved.isBlank() || saved != fingerprint || !localMatches ||
                    synchronized(lock) { tasks[id]?.total } != total)) {
                part.delete()
                offset = 0
            }
            if (offset == 0L) {
                RandomAccessFile(part, "rw").use { file ->
                    file.setLength(0)
                    file.write(prefix)
                    file.fd.sync()
                }
                offset = prefix.size.toLong()
            }
            update(id) { it.copy(bytes = offset, total = total, fingerprint = fingerprint) }
            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                var nextSave = offset
                while (offset < total) {
                    ensureRunning(id)
                    val count = minOf(CHUNK_BYTES.toLong(), total - offset).toInt()
                    val bytes = video.read(offset, count)
                    if (bytes.isEmpty()) throw IOException("视频数据提前结束")
                    file.write(bytes)
                    offset += bytes.size
                    if (offset - nextSave >= PROGRESS_INTERVAL || offset == total) {
                        file.fd.sync()
                        nextSave = offset
                        update(id) { it.copy(bytes = offset, total = total, fingerprint = fingerprint) }
                    }
                }
            }
            ensureRunning(id)
            if (target.exists() && !target.delete()) throw IOException("无法替换已有下载文件")
            if (!part.renameTo(target)) throw IOException("下载完成，但无法保存最终文件")
            update(id) { it.copy(status = TvDownloadStatus.COMPLETED, bytes = total, total = total, error = "") }
        } catch (failure: Exception) {
            val current = synchronized(lock) { tasks[id] }
            val partial = if (current?.mediaFormat == "hls") hlsStore.downloadedBytes(id)
                else File(current?.filePath.orEmpty() + ".part").takeIf(File::exists)?.length() ?: current?.bytes ?: 0L
            when (current?.status) {
                TvDownloadStatus.PAUSED, TvDownloadStatus.CANCELLED, TvDownloadStatus.PENDING,
                TvDownloadStatus.INTERRUPTED -> {
                    update(id) { it.copy(bytes = partial) }
                }
                else -> update(id) {
                    it.copy(status = TvDownloadStatus.FAILED, bytes = partial, error = safeFailure(failure))
                }
            }
        } finally {
            opened?.close()
            synchronized(lock) {
                activeVideos.remove(id)
                activeHlsDownloaders.remove(id)
            }
            if (synchronized(lock) { tasks[id]?.status == TvDownloadStatus.CANCELLED }) {
                runCatching { hlsStore.remove(id) }
            }
        }
    }

    /**
     * Downloads one HLS VOD representation. A failed primary CDN is retried once against the
     * SDK-provided backup URL. Cache entries from the failed CDN are cleared before switching,
     * so two different partial segment sets can never be mixed.
     */
    private fun downloadHls(task: TvDownloadTask, plan: HlsDownloadPlan) {
        if (plan.estimatedSize > MAX_FILE_BYTES) {
            hlsStore.remove(task.id)
            throw IOException("HLS 檔案超過 2 GB 下載上限")
        }
        val urls = listOfNotNull(plan.url, plan.backupUrl)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .let { candidates ->
                val preferred = task.mediaUri
                if (preferred.isBlank()) candidates
                else candidates.sortedBy { url ->
                    if (runCatching { hlsStore.offlineUri(url) }.getOrNull() == preferred) 0 else 1
                }
            }
        if (urls.isEmpty()) throw IOException("HLS 沒有可用的媒體 URL")

        var lastFailure: Exception? = null
        urls.forEachIndexed { index, rawUrl ->
            ensureRunning(task.id)
            if (index > 0) {
                // Do not combine cached segments from different CDNs.
                hlsStore.remove(task.id)
                update(task.id) { it.copy(bytes = 0L, total = plan.estimatedSize.coerceAtLeast(0L), error = "") }
            }
            var downloader: androidx.media3.exoplayer.offline.Downloader? = null
            val sizeExceeded = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val offlineUri = hlsStore.offlineUri(rawUrl)
                val currentDownloader = hlsStore.createDownloader(task.id, rawUrl)
                downloader = currentDownloader
                synchronized(lock) {
                    if (tasks[task.id]?.status != TvDownloadStatus.DOWNLOADING) {
                        throw IOException("下載已暫停或取消")
                    }
                    activeHlsDownloaders[task.id] = currentDownloader
                    tasks[task.id] = task.copy(
                        filePath = hlsMarkerPath(task.filePath),
                        mediaFormat = "hls",
                        mediaUri = offlineUri,
                        total = plan.estimatedSize.coerceAtLeast(0L),
                    )
                    persistLocked()
                }
                publish()
                val maxBytes = java.util.concurrent.atomic.AtomicLong(0L)
                val reportedBytes = java.util.concurrent.atomic.AtomicLong(0L)
                currentDownloader.download { contentLength, bytesDownloaded, percentDownloaded ->
                    val currentBytes = maxBytes.updateAndGet { old -> maxOf(old, bytesDownloaded) }
                    if (currentBytes > MAX_FILE_BYTES) {
                        if (sizeExceeded.compareAndSet(false, true)) currentDownloader.cancel()
                    } else if (currentBytes - reportedBytes.get() >= PROGRESS_INTERVAL || percentDownloaded >= 100f) {
                        reportedBytes.set(currentBytes)
                        update(task.id) {
                            it.copy(
                                bytes = currentBytes,
                                total = contentLength.takeIf { n -> n > 0L } ?: plan.estimatedSize.coerceAtLeast(0L),
                            )
                        }
                    }
                }
                if (sizeExceeded.get()) {
                    hlsStore.remove(task.id)
                    throw IOException("HLS 檔案超過 2 GB 下載上限")
                }
                ensureRunning(task.id)
                val downloaded = hlsStore.downloadedBytes(task.id)
                update(task.id) {
                    it.copy(status = TvDownloadStatus.COMPLETED, bytes = downloaded, total = downloaded, error = "")
                }
                return
            } catch (failure: Exception) {
                val currentStatus = synchronized(lock) { tasks[task.id]?.status }
                synchronized(lock) {
                    if (activeHlsDownloaders[task.id] === downloader) activeHlsDownloaders.remove(task.id)
                }
                if (sizeExceeded.get()) {
                    runCatching { hlsStore.remove(task.id) }
                    throw IOException("HLS 檔案超過 2 GB 下載上限")
                }
                if (currentStatus != TvDownloadStatus.DOWNLOADING || Thread.currentThread().isInterrupted) {
                    throw failure
                }
                if (index == urls.lastIndex) throw failure
                lastFailure = failure
                runCatching { hlsStore.remove(task.id) }
                update(task.id) {
                    it.copy(bytes = 0L, total = plan.estimatedSize.coerceAtLeast(0L), error = "")
                }
            }
        }
        throw lastFailure ?: IOException("HLS 下載失敗")
    }

    private fun ensureRunning(id: String) {
        val state = synchronized(lock) { tasks[id]?.status }
        if (state != TvDownloadStatus.DOWNLOADING || Thread.currentThread().isInterrupted)
            throw IOException("下载已暂停或取消")
    }

    private inline fun update(id: String, transform: (TvDownloadTask) -> TvDownloadTask) {
        synchronized(lock) {
            tasks[id]?.let { tasks[id] = transform(it); persistLocked() }
        }
        publish()
    }

    private fun publish() {
        val value = snapshot()
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private fun persistLocked() {
        val array = JSONArray()
        tasks.values.forEach { array.put(encode(it)) }
        preferences.edit()
            .putString("tasks", array.toString())
            .putBoolean("execution_active", executionSessionActive)
            .apply()
    }

    private fun encode(task: TvDownloadTask) = JSONObject().apply {
        put("id", task.id); put("seriesId", task.seriesId); put("seriesTitle", task.seriesTitle)
        put("episodeId", task.episodeId); put("episodeNumber", task.episodeNumber)
        put("episodeCount", task.episodeCount); put("maxQuality", task.maxQuality)
        put("source", task.source); put("status", task.status.name); put("bytes", task.bytes); put("total", task.total)
        put("filePath", task.filePath); put("error", task.error); put("fingerprint", task.fingerprint)
        put("mediaFormat", task.mediaFormat); put("mediaUri", task.mediaUri)
    }

    private fun decode(row: JSONObject): TvDownloadTask? = runCatching {
        TvDownloadTask(
            id = row.getString("id"), seriesId = row.getString("seriesId"),
            seriesTitle = row.optString("seriesTitle"), episodeId = row.getString("episodeId"),
            episodeNumber = row.optInt("episodeNumber", 1),
            episodeCount = row.optInt("episodeCount", 0),
            maxQuality = row.optInt("maxQuality", 1080).coerceAtLeast(1),
            source = row.optString("source"),
            status = TvDownloadStatus.valueOf(row.optString("status", "PAUSED")),
            bytes = row.optLong("bytes"), total = row.optLong("total"),
            filePath = row.optString("filePath"), error = row.optString("error"),
            fingerprint = row.optString("fingerprint"),
            mediaFormat = row.optString("mediaFormat", "progressive"),
            mediaUri = row.optString("mediaUri"),
        )
    }.getOrNull()

    private fun safeFailure(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("超时") -> "网络请求超时"
            message.contains("HTTP") -> message.take(80)
            message.contains("可播放") || message.contains("仅返回") || message.contains("HLS") || message.contains("DASH") ->
                message.take(120)
            else -> "下载失败，请重试"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val CHUNK_BYTES = 1024 * 1024
        private const val PROGRESS_INTERVAL = 512 * 1024L
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
