// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Rendition

/** Media container/transport supplied by the openhongguo playback API. */
internal enum class OpenHongguoMediaKind { PROGRESSIVE, HLS, DASH }

/**
 * One representation returned by the SDK. The SDK-derived CENC key stays in memory only and is
 * deliberately excluded from task persistence, diagnostics and toString output.
 */
internal data class OpenHongguoRepresentation(
    val definition: String,
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val bitrate: Long,
    val size: Long,
    val url: String,
    val backupUrl: String?,
    val kind: OpenHongguoMediaKind,
    val protected: Boolean,
    val encryption: String,
    val contentKey: ByteArray?,
) {
    fun toProgressiveRendition(): Rendition? {
        if (kind != OpenHongguoMediaKind.PROGRESSIVE) return null
        if (protected && (contentKey == null || encryption != "cenc-aes-ctr")) return null
        return Rendition(
            quality = quality,
            codec = codec,
            width = width,
            height = height,
            url = url,
            key = contentKey?.clone(),
            backupUrl = backupUrl,
            source = "openhongguo",
        )
    }
}

/** Complete resolved SDK model, including the separate DASH audio URL. */
internal data class OpenHongguoResolvedMedia(
    val episodeId: String,
    val expiresAt: Long,
    val durationSeconds: Int,
    val audioUrl: String,
    val representations: List<OpenHongguoRepresentation>,
    val contentKeyError: String,
)
