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

    private fun download(url: String): String {
        val request = Request.Builder().url(url)// Panels (Marzban, Remnawave, 3x-ui…) pick the response format from the User-Agent; a
            // v2ray client name gets the plain/base64 list of share links this importer reads.
            .header("User-Agent", "v2rayNG/1.9.0 XXW-Music/1.0")
            .header("Accept", "text/plain, */*").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "Сервер подписки отказал в доступе (HTTP ${response.code}). Проверьте, что ссылка не устарела."
                    404 -> "Подписка не найдена (HTTP 404). Проверьте ссылку."
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
        const val GCM_IV_BYTES = 12
        const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
    }
}

object VpnShareLinkParser {
    private val supported = setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "hysteria", "hysteria2", "hy2", "tuic", "anytls", "wireguard", "wg", "socks", "socks5")

    private val importSchemes = Regex(
        "(?i)^(?:v2raytun|happ|hiddify|clash|clashmeta|sing-box|v2rayn|v2rayng|streisand|karing|flclash|shadowrocket|nekoray|husi|exclave)://\\S*",
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

    /** Why a downloaded/pasted payload produced no servers, in words the user can act on. */
    fun explainEmpty(content: String, downloaded: Boolean): String {
        val head = content.trimStart().take(300).lowercase()
        return when {
            head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<head") ->
                "По ссылке открылась веб-страница, а не подписка. Скопируйте именно ссылку подписки из панели."
            head.startsWith("proxies:") || head.contains("\nproxies:") || head.startsWith("port:") || head.startsWith("mixed-port:") ->
                "Подписка в формате Clash (YAML) не поддерживается. Выберите в панели формат «V2Ray / base64» или список ссылок."
            head.startsWith("{") ->
                "Подписка в формате JSON (sing-box/Xray) не поддерживается. Выберите в панели формат «V2Ray / base64» или список ссылок."
            downloaded -> "Подписка загружена, но в ней нет поддерживаемых серверов (VLESS, VMess, Trojan, Shadowsocks)."
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
        candidates.flatMap { line -> line.split(Regex("\\s+")) }.forEach { raw ->
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
        val uri = URI(link)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: defaultPort(scheme)
        if (port !in 1..65535) return null
        val fragment = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }
        val display = fragment?.takeIf { it.isNotBlank() } ?: host
        val protocol = when (scheme) {
            "ss", "shadowsocks" -> "Shadowsocks"
            "hy2", "hysteria2" -> "Hysteria2"
            "hysteria" -> "Hysteria"
            "wg", "wireguard" -> "WireGuard"
            "socks", "socks5" -> "SOCKS"
            else -> scheme.replaceFirstChar { it.uppercase() }
        }
        VpnProfile(UUID.randomUUID().toString(), display, protocol, host, port, link, source)
    }.getOrNull()

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
