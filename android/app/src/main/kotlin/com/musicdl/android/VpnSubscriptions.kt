package com.musicdl.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URI
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Reads at most [limit] bytes. `readByteArray(n)` throws a message-less EOFException when the body
 * is shorter than n, which made every normal-sized subscription fail, so the source is buffered
 * with `request` instead (it simply stops at the end of the body).
 */
internal fun readBounded(source: okio.BufferedSource, limit: Int): ByteArray {
    source.request(limit.toLong() + 1L)
    require(source.buffer.size <= limit) { "Подписка слишком большая (лимит 2 МБ)" }
    return source.readByteArray()
}

/** Never show an empty error: fall back to a description plus the exception type. */
internal fun Throwable.vpnMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: "$fallback (${javaClass.simpleName})"

/** The device limit of the subscription is used up: remove an old device at the provider. */
class DeviceLimitException : Exception(
    "Достигнут лимит устройств по этой подписке. Удалите старое устройство в личном кабинете или боте провайдера и повторите.",
)

/** Client identity sent to the subscription server. */
enum class SubscriptionClient(val id: String, val label: String, val userAgent: String) {
    AUTO("auto", "Авто", ""),
    HAPP("happ", "Happ", "Happ/2.9.0"),
    V2RAYNG("v2rayng", "v2rayNG", "v2rayNG/1.9.40"),
    INCY("incy", "INCY", "INCY/1.0.0");

