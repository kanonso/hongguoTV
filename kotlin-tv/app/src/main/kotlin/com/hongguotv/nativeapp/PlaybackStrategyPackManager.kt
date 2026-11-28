// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Base64
import com.hongguotv.core.PlaybackStrategyPolicy
import com.hongguotv.core.PlaybackStrategyRoute
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Canonical JSON serializer for signed strategy payloads.
 *
 * Android's JSONObject.quote() escapes forward slashes (e.g. https:\/\/), while the
 * publisher's canonical payload preserves '/'. The signature is checked over the raw bytes;
 * this serializer then enforces the exact same deterministic representation without changing
 * those bytes or weakening Ed25519 verification.
 */
internal fun canonicalStrategyJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> {
        val keys = value.keys().asSequence().toList().sorted()
        keys.joinToString(prefix = "{", postfix = "}") { key ->
            canonicalStrategyJsonString(key) + ":" + canonicalStrategyJson(value.opt(key))
        }
    }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
        canonicalStrategyJson(value.opt(it))
    }
    is String -> canonicalStrategyJsonString(value)
    is Number -> JSONObject.numberToString(value)
    is Boolean -> value.toString()
    else -> throw IOException("策略包包含不支持的 JSON 值")
}

/** JSON string quoting which does not escape '/' and uses lowercase hex for control chars. */
internal fun canonicalStrategyJsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}

internal data class PlaybackStrategyStatus(
    val enabled: Boolean,
    val revision: Int,
    val issuedAt: String,
    val lastCheckAt: Long,
    val canRollback: Boolean,
    val source: String,
    val error: String,
    val routeCount: Int,
    val keyId: String,
)

internal class PlaybackStrategyPackManager(context: Context) {
    private data class DecodedPack(
        val envelope: JSONObject,
        val policy: PlaybackStrategyPolicy,
    )

    private val app = context.applicationContext
    private val stateFile = File(app.noBackupFilesDir, "playback-strategy/pack-state.json")
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val lock = Any()

    private val keyRing = mapOf(
        "primary-2026-11" to "MCowBQYDK2VwAyEAjuRI96E/T54X9FP806UEfoS1cwnWPsvPAnePBFSWA6M=",
        "standby-2026-11" to "MCowBQYDK2VwAyEA+WpDTdstYLrCl9EdDwtSV1PrwojElbPnOZk1LoXwnEU=",
    )
    private val mirrors = listOf(
        "https://raw.githubusercontent.com/jackotom/hongguo-drama/main/source-packs/hongguo-playback.pack.json",
        "https://cdn.jsdelivr.net/gh/jackotom/hongguo-drama@main/source-packs/hongguo-playback.pack.json",
        "https://fastly.jsdelivr.net/gh/jackotom/hongguo-drama@main/source-packs/hongguo-playback.pack.json",
    )

    private var enabled = true
    private var active: DecodedPack? = null
    private var previous: DecodedPack? = null
    private var activeSource = "embedded"
    private var previousSource = ""
    private var lastCheckAt = 0L
    private var suppressAutoThroughRevision = 0
    private var lastError = ""

    init {
        synchronized(lock) {
            val embedded = app.resources.openRawResource(R.raw.hongguo_playback_pack).use { input ->
                decodeEnvelope(JSONObject(String(input.readBytes(), StandardCharsets.UTF_8)))
            }
            var savedActive: DecodedPack? = null
            var savedPrevious: DecodedPack? = null
            if (stateFile.isFile) {
                runCatching {
                    val state = JSONObject(stateFile.readText(Charsets.UTF_8))
                    enabled = state.optBoolean("enabled", true)
                    activeSource = state.optString("activeSource", "saved").ifBlank { "saved" }
                    previousSource = state.optString("previousSource")
                    lastCheckAt = state.optLong("lastCheckAt", 0L)
                    suppressAutoThroughRevision = state.optInt("suppressAutoThroughRevision", 0)
                    lastError = state.optString("error")
                    state.optString("activeEnvelope").takeIf { it.isNotBlank() }?.let { savedActive = decodeEnvelope(JSONObject(it)) }
                    state.optString("previousEnvelope").takeIf { it.isNotBlank() }?.let { savedPrevious = decodeEnvelope(JSONObject(it)) }
                }
            }
            active = when {
                savedActive == null -> embedded
                savedActive.policy.revision > embedded.policy.revision -> savedActive
                else -> embedded
            }
            if (active === embedded) activeSource = "embedded"
            previous = savedPrevious
            persistLocked()
        }
    }

