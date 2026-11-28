// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

/** A verified, data-only route override from the signed playback strategy pack. */
data class PlaybackStrategyRoute(
    val id: String,
    val enabled: Boolean,
    val order: Int,
    val auth: String,
    val url: String?,
    val values: Map<String, Any>,
    val body: Map<String, Any>,
    val headers: Map<String, String>,
    val field21: Int?,
)

data class PlaybackStrategyPolicy(
    val revision: Int,
    val keyId: String,
    val issuedAt: String,
    val expiresAt: String?,
    val routes: List<PlaybackStrategyRoute>,
) {
    fun route(id: String): PlaybackStrategyRoute? = routes.firstOrNull { it.id == id && it.enabled }
    fun orderedRoutes(): List<PlaybackStrategyRoute> = routes.filter { it.enabled }.sortedBy { it.order }
}


internal fun PlaybackStrategyRoute.mergeQueryValues(base: Map<String, String>): Map<String, String> =
    base + values.mapValues { (_, value) -> value.toString() }

internal fun PlaybackStrategyRoute.applyBodyOverrides(original: ByteArray?): ByteArray? {
    if (body.isEmpty()) return original
    val root = if (original == null || original.isEmpty()) JSONObject()
        else runCatching { JSONObject(String(original, Charsets.UTF_8)) }.getOrElse {
            throw IOException("策略 body 覆寫只允許 JSON 請求")
        }
    body.forEach { (path, value) ->
        val parts = path.split('.')
        require(parts.isNotEmpty() && parts.all { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) })
        var node = root
        parts.dropLast(1).forEach { part ->
            val child = node.optJSONObject(part) ?: JSONObject().also { node.put(part, it) }
            node = child
        }
        node.put(parts.last(), value)
    }
    return root.toString().toByteArray(Charsets.UTF_8)
}

internal fun PlaybackStrategyRoute.applyHeaders(headers: MutableMap<String, String>) {
    this.headers.forEach { (key, value) ->
        when (key.lowercase()) {
            "user-agent" -> headers["User-Agent"] = value
            "accept" -> headers["Accept"] = value
        }
    }
}