    companion object {
        /** Tried in this order in «Авто» until an answer contains servers. */
        val AUTO_ORDER = listOf(HAPP, V2RAYNG, INCY)
        fun byId(id: String?): SubscriptionClient = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** A subscription entry kept locally. The raw share URI is encrypted at rest. */
data class VpnProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val shareUri: String,
    val subscriptionUrl: String? = null,
)

/**
 * Import/storage layer for user-owned VPN share links and subscription URLs.
 * This class deliberately does not pretend to establish a tunnel: the native libbox runtime
 * is a separate integration step and must report its real connection state to the UI.
 */
class VpnSubscriptionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("vpn_subscription_store", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Synchronized
    fun getProfiles(): List<VpnProfile> = decrypt(prefs.getString(KEY_PAYLOAD, null))

    @Synchronized
    fun add(input: String): List<VpnProfile> {
        val value = input.trim()
        require(value.isNotEmpty()) { "Вставьте ссылку на подписку или VPN-конфигурацию" }
        require(!VpnShareLinkParser.isEncryptedProviderLink(value)) { VpnShareLinkParser.ENCRYPTED_LINK_MESSAGE }
        // A pasted https:// subscription, or a "happ://add/https://…"-style import link from a panel.
        val sourceUrl = VpnShareLinkParser.extractSubscriptionUrl(value)
        val content = if (sourceUrl != null) download(sourceUrl) else value
        val parsed = VpnShareLinkParser.parse(content, sourceUrl)
        require(parsed.isNotEmpty()) { VpnShareLinkParser.explainEmpty(content, sourceUrl != null) }
        val current = getProfiles().associateBy { it.id }.toMutableMap()
        parsed.forEach { profile ->
            val duplicate = current.values.firstOrNull { it.shareUri == profile.shareUri }
            if (duplicate == null) current[profile.id] = profile
        }
        save(current.values.toList())
        return getProfiles()
    }

    @Synchronized
    fun refresh(subscriptionUrl: String): List<VpnProfile> {
        val content = download(subscriptionUrl)
        val parsed = VpnShareLinkParser.parse(content, subscriptionUrl)
        require(parsed.isNotEmpty()) { VpnShareLinkParser.explainEmpty(content, true) }
        val existing = getProfiles().filter { it.subscriptionUrl == subscriptionUrl }
            .associateBy { it.shareUri }
        val current = getProfiles().filterNot { it.subscriptionUrl == subscriptionUrl }.toMutableList()
        current += parsed.map { fresh -> existing[fresh.shareUri]?.copy(
            name = fresh.name, protocol = fresh.protocol, server = fresh.server, port = fresh.port,
            subscriptionUrl = subscriptionUrl,
        ) ?: fresh }
        save(current)
        return getProfiles()
    }

    @Synchronized
    fun remove(profileId: String): List<VpnProfile> {
        save(getProfiles().filterNot { it.id == profileId })
        return getProfiles()
    }

    /** Which client the subscription request identifies itself as; stored in the app. */
    var subscriptionClient: SubscriptionClient
        get() = SubscriptionClient.byId(prefs.getString(KEY_CLIENT, null))
        set(value) { prefs.edit().putString(KEY_CLIENT, value.id).apply() }

    /**
     * Stable per-install device id (panels with a device limit require `x-hwid`: 10–64 chars of
     * letters, digits, `=` and `-`). It is derived from the real Android ID, so the same phone always
     * reports the same id and never uses up a second device slot.
     */
    private fun hwid(): String {
        prefs.getString(KEY_HWID, null)?.takeIf { it.length in 10..64 }?.let { return it }
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val seed = androidId.ifBlank { UUID.randomUUID().toString() } + appContext.packageName
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val id = digest.joinToString("") { "%02x".format(it) }.take(32)
        prefs.edit().putString(KEY_HWID, id).apply()
        return id
    }

    private fun ascii(value: String): String = value.filter { it.code in 32..126 }.trim().ifBlank { "unknown" }

    /**
     * Fetches a subscription. Some providers only answer clients they recognise ("use another app"),
     * so in «Авто» mode the request is repeated as Happ, v2rayNG and INCY until one answer contains
     * servers. Every request carries the standard device headers.
     */
    private fun download(url: String): String {
        val candidates = subscriptionClient.let { if (it == SubscriptionClient.AUTO) SubscriptionClient.AUTO_ORDER else listOf(it) }
        var fallbackBody: String? = null
        var failure: Exception? = null
        for (client in candidates) {
            try {
                val body = fetch(url, client)
                if (VpnShareLinkParser.parse(body, url).isNotEmpty()) return body
                if (fallbackBody == null) fallbackBody = body
            } catch (limit: DeviceLimitException) {
                throw limit
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        return fallbackBody ?: throw (failure ?: IllegalStateException("Сервер подписки не ответил"))
    }

    private fun fetch(url: String, client: SubscriptionClient): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", client.userAgent)
            .header("Accept", "text/plain, */*")
            .header("x-hwid", hwid())
            .header("x-device-os", "Android")
            .header("x-ver-os", ascii(android.os.Build.VERSION.RELEASE.orEmpty()))
            .header("x-device-model", ascii(android.os.Build.MODEL.orEmpty()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.header("x-hwid-max-devices-reached").equals("true", true) ||
                response.header("x-hwid-limit").equals("true", true)) {
                throw DeviceLimitException()
            }
            require(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "Сервер подписки отказал в доступе (HTTP ${response.code}). Проверьте, что ссылка не устарела."
                    404 -> "Подписка не найдена (HTTP 404). Проверьте ссылку или не исчерпан ли лимит устройств."
                    else -> "Сервер подписки ответил HTTP ${response.code}"
                }
            }
            val body = response.body ?: error("Пустой ответ подписки")
            require(body.contentLength() <= MAX_SUBSCRIPTION_BYTES || body.contentLength() == -1L) {
                "Подписка слишком большая (лимит 2 МБ)"
            }
            return readBounded(body.source(), MAX_SUBSCRIPTION_BYTES).toString(StandardCharsets.UTF_8)
        }
    }

    private fun save(profiles: List<VpnProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("protocol", p.protocol)
                put("server", p.server); put("port", p.port); put("shareUri", p.shareUri)
                put("subscriptionUrl", p.subscriptionUrl ?: JSONObject.NULL)
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(prefs.edit().putString(KEY_PAYLOAD, Base64.getEncoder().encodeToString(payload)).commit()) {
            "Не удалось сохранить VPN-подписки"
        }
    }

    private fun decrypt(encoded: String?): List<VpnProfile> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            val payload = Base64.getDecoder().decode(encoded)
            require(payload.size > GCM_IV_BYTES)
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val array = JSONArray(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(VpnProfile(
                        id = item.getString("id"), name = item.getString("name"),
                        protocol = item.getString("protocol"), server = item.getString("server"),
                        port = item.getInt("port"), shareUri = item.getString("shareUri"),
                        subscriptionUrl = item.optString("subscriptionUrl").takeIf { it.isNotBlank() && it != "null" },
                    ))
                }
            }
        } catch (error: Exception) {
            // Never silently discard a corrupted encrypted store.
            throw IllegalStateException("Не удалось расшифровать локальные VPN-подписки", error)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "xxw_music_vpn_subscription_key"
        const val KEY_PAYLOAD = "encrypted_profiles_v1"
        const val KEY_CLIENT = "subscription_client"
        const val KEY_HWID = "hwid"
        const val GCM_IV_BYTES = 12
        const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
    }
}

