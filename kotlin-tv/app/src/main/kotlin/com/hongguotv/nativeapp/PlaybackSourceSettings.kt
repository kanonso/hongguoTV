// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/**
 * TV playback source preferences. The order is persisted independently from the app account/profile.
 * The SDK helper is intentionally marked unavailable until an Android-native adapter exists.
 */
internal class PlaybackSourceSettings(context: Context) {
    data class Source(
        val id: String,
        val title: String,
        val description: String,
        val available: Boolean,
        val defaultEnabled: Boolean,
    )

    private val prefs = context.applicationContext.getSharedPreferences("playback-source-settings", Context.MODE_PRIVATE)

    val sources = listOf(
        Source("openhongguo", "openhongguo 社區 Android SDK", "只播放 openhongguo 搜尋結果；不跨用紅果官方分集 ID", true, true),
        Source("sdk", "SDK helper", "目前上游 helper 沒有 Android TV 執行檔；原生移植完成前不可用", false, false),
        Source("app", "官方 App 播放來源", "使用目前原生 App 簽名播放模型與 TV 相容性處理；遠端策略包更新仍待移植", true, true),
        Source("web", "官網公開 MP4", "官方公開完整 MP4 兜底；畫質可能較低", true, true),
    )

    @Synchronized
    fun order(): List<String> {
        val saved = prefs.getString("order", null).orEmpty().split(',').filter { it in sources.map(Source::id) }
        return (saved + sources.map(Source::id)).distinct()
    }

    @Synchronized
    fun enabled(): Set<String> {
        val saved = prefs.getStringSet("enabled", null)
        return if (saved == null) sources.filter { it.defaultEnabled }.mapTo(linkedSetOf(), Source::id)
        else saved.filterTo(linkedSetOf()) { id -> sources.any { it.id == id && it.available } }
    }

    @Synchronized
    fun move(id: String, delta: Int): List<String> {
        val current = order().toMutableList()
        val index = current.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in current.indices) return current
        val value = current.removeAt(index)
        current.add(target, value)
        prefs.edit().putString("order", current.joinToString(",")).apply()
        return current
    }

    @Synchronized
    fun setEnabled(id: String, value: Boolean): Set<String> {
        val source = sources.firstOrNull { it.id == id } ?: return enabled()
        if (!source.available) return enabled()
        val next = enabled().toMutableSet()
        if (value) next += id else next -= id
        prefs.edit().putStringSet("enabled", next).apply()
        return next
    }

    @Synchronized
    fun reset() {
        prefs.edit().remove("order").remove("enabled").apply()
    }
}