    fun policy(): PlaybackStrategyPolicy? = synchronized(lock) { active?.policy }

    fun status(): PlaybackStrategyStatus = synchronized(lock) { statusLocked() }

    fun setEnabled(value: Boolean): PlaybackStrategyStatus = synchronized(lock) {
        enabled = value
        if (value) lastError = "" else lastError = "已关闭自动策略更新；当前验证通过的策略继续生效"
        persistLocked()
        statusLocked()
    }

    fun resetToEmbedded(): PlaybackStrategyStatus = synchronized(lock) {
        val embedded = app.resources.openRawResource(R.raw.hongguo_playback_pack).use { input ->
            decodeEnvelope(JSONObject(String(input.readBytes(), StandardCharsets.UTF_8)))
        }
        active = embedded
        activeSource = "embedded"
        previous = null
        previousSource = ""
        enabled = true
        lastCheckAt = 0L
        suppressAutoThroughRevision = 0
        lastError = ""
        persistLocked()
        statusLocked()
    }

    /**
     * Only a project-signed, canonical, unexpired format-2 JSON envelope is accepted.
     * The downloaded document contains route data only; it is never loaded as code or evaluated.
     */
    fun check(force: Boolean = false): PlaybackStrategyStatus {
        synchronized(lock) {
            if (!enabled) return statusLocked()
            if (!force && System.currentTimeMillis() - lastCheckAt < CHECK_INTERVAL_MS)
                return statusLocked()
        }
        var fetched: DecodedPack? = null
        val failures = mutableListOf<Exception>()
        for (url in mirrors) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "hongguoTV-android-source-pack/2")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code != 200) throw IOException("策略镜像 HTTP ${response.code}")
                    val body = response.body ?: throw IOException("策略镜像没有响应内容")
                    if (body.contentLength() > MAX_PACK_BYTES) throw IOException("策略包过大")
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_PACK_BYTES) throw IOException("策略包为空或过大")
                    fetched = decodeEnvelope(JSONObject(String(bytes, StandardCharsets.UTF_8)))
                }
                if (fetched != null) break
            } catch (problem: Exception) {
                failures += problem
            }
        }
        synchronized(lock) {
            lastCheckAt = System.currentTimeMillis()
            if (!enabled) {
                lastError = "策略自动更新已关闭；现有策略未变更"
                persistLocked()
                return statusLocked()
            }
            val candidate = fetched
            if (candidate == null) {
                lastError = when {
                    failures.isNotEmpty() && failures.all { it.message.orEmpty().contains("HTTP 404") } ->
                        "在线策略镜像暂未发布新版本，继续使用当前策略"
                    failures.any { it.message.orEmpty().contains("超时") } ->
                        "策略更新检查超时，继续使用当前策略"
                    else -> failures.lastOrNull()?.message?.take(180) ?: "策略更新检查失败，继续使用当前策略"
                }
                persistLocked()
                return statusLocked()
            }
            val currentRevision = active?.policy?.revision ?: 0
            if (!force && candidate.policy.revision <= suppressAutoThroughRevision) {
                lastError = "自动更新暂时忽略已回滚版本；当前策略保持不变"
                persistLocked()
                return statusLocked()
            }
            if (candidate.policy.revision <= currentRevision) {
                lastError = ""
                persistLocked()
                return statusLocked()
            }
            previous = active
            previousSource = activeSource
            active = candidate
            activeSource = "remote"
            suppressAutoThroughRevision = 0
            lastError = ""
            persistLocked()
            return statusLocked()
        }
    }

    fun rollback(): PlaybackStrategyStatus = synchronized(lock) {
        val target = previous ?: run {
            lastError = "没有可回滚的上一版策略"
            return statusLocked()
        }
        val rolledBackFrom = active?.policy?.revision ?: 0
        active = target
        activeSource = previousSource.ifBlank { "saved" }
        previous = null
        previousSource = ""
        suppressAutoThroughRevision = maxOf(suppressAutoThroughRevision, rolledBackFrom)
        lastError = "已回滚上一版；自动更新将跳过被回滚版本"
        persistLocked()
        statusLocked()
    }

    private fun statusLocked(): PlaybackStrategyStatus {
        val policy = active?.policy
        return PlaybackStrategyStatus(
            enabled = enabled,
            revision = policy?.revision ?: 0,
            issuedAt = policy?.issuedAt.orEmpty(),
            lastCheckAt = lastCheckAt,
            canRollback = previous != null,
            source = activeSource,
            error = lastError,
            routeCount = policy?.routes?.size ?: 0,
            keyId = policy?.keyId.orEmpty(),
        )
    }

    private fun persistLocked() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val state = JSONObject()
                .put("enabled", enabled)
                .put("activeSource", activeSource)
                .put("previousSource", previousSource)
                .put("lastCheckAt", lastCheckAt)
                .put("suppressAutoThroughRevision", suppressAutoThroughRevision)
                .put("error", lastError.take(200))
                .put("activeEnvelope", active?.envelope?.toString().orEmpty())
                .put("previousEnvelope", previous?.envelope?.toString().orEmpty())
            val temporary = File(stateFile.parentFile, "pack-state.json.tmp")
            temporary.writeText(state.toString(), Charsets.UTF_8)
            temporary.outputStream().use { it.fd.sync() }
            if (!temporary.renameTo(stateFile)) {
                stateFile.delete()
                if (!temporary.renameTo(stateFile)) throw IOException("无法保存策略状态")
            }
        }.onFailure { lastError = "策略状态保存失败：${it.message.orEmpty().take(100)}" }
    }

    private fun decodeEnvelope(envelope: JSONObject): DecodedPack {
        if (envelope.optInt("format") != PACK_FORMAT) throw IOException("策略包格式不支持")
        val keyId = envelope.optString("keyId")
        val keyBase64 = keyRing[keyId] ?: throw IOException("策略包签名密钥不受信任")
        val payloadBase64 = envelope.optString("payload")
        val signatureBase64 = envelope.optString("signature")
        if (payloadBase64.isBlank() || signatureBase64.isBlank() || payloadBase64.length > MAX_PACK_BYTES * 2)
            throw IOException("策略包签名数据缺失或过大")
        val payloadBytes = Base64.decode(payloadBase64, Base64.DEFAULT)
        val signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
        if (payloadBytes.isEmpty() || payloadBytes.size > MAX_PACK_BYTES || signatureBytes.size != 64)
            throw IOException("策略包签名长度无效")
        val x509 = Base64.decode(keyBase64, Base64.DEFAULT)
        val publicKey = EdDSAPublicKey(X509EncodedKeySpec(x509))
        val verifier = EdDSAEngine()
        verifier.initVerify(publicKey)
        verifier.update(payloadBytes)
        if (!verifier.verify(signatureBytes)) throw IOException("策略包 Ed25519 签名校验失败")
        val payloadText = String(payloadBytes, StandardCharsets.UTF_8)
        val payload = try { JSONObject(payloadText) } catch (problem: Exception) {
            throw IOException("策略包内容不是有效 JSON", problem)
        }
        if (canonicalStrategyJson(payload) != payloadText) throw IOException("策略包 JSON 不是规范格式")
        if (payload.optString("keyId") != keyId || payload.optInt("format") != PACK_FORMAT ||
            payload.optString("id") != "hongguo-playback") throw IOException("策略包标识不一致")
        val revision = payload.optInt("revision", 0)
        if (revision < 1) throw IOException("策略包版本无效")
        val issuedAt = payload.optString("issuedAt")
        val issuedMillis = runCatching { Instant.parse(issuedAt).toEpochMilli() }.getOrElse {
            throw IOException("策略包发布时间无效", it)
        }
        if (issuedMillis > System.currentTimeMillis() + 5 * 60_000L) throw IOException("策略包发布时间在未来")
        val expiresAt = payload.optString("expiresAt").takeIf { it.isNotBlank() }
        if (expiresAt != null) {
            val expires = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrElse {
                throw IOException("策略包有效期格式无效", it)
            }
            if (expires <= System.currentTimeMillis()) throw IOException("策略包已过期")
        }
        val routesObject = payload.optJSONObject("policy") ?: throw IOException("策略包缺少路由策略")
        val routesArray = routesObject.optJSONArray("routes") ?: JSONArray()
        if (routesArray.length() > ROUTE_IDS.size) throw IOException("策略路由数量过多")
        val seen = mutableSetOf<String>()
        val routes = (0 until routesArray.length()).map { index ->
            val route = routesArray.optJSONObject(index) ?: throw IOException("策略路由格式无效")
            val id = route.optString("id")
            if (id !in ROUTE_IDS || !seen.add(id)) throw IOException("策略路由 ID 无效或重复")
            val order = route.optInt("order", 100)
            if (order !in 0..1000) throw IOException("策略路由顺序无效")
            val auth = route.optString("auth", "v5")
            if (auth !in AUTH_MODES) throw IOException("策略签名族不受支持")
            val url = route.optString("url").takeIf { it.isNotBlank() }?.let(::safeRouteUrl)
            val values = safeValues(route.optJSONObject("values"), protectIdentity = true)
            val body = safeValues(route.optJSONObject("body"), protectIdentity = false)
            val headers = safeHeaders(route.optJSONObject("headers"))
            val field21 = if (route.has("field21")) {
                val value = route.optInt("field21", -1)
                if (value !in 0..0xFFFF) throw IOException("策略 field21 超出 V5 签名器范围")
                value
            } else null
            PlaybackStrategyRoute(
                id = id,
                enabled = route.optBoolean("enabled", true),
                order = order,
                auth = auth,
                url = url,
                values = values,
                body = body,
                headers = headers,
                field21 = field21,
            )
        }
        val policy = PlaybackStrategyPolicy(
            revision = revision,
            keyId = keyId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            routes = routes,
        )
        return DecodedPack(envelope, policy)
    }

    private fun safeRouteUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("策略路由 URL 无效")
        val host = url.host.lowercase()
        val allowedHost = host == "fqnovel.com" || host.endsWith(".fqnovel.com") ||
            host == "snssdk.com" || host.endsWith(".snssdk.com")
        if (url.scheme != "https" || !allowedHost || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.port != 443 || url.query != null || url.fragment != null)
            throw IOException("策略路由 URL 不是允许的官方 HTTPS 地址")
        return url.toString()
    }

    private fun safeValues(input: JSONObject?, protectIdentity: Boolean): Map<String, Any> {
        if (input == null) return emptyMap()
        if (input.length() > MAX_ROUTE_FIELDS) throw IOException("策略字段过多")
        val output = linkedMapOf<String, Any>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.matches(Regex("[A-Za-z0-9_.-]{1,80}")) ||
                key.split('.').any { it in setOf("__proto__", "prototype", "constructor") })
                throw IOException("策略字段名无效")
            if (protectIdentity && key in PROTECTED_QUERY_FIELDS) throw IOException("策略不允许覆写设备身份或时间字段")
            val value = input.opt(key)
            if (value !is String && value !is Number && value !is Boolean) throw IOException("策略字段类型无效")
            if (value.toString().length > MAX_VALUE_LENGTH || value.toString().any { it == '\r' || it == '\n' || it == '\u0000' })
                throw IOException("策略字段值无效")
            output[key] = value
        }
        return output
    }

    private fun safeHeaders(input: JSONObject?): Map<String, String> {
        val values = safeValues(input, protectIdentity = false)
        val output = linkedMapOf<String, String>()
        values.forEach { (key, value) ->
            val name = key.lowercase()
            if (name !in SAFE_HEADERS) throw IOException("策略不允许修改请求头 $key")
            output[name] = value.toString()
        }
        return output
    }

    companion object {
        private const val PACK_FORMAT = 2
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_PACK_BYTES = 256 * 1024
        private const val MAX_ROUTE_FIELDS = 40
        private const val MAX_VALUE_LENGTH = 256
        private val ROUTE_IDS = setOf("preload_current", "video_tab", "player_b", "player_a", "model_0", "model_1", "legacy")
        private val AUTH_MODES = setOf("v5", "player", "none")
        private val SAFE_HEADERS = setOf("user-agent", "accept")
        private val PROTECTED_QUERY_FIELDS = setOf("iid", "cdid", "device_id", "ts", "_rticket")
    }
}
