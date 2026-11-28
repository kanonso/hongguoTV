// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.Rendition
import com.hongguotv.core.Series
import com.hongguotv.core.StreamLadder
import dev.openhongguo.ClientOptions
import dev.openhongguo.DiscoverOptions
import dev.openhongguo.SeriesSummary as OpenHongguoSeriesSummary
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.SearchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Android adapter for the official Apache-2.0 openhongguo SDK.
 *
 * The SDK owns a separate no-backup anonymous profile and its own network/signing stack.
 * All calls are made by the caller's background executor; this class bridges its suspend API
 * without blocking the Android main thread.
 */
internal class OpenHongguoAdapter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * The upstream SDK is a gomobile AAR and loads libgojni.so before its Kotlin API can be used.
     * Robolectric/JVM tests do not load Android ABI libraries, so keep that native boundary out
     * of host-side tests and expose the real device capability to source settings.
     */
    internal val isRuntimeAvailable: Boolean
        get() = runCatching {
            val directory = appContext.applicationInfo.nativeLibraryDir
            !directory.isNullOrBlank() &&
                java.io.File(directory, System.mapLibraryName("gojni")).isFile
        }.getOrDefault(false)

    private fun requireRuntime() {
        if (!isRuntimeAvailable) {
            throw IOException("openhongguo Android SDK 原生执行库未载入")
        }
    }

    private val client: OpenhongguoClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpenhongguoClient.create(
            appContext,
            ClientOptions(
                retryAttempts = 2,
                deviceResets = 1,
                logger = { /* SDK URLs are redacted; keep routine diagnostics out of UI/logcat. */ },
            ),
        )
    }
    private val cursorLock = Any()
    private val cursors = linkedMapOf<Pair<String, Int>, String>()

    fun search(keyword: String, page: Int): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        requireRuntime()
        val normalized = keyword.trim()
        val cursor = synchronized(cursorLock) {
            if (page == 1) {
                cursors.keys.removeAll { it.first == normalized }
                null
            } else cursors[normalized to (page - 1)]
        }
        if (page > 1 && cursor.isNullOrBlank()) return CatalogPage(emptyList(), false)
        val result = try {
            runBlocking(Dispatchers.IO) {
                client.search(
                    normalized,
                    SearchOptions(cursor = cursor, pageSize = 20),
                )
            }
        } catch (failure: Exception) {
            throw IOException("openhongguo 搜索暂不可用", failure)
        }
        val items = result.items.mapNotNull(::toCatalogSeries).distinctBy { it.id }
        val next = result.cursor.takeIf { result.hasMore && it.isNotBlank() }
        synchronized(cursorLock) {
            if (next != null) cursors[normalized to page] = next
            while (cursors.size > 300) cursors.remove(cursors.keys.first())
        }
        return CatalogPage(items, next != null)
    }

    /** Browse the SDK public recommendation channel independently of Hongguo catalog. */
    fun discover(page: Int): CatalogPage {
        require(page in 1..100)
        requireRuntime()
        val key = "__openhongguo_discover__"
        val cursor = synchronized(cursorLock) {
            if (page == 1) {
                cursors.keys.removeAll { it.first == key }
                null
            } else cursors[key to (page - 1)]
        }
        if (page > 1 && cursor.isNullOrBlank()) return CatalogPage(emptyList(), false)
        val result = try {
            runBlocking(Dispatchers.IO) { client.discover(DiscoverOptions(cursor = cursor)) }
        } catch (failure: Exception) {
            throw IOException("openhongguo 推荐目录暂不可用", failure)
        }
        val items = result.items.mapNotNull(::toCatalogSeries).distinctBy { it.id }
        val next = result.cursor.takeIf { result.hasMore && it.isNotBlank() }
        synchronized(cursorLock) {
            if (next != null) cursors[key to page] = next
            while (cursors.size > 300) cursors.remove(cursors.keys.first())
        }
        return CatalogPage(items, next != null)
    }

    private fun toCatalogSeries(row: OpenHongguoSeriesSummary): Series? {
        val id = row.id.trim()
        val title = row.title.trim()
        if (!id.matches(Regex("[0-9]{6,30}")) || title.isBlank()) return null
        return Series(
            id = id,
            title = title,
            cover = safeHttps(row.coverUrl),
            description = row.description.trim(),
            badge = if (row.episodeCount > 0) "共 " + row.episodeCount + " 集" else "OpenHongguo",
            source = "openhongguo",
        )
    }
    /** Fetch every playable episode in order. Cursor changes are checked to avoid a server loop. */
    fun listPlayableEpisodes(seriesId: String): List<Pair<Int, String>> {
        require(seriesId.matches(Regex("[0-9]{6,30}")))
        requireRuntime()
        val result = mutableListOf<Pair<Int, String>>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        for (pageIndex in 0 until 50) {
            val page = try {
                runBlocking(Dispatchers.IO) {
                    client.listEpisodes(seriesId, dev.openhongguo.EpisodeOptions(cursor = cursor, pageSize = 100))
                }
            } catch (failure: Exception) {
                throw IOException("openhongguo 分集列表暂不可用", failure)
            }
            page.items.forEach { episode ->
                if (episode.playable && episode.number in 1..10000 && episode.id.matches(Regex("[0-9]{1,30}")))
                    result += episode.number to episode.id
            }
            if (!page.hasMore) return result.distinctBy { it.first }.sortedBy { it.first }
            if (page.cursor.isBlank() || !seenCursors.add(page.cursor))
                throw IOException("openhongguo 分集分页游标异常，无法确认目录完整")
            cursor = page.cursor
        }
        throw IOException("openhongguo 分集目录超过最大分页限制，无法确认目录完整")
    }

    fun listEpisodeIds(seriesId: String): List<String> =
        listPlayableEpisodes(seriesId).map { it.second }

    /**
     * Find exactly one normalized full-title match, then verify its complete playable directory
     * against the count published by the original Hongguo card. This fails closed for season
     * collisions, missing pages, unplayable gaps and truncated SDK data.
     */
    fun listCompleteEpisodeDirectoryBySeriesTitle(
        seriesTitle: String,
        expectedEpisodeCount: Int,
    ): List<Pair<Int, String>> {
        require(seriesTitle.isNotBlank() && seriesTitle.length <= 200)
        require(expectedEpisodeCount in 1..10000)
        requireRuntime()
        val target = OpenHongguoDirectoryPolicy.normalizeTitle(seriesTitle)
        if (target.isBlank()) throw IOException("无法识别剧名，跳过 openhongguo 来源")

        val candidates = linkedMapOf<String, OpenHongguoSeriesSummary>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        var searchComplete = false
        for (pageIndex in 0 until 5) {
            val page = try {
                runBlocking(Dispatchers.IO) {
                    client.search(seriesTitle, SearchOptions(cursor = cursor, pageSize = 50))
                }
            } catch (failure: Exception) {
                throw IOException("openhongguo 系列映射搜索暂不可用", failure)
            }
            page.items.forEach { row ->
                if (row.id.matches(Regex("[0-9]{6,30}")) &&
                    OpenHongguoDirectoryPolicy.normalizeTitle(row.title) == target
                ) candidates[row.id] = row
            }
            if (!page.hasMore) {
                searchComplete = true
                break
            }
            if (page.cursor.isBlank() || !seenCursors.add(page.cursor))
                throw IOException("openhongguo 系列映射分页游标异常，无法确认同名结果唯一")
            cursor = page.cursor
        }
        if (!searchComplete)
            throw IOException("openhongguo 同名系列结果超过安全查询范围，无法确认映射唯一")
        if (candidates.size != 1) {
            throw IOException(
                if (candidates.isEmpty()) "openhongguo 没有找到完全同名剧集，安全跳过该来源"
                else "openhongguo 找到多个完全同名剧集，无法确认季别，安全跳过该来源",
            )
        }

        val candidate = candidates.values.single()
        if (candidate.episodeCount > 0 && candidate.episodeCount != expectedEpisodeCount)
            throw IOException("openhongguo 总集数与红果目录不一致，安全跳过该来源")
        val episodes = listPlayableEpisodes(candidate.id)
        if (!OpenHongguoDirectoryPolicy.isCompleteDirectory(expectedEpisodeCount, episodes))
            throw IOException("openhongguo 分集未形成完整连续目录，安全跳过该来源")
        return episodes
    }

    /** Resolve one episode only after the cross-provider directory has passed all checks. */
    fun resolvePlaybackBySeriesTitle(
        seriesTitle: String,
        episodeNumber: Int,
        expectedEpisodeCount: Int,
    ): StreamLadder {
        require(episodeNumber in 1..expectedEpisodeCount)
        val directory = listCompleteEpisodeDirectoryBySeriesTitle(seriesTitle, expectedEpisodeCount)
        val episodeId = directory.getOrNull(episodeNumber - 1)
            ?.takeIf { it.first == episodeNumber }
            ?.second
            ?: throw IOException("openhongguo 没有对应集数，安全跳过该来源")
        return resolvePlayback(episodeId)
    }
    /** Resolve the complete SDK media model without discarding HLS, DASH or the separate audio track. */
    fun resolveMedia(episodeId: String): OpenHongguoResolvedMedia {
        require(episodeId.matches(Regex("[0-9]{1,30}")))
        requireRuntime()
        val result = try {
            runBlocking(Dispatchers.IO) { client.resolvePlayback(episodeId) }
        } catch (failure: Exception) {
            throw IOException("openhongguo 取源暂不可用", failure)
        }
        val key = result.contentKey?.key?.let(::decodeContentKey)
        val representations = result.playback.streams.mapNotNull { stream ->
            val url = safeHttps(stream.url)
            if (url.isBlank()) return@mapNotNull null
            val kind = when {
                stream.hls -> OpenHongguoMediaKind.HLS
                stream.dash -> OpenHongguoMediaKind.DASH
                else -> OpenHongguoMediaKind.PROGRESSIVE
            }
            OpenHongguoRepresentation(
                definition = stream.definition,
                quality = Regex("(\\d{3,4})").find(stream.definition)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                codec = stream.codec.ifBlank { "unknown" }.lowercase(),
                width = stream.width.takeIf { it > 0 },
                height = stream.height.takeIf { it > 0 },
                bitrate = stream.bitrate,
                size = stream.size,
                url = url,
                backupUrl = safeHttps(stream.backupUrl).ifBlank { null },
                kind = kind,
                protected = stream.protected,
                encryption = stream.encryption.lowercase(),
                contentKey = if (stream.protected && stream.encryption.equals("cenc-aes-ctr", true)) key?.clone() else null,
            )
        }
        if (representations.isEmpty()) {
            throw IOException(result.contentKeyError?.message ?: "openhongguo 没有可用的媒体 URL")
        }
        return OpenHongguoResolvedMedia(
            episodeId = result.playback.episodeId,
            expiresAt = result.playback.expiresAt,
            durationSeconds = result.playback.durationSeconds,
            audioUrl = safeHttps(result.playback.audioUrl),
            representations = representations,
            contentKeyError = result.contentKeyError?.message.orEmpty(),
        )
    }

    /** Resolve a complete, clear HLS representation for offline VOD download. */
    fun resolveHls(episodeId: String, maxQuality: Int): HlsDownloadPlan? {
        val media = resolveMedia(episodeId)
        val candidates = media.representations.filter {
            it.kind == OpenHongguoMediaKind.HLS && !it.protected && it.url.isNotBlank()
        }
        if (candidates.isEmpty()) return null
        val eligible = candidates.filter { it.quality in 1..maxQuality.coerceAtLeast(1) }
        val chosen = (eligible.ifEmpty { candidates }).maxWithOrNull(
            compareBy<OpenHongguoRepresentation> { it.quality }.thenBy { it.bitrate },
        ) ?: return null
        if (chosen.size > 2L * 1024 * 1024 * 1024) throw IOException("HLS 檔案超過 2 GB 下載上限")
        return HlsDownloadPlan(
            url = chosen.url,
            definition = chosen.definition,
            quality = chosen.quality,
            estimatedSize = chosen.size,
            backupUrl = chosen.backupUrl,
        )
    }

    /**
     * The current RemoteVideo playback reader accepts progressive MP4 streams only.
     * Keep that boundary explicit; HLS/DASH metadata remains available to the download pipeline.
     */
    fun resolvePlayback(episodeId: String): StreamLadder {
        val media = resolveMedia(episodeId)
        val rows = media.representations.mapNotNull { it.toProgressiveRendition() }
        if (rows.isEmpty()) {
            val formats = media.representations.map { it.kind }.distinct().joinToString("／") {
                when (it) {
                    OpenHongguoMediaKind.HLS -> "HLS"
                    OpenHongguoMediaKind.DASH -> "DASH"
                    OpenHongguoMediaKind.PROGRESSIVE -> "受保护或不兼容的 MP4"
                }
            }
            throw IOException(
                media.contentKeyError.ifBlank { "openhongguo 仅返回 $formats；当前播放器不接受这类串流" },
            )
        }
        return StreamLadder(rows)
    }

    private fun safeHttps(raw: String): String {
        val parsed = raw.trim().toHttpUrlOrNull() ?: return ""
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.port != 443)
            return ""
        return parsed.toString()
    }

    private fun decodeContentKey(hex: String): ByteArray? {
        val value = hex.trim()
        if (value.length != 32 || !value.matches(Regex("[0-9a-fA-F]{32}"))) return null
        return ByteArray(16) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
