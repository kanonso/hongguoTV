// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenHongguoMediaTest {
    @Test
    fun progressiveMappingDoesNotAccidentallyAcceptPlaylistFormats() {
        val progressive = representation(OpenHongguoMediaKind.PROGRESSIVE)
        val hls = representation(OpenHongguoMediaKind.HLS)
        val dash = representation(OpenHongguoMediaKind.DASH)

        assertNotNull(progressive.toProgressiveRendition())
        assertNull(hls.toProgressiveRendition())
        assertNull(dash.toProgressiveRendition())
    }

    @Test
    fun protectedProgressiveNeedsTheExpectedCencKey() {
        val missing = representation(OpenHongguoMediaKind.PROGRESSIVE, protected = true, contentKey = null)
        val unsupported = representation(
            OpenHongguoMediaKind.PROGRESSIVE,
            protected = true,
            encryption = "sample-aes",
            contentKey = ByteArray(16),
        )
        val valid = representation(
            OpenHongguoMediaKind.PROGRESSIVE,
            protected = true,
            encryption = "cenc-aes-ctr",
            contentKey = ByteArray(16) { it.toByte() },
        )

        assertNull(missing.toProgressiveRendition())
        assertNull(unsupported.toProgressiveRendition())
        assertEquals(16, valid.toProgressiveRendition()?.key?.size)
    }

    private fun representation(
        kind: OpenHongguoMediaKind,
        protected: Boolean = false,
        encryption: String = "",
        contentKey: ByteArray? = null,
    ) = OpenHongguoRepresentation(
        definition = "720p",
        quality = 720,
        codec = "h264",
        width = 1280,
        height = 720,
        bitrate = 1_000_000,
        size = 42_000_000,
        url = "https://media.invalid/video",
        backupUrl = "https://backup.invalid/video",
        kind = kind,
        protected = protected,
        encryption = encryption,
        contentKey = contentKey,
    )
}
