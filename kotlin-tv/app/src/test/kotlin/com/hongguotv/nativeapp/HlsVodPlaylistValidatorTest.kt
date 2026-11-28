// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HlsVodPlaylistValidatorTest {
    @Test
    fun validatesVodPlaylistAndAes128KeyUris() {
        val root = "https://media.invalid/episode/index.m3u8"
        val stats = HlsVodPlaylistValidator.validate(root) {
            """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXTINF:6,
seg-1.ts
#EXTINF:6,
seg-2.ts
#EXT-X-ENDLIST
"""
        }
        assertEquals(1, stats.playlistCount)
        assertEquals(2, stats.segmentCount)
    }

    @Test
    fun validatesMasterAndAudioMediaPlaylists() {
        val root = "https://media.invalid/master.m3u8"
        val playlists = mapOf(
            root to """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1500000
video/index.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="main",URI="audio/index.m3u8"
""",
            "https://media.invalid/video/index.m3u8" to """#EXTM3U
#EXTINF:4,
v0.m4s
#EXT-X-ENDLIST
""",
            "https://media.invalid/audio/index.m3u8" to """#EXTM3U
#EXTINF:4,
a0.m4s
#EXT-X-ENDLIST
""",
        )
        val stats = HlsVodPlaylistValidator.validate(root) { playlists.getValue(it) }
        assertEquals(3, stats.playlistCount)
        assertEquals(2, stats.segmentCount)
    }

    @Test
    fun rejectsLivePlaylistsAndSampleAes() {
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/live.m3u8") {
                "#EXTM3U\n#EXTINF:6,\nseg.ts\n"
            }
        }
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/encrypted.m3u8") {
                """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
#EXTINF:6,
seg.ts
#EXT-X-ENDLIST
"""
            }
        }
    }

    @Test
    fun rejectsInsecureSubtitleAndIframeUris() {
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/master.m3u8") {
                """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="captions",URI="http://unsafe.invalid/subs.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000000
video.m3u8
"""
            }
        }
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/master.m3u8") {
                """#EXTM3U
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=10000,URI="http://unsafe.invalid/iframe.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000000
video.m3u8
"""
            }
        }
    }

    @Test
    fun rejectsLowLatencyPreloadHintsAndNonIdentityKeyFormats() {
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/low-latency.m3u8") {
                """#EXTM3U
#EXT-X-PRELOAD-HINT:TYPE=PART,URI="part.ts"
#EXTINF:4,
seg.ts
#EXT-X-ENDLIST
"""
            }
        }
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/keyformat.m3u8") {
                """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,KEYFORMAT="com.example.drm",URI="key.bin"
#EXTINF:4,
seg.ts
#EXT-X-ENDLIST
"""
            }
        }
    }

    @Test
    fun rejectsUnverifiedSessionKeys() {
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/session-key.m3u8") {
                """#EXTM3U
#EXT-X-SESSION-KEY:METHOD=AES-128,URI="key.bin"
#EXTINF:4,
v0.ts
#EXT-X-ENDLIST
"""
            }
        }
    }

    @Test
    fun rejectsInsecureSegmentUrlsAndDynamicPlaylistVariables() {
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/unsafe.m3u8") {
                """#EXTM3U
#EXTINF:4,
http://insecure.invalid/v0.ts
#EXT-X-ENDLIST
"""
            }
        }
        assertThrows(java.io.IOException::class.java) {
            HlsVodPlaylistValidator.validate("https://media.invalid/variable.m3u8") {
                """#EXTM3U
#EXT-X-DEFINE:NAME="x",VALUE="y"
#EXTINF:4,
v0.ts
#EXT-X-ENDLIST
"""
            }
        }
    }
}
