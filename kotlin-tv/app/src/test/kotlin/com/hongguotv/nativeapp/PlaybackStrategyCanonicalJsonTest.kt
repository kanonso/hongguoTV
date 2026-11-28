// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class PlaybackStrategyCanonicalJsonTest {
    @Test
    fun embeddedSignedPayloadMatchesCanonicalJsonExactly() {
        val app = RuntimeEnvironment.getApplication()
        val envelopeText = app.resources.openRawResource(R.raw.hongguo_playback_pack)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        val envelope = JSONObject(envelopeText)
        val payloadBytes = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        val payloadText = String(payloadBytes, StandardCharsets.UTF_8)
        val payload = JSONObject(payloadText)

        assertEquals(payloadText, canonicalStrategyJson(payload))
        assertFalse(canonicalStrategyJson(payload).contains("\\/"))
    }

    @Test
    fun canonicalStringEscapesOnlyJsonRequiredCharacters() {
        assertEquals(
            "\"https://example.invalid/a/b\"",
            canonicalStrategyJsonString("https://example.invalid/a/b"),
        )
        assertEquals(
            "\"quote=\\\" slash=/ backslash=\\\\\"",
            canonicalStrategyJsonString("quote=\" slash=/ backslash=\\"),
        )
        assertEquals(
            "\"line1\\nline2\\tend\\u0001\"",
            canonicalStrategyJsonString("line1\nline2\tend\u0001"),
        )
    }

    @Test
    fun constructingTheManagerAcceptsTheEmbeddedEd25519Pack() {
        val app = RuntimeEnvironment.getApplication()
        val status = PlaybackStrategyPackManager(app).status()

        assertTrue(status.enabled)
        assertEquals(1, status.revision)
        assertEquals(7, status.routeCount)
        assertEquals("primary-2026-11", status.keyId)
    }
}
