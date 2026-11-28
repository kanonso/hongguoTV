// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

class SearchUnavailableException(message: String, cause: Throwable? = null): IOException(message, cause)

/** Parse one upstream tab without silently mixing comics and short dramas. */
internal object SearchPayload {
    data class Page(val items: List<Series>, val hasMore: Boolean, val searchId: String,
                    val passback: String, val nextOffset: Int?)

    fun parse(body: String, type: ContentType, keyword: String): Page {
        if (body.isBlank()) throw SearchUnavailableException("搜索服务返回空内容，请稍后重试；这不一定是电视网络故障")
        val data = try { JSONObject(body.removePrefix("\uFEFF")) }
            catch (e: Exception) { throw SearchUnavailableException("搜索服务返回了无法识别的内容，请稍后重试", e) }
        val code = data.optInt("code", data.optInt("Code", -1))
        if (code != 0) throw SearchUnavailableException("搜索服务暂不可用（错误码 $code），请稍后重试")
        val tabs = data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw SearchUnavailableException("搜索数据格式已变化，需要更新内容接口")
        val tab = tabs.objects().firstOrNull { it.optInt("tab_type") == type.searchTab }
            ?: throw SearchUnavailableException("${type.label}搜索暂不可用，请稍后重试")
        val rows = tab.optJSONArray("data")
            ?: throw SearchUnavailableException("搜索列表格式已变化，需要更新内容接口")
        val items = mutableListOf<Series>()
        for (cell in rows.objects()) {
            val cells = mutableListOf(cell)
            val embedded = when (val raw = cell.opt("lynx_data")) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
            embedded?.optJSONArray("cell_data")?.objects()?.let { cells.addAll(it) }
            for (row in cells) {
                val videos = when (val raw = row.opt("video_data")) {
                    is JSONArray -> raw.objects()
                    is JSONObject -> listOf(raw)
                    else -> emptyList()
                }
                for (video in videos) {
                    // Search responses may mix cards, recommendations and non-video book types.
                    // Only map the playable short-drama / animated-drama records into the TV catalog.
                    val contentType = video.optString("content_type", row.optString("content_type", "1004"))
                    if (contentType.isNotBlank() && contentType !in setOf("1003", "1004")) continue
                    val album = video.optJSONObject("album_info")
                    val id = if (contentType == "1003") {
                        album?.str("album_id_str").orEmpty().ifEmpty { album?.str("album_id").orEmpty() }
                    } else {
                        video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                    }
                    // Multi-season cells contain one heading and several videos. Prefer each video's
                    // own title; the highlighted heading is only a final fallback.
                    val highlighted = row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty()
                    val title = cleanTitle(
                        if (contentType == "1003") album?.str("title").orEmpty() else video.str("series_name")
                            .ifEmpty { video.str("series_title") }.ifEmpty { video.str("title") }
                    ).ifEmpty { cleanTitle(highlighted).ifEmpty { cleanTitle(row.str("cell_name")) } }
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
                    val count = if (contentType == "1003") album?.optInt("episode_cnt") ?: 0 else video.optInt("episode_cnt")
                    if (contentType == "1003" && count <= 0) continue
                    items += Series(
                        id,
                        title,
                        cleanUrl(album?.str("cover").orEmpty().ifEmpty {
                            video.str("cover").ifEmpty { video.str("cover_url").ifEmpty { video.str("series_cover") } }
                        }),
                        cleanTitle(album?.str("intro").orEmpty().ifEmpty {
                            video.str("video_desc").ifEmpty { video.str("series_intro") }
                        }),
                        if (count > 0) "全 $count 集" else video.str("rec_text"),
                        video.str("sub_title"),
                    )
                }
            }
        }
        val more = when (val raw = tab.opt("has_more")) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw == "1" || raw.equals("true", ignoreCase = true)
            else -> false
        }
        return Page(prioritize(items.distinctBy { it.source to it.id }, keyword), more,
            tab.str("search_id"), tab.str("passback"), tab.str("next_offset").toIntOrNull()?.takeIf { it >= 0 })
    }

    fun hasExactMatch(items: List<Series>, keyword: String): Boolean {
        val query = normalized(keyword)
        return query.isNotEmpty() && items.any { normalized(it.title) == query }
    }

    /** Merge independent App and website pages without losing seasons or source pagination. */
    fun merge(primary: List<Series>, supplemental: List<Series>, keyword: String): List<Series> =
        prioritize((primary + supplemental).distinctBy { it.id }, keyword)

    fun prioritize(items: List<Series>, keyword: String): List<Series> {
        val query = normalized(keyword)
        if (query.isEmpty()) return items
        // The server resolves pinyin/initials; Chinese titles cannot contain the Latin query.
        if (query.any { it in 'a'..'z' } && query.all { it in 'a'..'z' || it.isDigit() }) return items
        // Keep the requested title and its other seasons, not unrelated upstream recommendations.
        val seriesQuery = query.replace(Regex("第[零〇一二三四五六七八九十百千万两0-9]+[季部集]"), "")
        val matching = items.filter { item ->
            val title = normalized(item.title)
            title.contains(query) || (seriesQuery.length >= 2 && title.contains(seriesQuery))
        }
        // Search APIs may match aliases, pinyin, abbreviations or category hints that are not
        // present in the displayed title. Only narrow the page when we have a positive textual
        // match; never turn an otherwise valid server page into "no results" by guessing.
        val candidates = matching.ifEmpty { items }
        return candidates.sortedBy { item ->
            val title = normalized(item.title)
            when { title == query -> 0; title.startsWith(query) -> 1; title.contains(query) -> 2; else -> 3 }
        }
    }

    private fun cleanTitle(value: String): String = Jsoup.parse(value).text().trim()
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}


/**
 * Merge search pages from independent catalogs. IDs are source identifiers and are only deduplicated
 * when identical; differently numbered seasons remain separate. Exact and prefix title matches rank
 * first, while the caller retains each provider's pagination state.
 */
object CatalogSearchMerge {
    fun merge(primary: CatalogPage, supplemental: CatalogPage, keyword: String): CatalogPage {
        val query = normalize(keyword)
        fun score(item: Series): Int {
            val title = normalize(item.title)
            return when {
                title == query -> 0
                query.isNotEmpty() && title.startsWith(query) -> 1
                query.isNotEmpty() && title.contains(query) -> 2
                query.isNotEmpty() && query.contains(title) -> 3
                else -> 4
            }
        }
        val merged = (primary.items + supplemental.items)
            .filter { it.id.matches(Regex("[0-9]{6,30}")) && it.title.isNotBlank() }
            .distinctBy { it.id }
            .sortedWith(compareBy<Series> { score(it) }.thenBy { it.title })
        return CatalogPage(
            items = merged,
            hasMore = primary.hasMore || supplemental.hasMore,
            ranking = primary.ranking ?: supplemental.ranking,
            discovery = primary.discovery ?: supplemental.discovery,
            trialHints = primary.trialHints + supplemental.trialHints,
        )
    }

    private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
}
