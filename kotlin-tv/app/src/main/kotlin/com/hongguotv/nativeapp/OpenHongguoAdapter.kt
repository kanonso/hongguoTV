// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.Rendition
import com.hongguotv.core.Series
import com.hongguotv.core.StreamLadder
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.SearchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/**
 * Android adapter for the official Apache-2.0 openhongguo SDK.
 *
 * The SDK owns a separate no-backup anonymous profile and its own network/signing stack.
 * All calls are made by the caller's background executor; this class bridges its suspend API
 * without blocking the Android main thread.
 */
internal class OpenHongguoAdapter(context: Context) {
    private val appContext = context.applicationContext
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
        val items = result.items.mapNotNull { row ->
            val id = row.id.trim()
            val title = row.title.trim()
            if (!id.matches(Regex("[0-9]{6,30}")) || title.isBlank()) return@mapNotNull null
            Series(
                id = id,
                title = title,
                cover = safeHttps(row.coverUrl),
                description = row.description.trim(),
                badge = if (row.episodeCount > 0) "共 ${row.episodeCount} 集" else "社区 SDK",
                source = "openhongguo",
            )
        }.distinctBy { it.id }
        val next = result.cursor.takeIf { result.hasMore && it.isNotBlank() }
        synchronized(cursorLock) {
            if (next != null) cursors[normalized to page] = next
            while (cursors.size > 300) cursors.remove(cursors.keys.first())
        }
        return CatalogPage(items, next != null)
    }

    /** Fetch every playable episode in order. Cursor changes are checked to avoid a server loop. */
    fun listPlayableEpisodes(seriesId: String): List<Pair<Int, String>> {
        require(seriesId.matches(Regex("[0-9]{6,30}")))
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
     * Safe cross-provider mapping for a Hongguo-native series: search openhongguo by the full
     * displayed series title, require exactly one normalized exact-title match, then map the
     * 1-based episode number from its own episode directory. Never reuse a Hongguo episode ID.
     */
    fun resolvePlaybackBySeriesTitle(
        seriesTitle: String,
        episodeNumber: Int,
        expectedEpisodeCount: Int,
    ): StreamLadder {
        require(
            seriesTitle.isNotBlank() && seriesTitle.length <= 200 &&
                episodeNumber in 1..10000 && expectedEpisodeCount in 1..10000,
        )
        val target = normalizeTitle(seriesTitle)
        if (target.isBlank()) throw IOException("无法识别剧名，跳过 openhongguo 来源")
        val candidates = linkedMapOf<String, String>()
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
                if (row.id.matches(Regex("[0-9]{6,30}")) && normalizeTitle(row.title) == target)
                    candidates[row.id] = row.title
            }
            if (!page.hasMore) {
                searchComplete = true
                break
            }
            if (page.cursor.isBlank() || !seenCursors.add(page.cursor))
                throw IOException("openhongguo 系列映射分页游标异常，无法确认同名结果唯一")
            cursor = page.cursor
        }
        if (!searchComplete) throw IOException("openhongguo 同名系列结果过多，无法确认映射唯一")
        if (candidates.isEmpty()) throw IOException("openhongguo 没有找到同名剧集，安全跳过该来源")
        val matches = candidates.keys.mapNotNull { seriesId ->
            val completeEpisodes = listPlayableEpisodes(seriesId)
            // The host catalogue exposes IDs but not explicit episode labels. Only map by index
            // when the SDK's playable list is complete and numbered exactly 1..N for the same count.
            val completeOrderedDirectory = completeEpisodes.size == expectedEpisodeCount &&
                completeEpisodes.withIndex().all { (index, episode) -> episode.first == index + 1 }
            if (!completeOrderedDirectory) null
            else completeEpisodes.getOrNull(episodeNumber - 1)?.let { seriesId to it.second }
        }
        if (matches.size != 1) {
            throw IOException(
                if (matches.isEmpty()) "openhongguo 同名剧没有对应分集，安全跳过该来源"
                else "openhongguo 找到多个同名分集，无法确认对应关系，安全跳过该来源",
            )
        }
        return resolvePlayback(matches.single().second)
    }

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    /** Resolve the complete SDK media model without discarding HLS, DASH or the separate audio track. */
    fun resolveMedia(episodeId: String): OpenHongguoResolvedMedia {
        require(episodeId.matches(Regex("[0-9]{1,30}")))
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
        return HlsDownloadPlan(chosen.url, chosen.definition, chosen.quality, chosen.size)
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