object VpnShareLinkParser {
    private val supported = setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "hysteria", "hysteria2", "hy2", "tuic", "anytls", "wireguard", "wg", "socks", "socks5")

    private val importSchemes = Regex(
        "(?i)^(?:v2raytun|happ|incy|v2box|hiddify|clash|clashmeta|sing-box|v2rayn|v2rayng|streisand|karing|flclash|shadowrocket|nekoray|husi|exclave)://\\S*",
    )
    private val shareSchemes = Regex("(?i)(?:^|\\s)(?:vless|vmess|trojan|ss|shadowsocks|hysteria2?|hy2|tuic|anytls|wireguard|wg|socks5?)://")
    private val httpUrl = Regex("(?i)https?://[^\\s\"'<>]+")

    /**
     * The subscription URL inside what the user pasted: a bare https:// link, a link surrounded by
     * text, or an app import link such as `happ://add/https://…` / `clash://install-config?url=…`.
     * Returns null when the input is one or more share links themselves.
     */
    fun extractSubscriptionUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (importSchemes.containsMatchIn(firstLine)) {
            // clash://install-config?url=<encoded>&name=… carries the subscription in a parameter.
            Regex("(?i)[?&]url=([^&\\s]+)").find(firstLine)?.groupValues?.get(1)?.let { raw ->
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                httpUrl.find(decoded)?.let { return cleanUrl(it.value) }
            }
            val decoded = runCatching { URLDecoder.decode(firstLine, "UTF-8") }.getOrDefault(firstLine)
            httpUrl.find(decoded)?.let { return cleanUrl(it.value) }
        }
        if (shareSchemes.containsMatchIn(text)) return null
        return httpUrl.find(text)?.let { cleanUrl(it.value) }
    }

    private fun cleanUrl(url: String): String = url.trimEnd('.', ',', ';', ')', ']', '}')

    private val encryptedLink = Regex("(?i)\\b(?:happ://crypt\\d*|incy://crypt\\d*|v2raytun://crypt\\d*)/")

    /** Encrypted provider links hide the subscription address on purpose; they open only in their own app. */
    fun isEncryptedProviderLink(input: String): Boolean = encryptedLink.containsMatchIn(input)

    const val ENCRYPTED_LINK_MESSAGE =
        "Это зашифрованная ссылка (happ://crypt… / incy://crypt…): провайдер скрыл в ней адрес подписки, и она " +
            "открывается только в приложении Happ или INCY. Попросите у провайдера обычную ссылку подписки " +
            "https://… и вставьте её сюда."

    /** Why a downloaded/pasted payload produced no servers, in words the user can act on. */
    /** A short, secret-free look at a response: long tokens (keys, UUIDs, base64) are replaced by "…". */
    internal fun preview(content: String): String {
        val text = decodeBase64(content.trim())?.takeIf { it.isNotBlank() } ?: content
        return text.trim().take(160).replace(Regex("[A-Za-z0-9+/=_.-]{16,}"), "…").replace(Regex("\\s+"), " ").take(90)
    }

    fun explainEmpty(content: String, downloaded: Boolean): String {
        val head = content.trimStart().take(300).lowercase()
        return when {
            head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<head") ->
                "По ссылке открылась веб-страница, а не подписка. Скопируйте именно ссылку подписки из панели."
            head.startsWith("proxies:") || head.contains("\nproxies:") || head.startsWith("port:") || head.startsWith("mixed-port:") ->
                "Подписка в формате Clash (YAML) не поддерживается. Выберите в панели формат «V2Ray / base64» или список ссылок."
            head.startsWith("{") || head.startsWith("[") ->
                "Подписка в формате JSON (sing-box/Xray) не поддерживается. Выберите в панели формат «V2Ray / base64» или список ссылок."
            downloaded -> "Подписка загружена, но серверов в ней не нашлось (VLESS, VMess, Trojan, Shadowsocks). " +
                "Начало ответа: «${preview(content)}»"
            else -> "Не найдены поддерживаемые VPN-ссылки (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC)"
        }
    }

    fun parse(input: String, subscriptionUrl: String? = null): List<VpnProfile> {
        val candidates = mutableListOf<String>()
        val trimmed = input.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("vmess://", true) || trimmed.startsWith("vless://", true) || trimmed.startsWith("trojan://", true) || trimmed.startsWith("ss://", true)) {
            candidates += trimmed.lines()
        } else {
            candidates += trimmed.lines()
            decodeBase64(trimmed)?.let { decoded -> if (decoded != trimmed) candidates += decoded.lines() }
        }
        val output = LinkedHashMap<String, VpnProfile>()
        candidates.flatMap { line ->
            val text = line.trim()
            // One link per line (its name may contain spaces); split only if several are glued together.
            if (Regex("://").findAll(text).count() > 1) text.split(Regex("\\s+")) else listOf(text)
        }.forEach { raw ->
            val link = raw.trim().trim('"', '\'', ',', ';')
            if (link.startsWith("vmess://", true)) {
                parseVmess(link, subscriptionUrl)?.let { output.putIfAbsent(it.shareUri, it) }
            } else {
                parseUri(link, subscriptionUrl)?.let { output.putIfAbsent(it.shareUri, it) }
            }
        }
        return output.values.toList()
    }

    private fun parseVmess(link: String, source: String?): VpnProfile? = runCatching {
        val jsonText = decodeBase64(link.substringAfter("vmess://")) ?: return null
        val json = Json.parseToJsonElement(jsonText).jsonObject
        val host = json["add"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val port = json["port"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = json["ps"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: host
        VpnProfile(UUID.randomUUID().toString(), name, "VMess", host, port, link, source)
    }.getOrNull()

    private fun parseUri(link: String, source: String?): VpnProfile? = runCatching {
        val scheme = link.substringBefore("://", "").lowercase()
        if (scheme !in supported) return null
        // Panels often leave spaces, quotes or "|" unescaped in the name/query; java.net.URI rejects those.
        val clean = escapeIllegal(link)
        val uri = URI(clean)
        val authority = uri.rawAuthority.orEmpty().substringAfterLast('@')
        // uri.host is null for names java.net.URI dislikes (e.g. underscores), so fall back to the raw authority.
        val host = (uri.host ?: authority.substringBeforeLast(':', authority).trim('[', ']')).takeIf { it.isNotBlank() } ?: return null
        val port = (uri.port.takeIf { it > 0 } ?: authority.substringAfterLast(':', "").toIntOrNull())
            ?.takeIf { it in 1..65535 } ?: defaultPort(scheme)
        if (port !in 1..65535) return null
        val fragment = uri.rawFragment?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val display = fragment?.takeIf { it.isNotBlank() } ?: host
        val protocol = when (scheme) {
            "ss", "shadowsocks" -> "Shadowsocks"
            "hy2", "hysteria2" -> "Hysteria2"
            "hysteria" -> "Hysteria"
            "wg", "wireguard" -> "WireGuard"
            "socks", "socks5" -> "SOCKS"
            else -> scheme.replaceFirstChar { it.uppercase() }
        }
        VpnProfile(UUID.randomUUID().toString(), display, protocol, host, port, clean, source)
    }.getOrNull()

    /** Percent-encodes characters that are illegal in a URI, keeping existing %XX escapes. */
    internal fun escapeIllegal(link: String): String = buildString {
        for (ch in link) {
            if (ch.code > 127 || ch in " \"<>\\^`{|}") {
                ch.toString().toByteArray(StandardCharsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
            } else append(ch)
        }
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        else -> 0
    }

    private fun decodeBase64(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.isBlank()) return null
        return listOf(Base64.getDecoder(), Base64.getUrlDecoder()).firstNotNullOfOrNull { decoder ->
            runCatching { String(decoder.decode(compact), StandardCharsets.UTF_8) }
                .getOrNull()?.takeIf { decoded -> decoded.contains("://") || decoded.trimStart().startsWith("{") }
        }
    }
}
