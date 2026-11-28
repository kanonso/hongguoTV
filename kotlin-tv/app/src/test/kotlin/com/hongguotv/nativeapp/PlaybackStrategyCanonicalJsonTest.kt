// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackStrategyCanonicalJsonTest {
    @Test
    fun constructingTheManagerAcceptsTheEmbeddedEd25519Pack() {
        val app = RuntimeEnvironment.getApplication()
        val status = PlaybackStrategyPackManager(app).status()

        assertTrue(status.enabled)
        assertEquals(1, status.revision)
        assertEquals(7, status.routeCount)
        assertEquals("primary-2026-11", status.keyId)
    }

    @Test
    fun modifyingTheSignedPayloadWithoutItsSignatureIsRejected() {
        val app = RuntimeEnvironment.getApplication()
        val manager = PlaybackStrategyPackManager(app)
        val envelopeText = app.resources.openRawResource(R.raw.hongguo_playback_pack)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        val envelope = JSONObject(envelopeText)
        val method = manager.javaClass.getDeclaredMethod("decodeEnvelope", JSONObject::class.java)
        method.isAccessible = true

        val changedPayload = String(
            Base64.decode(envelope.getString("payload"), Base64.DEFAULT),
            StandardCharsets.UTF_8,
        ).replace("\"revision\":1", "\"revision\":2")
        envelope.put(
            "payload",
            Base64.encodeToString(changedPayload.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
        )

        val failure = try {
            method.invoke(manager, envelope)
            throw AssertionError("Modified payload unexpectedly passed signature verification")
        } catch (expected: InvocationTargetException) {
            expected.targetException
        }
        assertTrue(failure is IOException)
        assertTrue(failure.message.orEmpty().contains("Ed25519"))
    }

    @Test
    fun modifyingTheSignatureIsRejected() {
        val app = RuntimeEnvironment.getApplication()
        val manager = PlaybackStrategyPackManager(app)
        val envelopeText = app.resources.openRawResource(R.raw.hongguo_playback_pack)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        val envelope = JSONObject(envelopeText)
        val signature = Base64.decode(envelope.getString("signature"), Base64.DEFAULT)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        envelope.put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))

        val method = manager.javaClass.getDeclaredMethod("decodeEnvelope", JSONObject::class.java)
        method.isAccessible = true
        val failure = try {
            method.invoke(manager, envelope)
            throw AssertionError("Modified signature unexpectedly passed verification")
        } catch (expected: InvocationTargetException) {
            expected.targetException
        }
        assertTrue(failure is IOException)
        assertTrue(failure.message.orEmpty().contains("Ed25519"))
    }
}
