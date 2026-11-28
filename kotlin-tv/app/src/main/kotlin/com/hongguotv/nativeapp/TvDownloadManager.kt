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

internal enum class TvDownloadStatus { PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

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
    private val requestExecution: () -> Unit,
) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "downloads").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("tv-download-queue", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val activeVideos = mutableMapOf<String, RemoteVideo>()
    private val tasks = linkedMapOf<String, TvDownloadTask>()
    private val listeners = CopyOnWriteArrayList<(List<TvDownloadTask>) -> Unit>()
    private var draining = false
    private var executionAllowed = false

    init {
        val array = runCatching { JSONArray(preferences.getString("tasks", "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val restored = decode(row) ?: continue
            tasks[restored.id] = if (restored.status == TvDownloadStatus.DOWNLOADING)
                restored.copy(status = TvDownloadStatus.PENDING, bytes = File(restored.filePath + ".part").takeIf(File::exists)?.length() ?: 0)
            else restored
        }
        persistLocked()
        // Construction restores state only. The OS job/service starts network work explicitly.
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
        requestExecution()
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
            requestExecution()
        }
    }

    fun pause(id: String) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status !in setOf(TvDownloadStatus.PENDING, TvDownloadStatus.DOWNLOADING)) return
            tasks[id] = task.copy(status = TvDownloadStatus.PAUSED)
            activeVideos[id]?.close()
            persistLocked()
        }
        publish()
    }

    fun resume(id: String) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status !in setOf(TvDownloadStatus.PAUSED, TvDownloadStatus.FAILED)) return
            tasks[id] = task.copy(status = TvDownloadStatus.PENDING, error = "")
            persistLocked()
        }
        publish()
        requestExecution()
    }

    fun resumePending() {
        synchronized(lock) { executionAllowed = true }
        drain()
    }

    fun suspendForSystemStop() {
        synchronized(lock) {
            executionAllowed = false
            tasks.entries.toList().forEach { (id, task) ->
                if (task.status == TvDownloadStatus.DOWNLOADING) {
                    val bytes = File(task.filePath + ".part").takeIf(File::exists)?.length() ?: task.bytes
                    tasks[id] = task.copy(status = TvDownloadStatus.PENDING, bytes = bytes, error = "")
                }
            }
            activeVideos.values.toList().forEach { it.close() }
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
            tasks[id] = task.copy(status = TvDownloadStatus.CANCELLED, error = "已取消")
            File(task.filePath + ".part").delete()
            persistLocked()
        }
        publish()
    }

    fun remove(id: String, deleteFile: Boolean = false) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            if (task.status == TvDownloadStatus.DOWNLOADING) return
            if (deleteFile) File(task.filePath).delete()
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
                        if (!pending) executionAllowed = false
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
            opened = open(task.episodeId, task.seriesTitle, task.episodeNumber, task.episodeCount, task.source, task.maxQuality)
            val video = opened.video
            synchronized(lock) {
                if (tasks[id]?.status != TvDownloadStatus.DOWNLOADING) throw IOException("下载已暂停或取消")
                activeVideos[id] = video
            }
            val total = video.total
            if (total <= 0L || total > MAX_FILE_BYTES) throw IOException("文件大小无效或超过 2GB 下载上限")
            val part = File(task.filePath + ".part")
            val target = File(task.filePath)
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
            when (current?.status) {
                TvDownloadStatus.PAUSED, TvDownloadStatus.CANCELLED -> {
                    update(id) { it.copy(bytes = File(it.filePath + ".part").takeIf(File::exists)?.length() ?: it.bytes) }
                }
                TvDownloadStatus.PENDING -> {
                    update(id) { it.copy(bytes = File(it.filePath + ".part").takeIf(File::exists)?.length() ?: it.bytes) }
                }
                else -> update(id) {
                    it.copy(status = TvDownloadStatus.FAILED,
                        bytes = File(it.filePath + ".part").takeIf(File::exists)?.length() ?: it.bytes,
                        error = safeFailure(failure))
                }
            }
        } finally {
            opened?.close()
            synchronized(lock) { activeVideos.remove(id) }
        }
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
        preferences.edit().putString("tasks", array.toString()).apply()
    }

    private fun encode(task: TvDownloadTask) = JSONObject().apply {
        put("id", task.id); put("seriesId", task.seriesId); put("seriesTitle", task.seriesTitle)
        put("episodeId", task.episodeId); put("episodeNumber", task.episodeNumber)
        put("episodeCount", task.episodeCount); put("maxQuality", task.maxQuality)
        put("source", task.source); put("status", task.status.name); put("bytes", task.bytes); put("total", task.total)
        put("filePath", task.filePath); put("error", task.error); put("fingerprint", task.fingerprint)
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
        )
    }.getOrNull()

    private fun safeFailure(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("超时") -> "网络请求超时"
            message.contains("HTTP") -> message.take(80)
            message.contains("可播放") -> message.take(80)
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
