package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class SearchPayloadTest {
    private fun video(id: String, title: String) = JSONObject().put("series_id", id).put("title", title)
    private fun body(rows: JSONArray, more: Any = false) = JSONObject().put("code", 0)
        .put("search_tabs", JSONArray().put(JSONObject().put("tab_type", 19).put("data", rows).put("has_more", more)))

    @Test fun everySeasonInOneCellIsReturnedWithItsOwnTitle() {
        val row = JSONObject().put("search_high_light", JSONObject().put("title", JSONObject().put("text", "大佬，你该出手了")))
            .put("video_data", JSONArray().put(video("123", "大佬，你该出手了第四季")).put(video("124", "大佬，你该出手了第五季")))
        val result = SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "大佬，你该出手了第五季")
        assertEquals(listOf("124", "123"), result.items.map { it.id })
        assertEquals("大佬，你该出手了第五季", result.items.first().title)
    }

    @Test fun objectVideoAndObjectLynxPayloadAreSupported() {
        val row = JSONObject().put("video_data", video("123", "测试漫剧"))
        val wrapper = JSONObject().put("lynx_data", JSONObject().put("cell_data", JSONArray().put(row)))
        assertEquals("123", SearchPayload.parse(body(JSONArray().put(wrapper)).toString(), ContentType.COMIC, "测试").items.single().id)
    }

    @Test fun stringLynxPayloadAndNestedTabsRemainSupported() {
        val row = JSONObject().put("video_data", JSONArray().put(video("123", "测试漫剧")))
        val wrapper = JSONObject().put("lynx_data", JSONObject().put("cell_data", JSONArray().put(row)).toString())
        val response = JSONObject().put("code", 0).put("data", body(JSONArray().put(wrapper)))
        assertEquals("123", SearchPayload.parse(response.toString(), ContentType.COMIC, "测试").items.single().id)
    }

    @Test fun malformedCellDoesNotHideTheRemainingValidSeasons() {
        val bad = JSONObject().put("lynx_data", "invalid")
        val row = JSONObject().put("video_data", JSONArray().put("not-an-object").put(video("bad", "无效"))
            .put(video("123", "有效标题")).put(video("123", "重复")))
        assertEquals(listOf("123"), SearchPayload.parse(body(JSONArray().put(bad).put(row)).toString(), ContentType.COMIC, "有效").items.map { it.id })
    }

    @Test fun highlightMarkupIsRemovedWithoutTruncatingSeasonSuffix() {
        val row = JSONObject().put("video_data", video("123", ""))
            .put("search_high_light", JSONObject().put("title", JSONObject().put("text", "<em>凡人百世书</em>第八季")))
        assertEquals("凡人百世书第八季", SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "凡人").items.single().title)
    }

    @Test fun numericAndStringPaginationDoNotStopAtFirstPage() {
        for (more in listOf(1, "1", true, "true")) {
            val response = body(JSONArray(), more)
            response.getJSONArray("search_tabs").getJSONObject(0).put("search_id", "session").put("passback", "cursor").put("next_offset", "40")
            val result = SearchPayload.parse(response.toString(), ContentType.COMIC, "测试")
            assertTrue(result.hasMore)
            assertEquals("session", result.searchId)
            assertEquals("cursor", result.passback)
            assertEquals(40, result.nextOffset)
        }
    }

    @Test fun emptyMalformedOrRejectedResponseIsAnActionableError() {
        for (payload in listOf("", "  ", "<html>error</html>", "{\"code\":110001}")) {
            val error = assertThrows(SearchUnavailableException::class.java) { SearchPayload.parse(payload, ContentType.COMIC, "测试") }
            assertFalse(error.message.orEmpty().contains("End of input"))
            assertTrue(error.message.orEmpty().contains("搜索"))
        }
    }

    @Test fun comicRequestNeverReturnsTheShortDramaTab() {
        val response = body(JSONArray().put(JSONObject().put("video_data", video("123", "短剧"))))
        response.getJSONArray("search_tabs").getJSONObject(0).put("tab_type", 11)
        assertThrows(SearchUnavailableException::class.java) { SearchPayload.parse(response.toString(), ContentType.COMIC, "测试") }
    }

    @Test fun exactTitleWinsAndOtherSeasonsRemainWithoutUnrelatedRecommendations() {
        val items = listOf(Series("1", "其他推荐一"), Series("2", "大佬，你该出手了第四季"),
            Series("3", "大佬,你该出手了第五季"), Series("4", "其他推荐二"))
        assertEquals(listOf("3", "2"), SearchPayload.prioritize(items, "大佬，你该出手了第五季").map { it.id })
    }


    @Test fun albumTypeSearchMapsTheAlbumIdAndItsOwnSeasonTitle() {
        val album = JSONObject().put("content_type", 1003).put("album_info",
            JSONObject().put("album_id_str", "770123456789").put("title", "测试作品第五季")
                .put("episode_cnt", 48).put("cover", "https://example.test/cover.jpg"))
        val row = JSONObject().put("video_data", JSONArray().put(album))
        val result = SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "测试作品第五季")
        assertEquals("770123456789", result.items.single().id)
        assertEquals("测试作品第五季", result.items.single().title)
        assertEquals("全 48 集", result.items.single().badge)
    }

    @Test fun nonVideoBookTypesAreNotMappedToPlayableDramaRows() {
        val row = JSONObject().put("video_data", JSONArray().put(
            JSONObject().put("content_type", 9999).put("series_id", "770123456789").put("title", "非短剧内容")))
        val result = SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "非短剧内容")
        assertTrue(result.items.isEmpty())
    }

    @Test fun appAndWebsiteSupplementResultsMergeWithoutDuplicateOrUnrelatedRows() {
        val app = listOf(Series("1", "大佬，你该出手了第五季"), Series("2", "无关推荐"))
        val website = listOf(
            Series("1", "大佬，你该出手了第五季"),
            Series("3", "大佬，你该出手了第四季"),
            Series("4", "另一个无关推荐"),
        )
        val merged = SearchPayload.merge(app, website, "大佬，你该出手了第五季")
        assertEquals(listOf("1", "3"), merged.map { it.id })
        assertEquals(1, merged.count { it.id == "1" })
    }

    @Test fun repositorySearchQueriesAndMergesAppAndWebsiteOnTheSamePage() {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val response = when (request.url.encodedPath) {
                "/reading/bookapi/search/tab/v" -> """
                    {"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[
                      {"video_data":[{"content_type":1004,"series_id":"700001","title":"大佬，你该出手了第五季","episode_cnt":48}]}
                    ]}]}"""
                "/api/search/result" -> """
                    {"pageNum":1,"hasMore":false,"searchList":[
                      {"video_data":{"series_id":"700001","series_title":"大佬，你该出手了第五季","genre":"203"}},
                      {"video_data":{"series_id":"700002","series_title":"大佬，你该出手了第四季","genre":"203"}},
                      {"video_data":{"series_id":"799999","series_title":"无关推荐","genre":"203"}}
                    ]}"""
                else -> error("Unexpected search endpoint: ${request.url.encodedPath}")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, response.trimIndent())).build()
        }.build()
        val page = ContentRepository(http).search("大佬，你该出手了第五季", type = ContentType.SHORT)
        assertEquals(listOf("700001", "700002"), page.items.map { it.id })
        assertEquals(listOf("/reading/bookapi/search/tab/v", "/api/search/result"), requests)
    }

    @Test fun publicMp4FallbackAcceptsOnlyExpectedHttpsMediaAndNoContentKey() {
        val body = """{"base_resp":{"StatusCode":0},"main_url":"https://cdn.fqnovelvod.com/video/episode.mp4","url_expire":4102444800}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("https://hongguoduanju.com/api/video/protected/fallback-play-info", request.url.toString())
            assertTrue(request.header("Referer")?.startsWith("https://hongguoduanju.com/") == true)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, body)).build()
        }.build()
        val result = ContentRepository(http).publicWebsiteMp4Fallback("123456789")
        assertEquals(360, result.quality)
        assertEquals("h264", result.codec)
        assertNull(result.key)
        assertEquals("web", result.source)
        assertEquals("cdn.fqnovelvod.com", result.url.toHttpUrl().host)
    }

    @Test fun publicMp4FallbackRejectsUntrustedMediaHosts() {
        val body = """{"base_resp":{"StatusCode":0},"main_url":"https://attacker.example/episode.mp4"}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, body)).build()
        }.build()
        val failure = runCatching { ContentRepository(http).publicWebsiteMp4Fallback("123456789") }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure?.message.orEmpty().contains("安全校验"))
    }

    @Test fun publicMp4FallbackDoesNotTreatUnavailablePublicEpisodeAsPlayable() {
        val body = """{"base_resp":{"StatusCode":0},"error":"public_fallback_unavailable"}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, body)).build()
        }.build()
        val failure = runCatching { ContentRepository(http).publicWebsiteMp4Fallback("123456789") }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }

    @Test fun strictWebsiteFilterRejectsUnrelatedRecommendations() {
        val items = listOf(Series("1", "跨类型剧"), Series("2", "测试短剧第五季"))
        assertEquals(listOf("2"), SearchPayload.filterTextMatches(items, "测试短剧").map { it.id })
    }

    @Test fun providerAwareCatalogMergeKeepsDistinctIDsAndSeasonsWithoutIDCrossMapping() {
        val primary = CatalogPage(listOf(Series("123456", "目标作品第五季")), hasMore = false)
        val community = CatalogPage(listOf(
            Series("123456", "目标作品第五季", source = "openhongguo"),
            Series("654321", "目标作品第四季", source = "openhongguo"),
        ), hasMore = false)
        val merged = CatalogSearchMerge.merge(primary, community, "目标作品第五季")
        assertEquals(listOf("hongguo", "openhongguo", "openhongguo"), merged.items.map { it.source })
        assertEquals(listOf("123456", "123456", "654321"), merged.items.map { it.id })
    }

    @Test fun pinyinSearchKeepsServerMatchesWithChineseTitles() {
        val items = listOf(Series("1", "重生之路"), Series("2", "重生归来"))
        assertEquals(items, SearchPayload.prioritize(items, "cs"))
        assertEquals(items, SearchPayload.prioritize(items, "chongsheng"))
    }

    @Test fun websiteSearchFiltersGenresAndKeepsPagination() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/search/result",chain.request().url.encodedPath)
            assertNotNull(chain.request().header("Referer"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null,
                    """{"pageNum":1,"hasMore":true,"searchList":[{"video_data":{"series_id":"123","series_title":"漫剧","genre":"205"}},{"video_data":{"series_id":"456","series_title":"短剧","genre":"203"}}]}""")).build()
        }.build())
        assertEquals("123",repo.searchAll("剧",type=ContentType.COMIC).items.single().id)
        assertEquals("456",repo.searchAll("剧",type=ContentType.SHORT).items.single().id)
        val unrelated = repo.searchAll("测试")
        assertTrue(unrelated.items.isEmpty())
        assertTrue(unrelated.hasMore)
    }
}
