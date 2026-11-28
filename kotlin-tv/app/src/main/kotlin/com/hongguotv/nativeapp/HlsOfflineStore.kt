// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class HlsDownloadPlan(
    val url: String,
    val definition: String,
    val quality: Int,
    val estimatedSize: Long,
)

internal data class HlsVodPlaylistStats(
    val playlistCount: Int,
    val segmentCount: Int,
    val playlistBytes: Long,
)

/**
 * Validates every playlist in an HLS VOD graph before handing it to Media3.
 * Live and low-latency playlists are rejected rather than silently saving only a moving window.
 */
internal object HlsVodPlaylistValidator {
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_PLAYLIST_BYTES = 8 * 1024 * 1024
    private const val MAX_PLAYLISTS = 64
    private const val MAX_SEGMENTS = 10000
    private const val MAX_DEPTH = 4
    private val uriAttribute = Regex("""URI="([^"]+)"""")
    private val encryptionMethod = Regex("""METHOD=([A-Z0-9-]+)""")

    fun validate(rootUrl: String, load: (String) -> String): HlsVodPlaylistStats {
        val state = State()
        validatePlaylist(requireHttps(rootUrl), 0, state, load)
        if (state.segments == 0) throw IOException("HLS 播放清單沒有媒體分段")
        return HlsVodPlaylistStats(state.playlists, state.segments, state.bytes)
    }

    private class State {
        val visited = linkedSetOf<String>()
        var playlists = 0
        var segments = 0
        var bytes = 0L
    }

    private fun validatePlaylist(
        url: HttpUrl,
        depth: Int,
        state: State,
        load: (String) -> String,
    ) {
        if (depth > MAX_DEPTH) throw IOException("HLS 播放清單巢狀層級過深")
        val normalized = url.toString()
        if (!state.visited.add(normalized)) return
        state.playlists++
        if (state.playlists > MAX_PLAYLISTS) throw IOException("HLS 播放清單數量超過上限")
        val text = load(normalized)
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_PLAYLIST_BYTES) throw IOException("HLS 播放清單超過 2 MB")
        state.bytes += size
        if (state.bytes > MAX_TOTAL_PLAYLIST_BYTES) throw IOException("HLS 播放清單總量超過 8 MB")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("HLS 播放清單格式無效")
        if (lines.any { it.startsWith("#EXT-X-DEFINE") }) throw IOException("HLS 動態變數尚未支援")
        if (lines.any { it.startsWith("#EXT-X-PART:") || it.startsWith("#EXT-X-SKIP:") }) {
            throw IOException("HLS 低延遲／增量播放清單尚未支援")
        }
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:") }) {
            throw IOException("HLS session-level key 尚未驗證支援，拒絕下載")
        }
        lines.filter { it.startsWith("#EXT-X-KEY:") }
            .forEach { line ->
                val method = encryptionMethod.find(line)?.groupValues?.get(1)
                    ?: throw IOException("HLS 加密方法欄位缺失")
                if (method !in setOf("NONE", "AES-128")) {
                    throw IOException("HLS 加密方法 $method 尚未支援")
                }
            }

        val childPlaylists = linkedSetOf<HttpUrl>()
        var expectsVariantUri = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> expectsVariantUri = true
                expectsVariantUri && !line.startsWith("#") -> {
                    childPlaylists += resolveHttps(url, line)
                    expectsVariantUri = false
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val type = Regex("""TYPE=([A-Z0-9-]+)""").find(line)?.groupValues?.get(1)
                    if (type == "AUDIO") {
                        uriAttribute.find(line)?.groupValues?.get(1)?.let {
                            childPlaylists += resolveHttps(url, it)
                        }
                    }
                }
                !line.startsWith("#") -> {
                    resolveHttps(url, line)
                    state.segments++
                    if (state.segments > MAX_SEGMENTS) throw IOException("HLS 分段數量超過上限")
                }
            }
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:")) {
                uriAttribute.find(line)?.groupValues?.get(1)?.let { resolveHttps(url, it) }
            }
        }
        if (expectsVariantUri) throw IOException("HLS 主播放清單缺少變體 URL")
        if (childPlaylists.isNotEmpty()) {
            childPlaylists.forEach { validatePlaylist(it, depth + 1, state, load) }
            return
        }
        if (lines.none { it == "#EXT-X-ENDLIST" }) {
            throw IOException("HLS 不是完整 VOD（缺少 EXT-X-ENDLIST），拒絕下載即時串流")
        }
    }

    private fun requireHttps(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw IOException("HLS URL 格式無效")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) {
            throw IOException("HLS 僅允許標準 HTTPS 媒體來源")
        }
        return url
    }

    private fun resolveHttps(base: HttpUrl, reference: String): HttpUrl {
        val resolved = base.resolve(reference.trim()) ?: throw IOException("HLS 分段 URL 格式無效")
        if (resolved.scheme != "https" || resolved.username.isNotEmpty() ||
            resolved.password.isNotEmpty() || resolved.port != 443) {
            throw IOException("HLS playlist 包含非 HTTPS 或非標準連接埠 URL")
        }
        return resolved
    }
}

