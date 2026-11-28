// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

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