/** Persistent HLS VOD cache. Cache namespaces are per task and strip expiring URL query strings. */
@OptIn(UnstableApi::class)
internal class HlsOfflineStore(context: Context) {
    private val app = context.applicationContext
    private val database = StandaloneDatabaseProvider(app)
    private val cache = SimpleCache(
        File(app.filesDir, "hls-offline-cache"),
        NoOpCacheEvictor(),
        database,
    )
    private val segmentExecutor = Executors.newFixedThreadPool(3)
    private val playlistClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    fun offlineUri(rawUrl: String): String {
        val safe = rawUrl.toHttpUrlOrNull() ?: throw IOException("HLS URL 格式無效")
        if (safe.scheme != "https" || safe.username.isNotEmpty() || safe.password.isNotEmpty() || safe.port != 443) {
            throw IOException("HLS 僅允許 HTTPS URL")
        }
        return safe.newBuilder().query(null).fragment(null).build().toString()
    }

    fun createDownloader(taskId: String, rawUrl: String): HlsDownloader {
        HlsVodPlaylistValidator.validate(rawUrl, ::loadPlaylist)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(rawUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        return HlsDownloader.Factory(dataSourceFactory(taskId))
            .setExecutor(segmentExecutor)
            .create(mediaItem)
    }

    fun createMediaSource(taskId: String, offlineUri: String): HlsMediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(offlineUri))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        // Offline playback must not fall through to a signed CDN URL after the URL expires.
        // A cache miss should be visible as an offline-read error, never a silent network request.
        return HlsMediaSource.Factory(dataSourceFactory(taskId, cacheOnly = true)).createMediaSource(mediaItem)
    }

    fun remove(taskId: String) {
        val prefix = "$taskId|"
        cache.getKeys().filter { it.startsWith(prefix) }.forEach(cache::removeResource)
    }

    fun downloadedBytes(taskId: String): Long {
        val prefix = "$taskId|"
        return cache.getKeys().filter { it.startsWith(prefix) }
            .sumOf { key -> cache.getCachedSpans(key).sumOf { span -> span.length.coerceAtLeast(0L) } }
    }

    private fun dataSourceFactory(taskId: String, cacheOnly: Boolean = false): CacheDataSource.Factory {
        val keys = CacheKeyFactory { spec: DataSpec ->
            val stableUri = spec.uri.buildUpon().clearQuery().fragment(null).build().toString()
            "$taskId|$stableUri"
        }
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("hongguoTV/Android")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(mapOf("Referer" to "https://novel.snssdk.com/"))
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(if (cacheOnly) null else upstream)
            .setCacheKeyFactory(keys)
    }

    private fun loadPlaylist(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://novel.snssdk.com/")
            .build()
        playlistClient.newCall(request).execute().use { response ->
            val finalUrl = response.request.url
            if (finalUrl.scheme != "https" || finalUrl.username.isNotEmpty() ||
                finalUrl.password.isNotEmpty() || finalUrl.port != 443) {
                throw IOException("HLS playlist redirected to an unsafe URL")
            }
            if (response.code != 200) throw IOException("HLS playlist HTTP " + response.code)
            val body = response.body ?: throw IOException("HLS playlist response body is missing")
            if (body.contentLength() > 2L * 1024 * 1024) throw IOException("HLS playlist too large")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > 2 * 1024 * 1024) throw IOException("HLS playlist too large")
                }
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
