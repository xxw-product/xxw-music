package com.musicdl.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Builds the Xray JSON consumed by AndroidLibXrayLite. Only protocols implemented here are
 * advertised as connectable; import/storage may retain additional share-link formats. */
internal object XrayConfigFactory {
    /** [serverIp] pins the outbound to an already resolved address (see [MusicVpnService]). */
    fun build(profile: VpnProfile, serverIp: String? = null): String {
        var extraOutbounds = emptyList<JSONObject>()
        val outbound = if (profile.shareUri.startsWith(VpnShareLinkParser.XRAY_JSON_PREFIX)) {
            val all = JSONArray(VpnShareLinkParser.xrayJsonOutbounds(profile.shareUri))
            val items = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            val main = items.first { it.optString("protocol").lowercase() in setOf("vless", "vmess", "trojan", "shadowsocks") }
            // Keep helper outbounds the main one may reference (e.g. a fragment dialer); direct/block are ours.
            extraOutbounds = items.filter { it !== main && it.optString("tag") !in setOf("proxy", "direct", "block") }
            main
        } else when (profile.protocol.lowercase()) {
            "vless" -> vless(profile.shareUri)
            "vmess" -> vmess(profile.shareUri)
            "trojan" -> trojan(profile.shareUri)
            "shadowsocks", "ss" -> shadowsocks(profile.shareUri)
            else -> error("${profile.protocol} импортирован, но пока не поддерживается подключением через встроенный Xray")
        }
        outbound.put("tag", "proxy")
        if (!serverIp.isNullOrBlank()) pinServerAddress(outbound, profile.server, serverIp)
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("stats", JSONObject())
            put("policy", JSONObject().put("levels", JSONObject().put("8", JSONObject()
                .put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1)))
                .put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "tun").put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xxwmusic0").put("mtu", 1500).put("userLevel", 8))
                .put("sniffing", JSONObject().put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic")))))
            put("outbounds", JSONArray().put(outbound).apply { extraOutbounds.forEach { put(it) } }
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        }.toString()
    }

    /** Connects by IP but keeps every name-based field (TLS SNI, WebSocket Host) on the original host. */
    private fun pinServerAddress(outbound: JSONObject, host: String, ip: String) {
        val settings = outbound.optJSONObject("settings") ?: return
        val entry = (settings.optJSONArray("vnext") ?: settings.optJSONArray("servers"))?.optJSONObject(0) ?: return
        entry.put("address", ip)
        val stream = outbound.optJSONObject("streamSettings") ?: return
        stream.optJSONObject("wsSettings")?.optJSONObject("headers")
            ?.takeIf { it.optString("Host").isBlank() }?.put("Host", host)
        stream.optJSONObject("httpupgradeSettings")?.takeIf { it.optString("host").isBlank() }?.put("host", host)
        stream.optJSONObject("tlsSettings")?.takeIf { it.optString("serverName").isBlank() }?.put("serverName", host)
        stream.optJSONObject("realitySettings")?.takeIf { it.optString("serverName").isBlank() }?.put("serverName", host)
    }

    private fun vless(link: String): JSONObject {
        val uri = Uri.parse(link)
        val uuid = uri.userInfoOrNull().orEmpty()
        require(uuid.isNotBlank()) { "В ссылке VLESS отсутствует UUID" }
        val query = query(uri).toMutableMap()
        if (query["sni"].isNullOrBlank()) query["sni"] = uri.host.orEmpty()
        val user = JSONObject().put("id", decode(uuid)).put("encryption", "none")
        query["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val stream = streamSettings(query)
        return JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", uri.host ?: error("В VLESS отсутствует адрес сервера"))
                .put("port", uri.port.takeIf { it > 0 } ?: error("В VLESS отсутствует порт"))
                .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
    }

    private fun vmess(link: String): JSONObject {
        val encoded = link.substringAfter("vmess://", "")
        val jsonText = decodeBase64(encoded) ?: error("Некорректная VMess-ссылка")
        val data = JSONObject(jsonText)
        val host = data.optString("add").takeIf { it.isNotBlank() } ?: error("В VMess отсутствует адрес сервера")
        val port = data.optInt("port", 0).takeIf { it in 1..65535 } ?: error("В VMess отсутствует порт")
        val user = JSONObject().put("id", data.optString("id")).put("alterId", data.optInt("aid", 0))
            .put("security", data.optString("scy").ifBlank { "auto" })
        val query = linkedMapOf(
            "type" to data.optString("net").ifBlank { "tcp" },
            "security" to data.optString("tls"),
            "sni" to data.optString("sni").ifBlank { data.optString("host").ifBlank { host } },
            "host" to data.optString("host"),
            "path" to data.optString("path"),
        )
        val stream = streamSettings(query)
        return JSONObject().put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", host).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
    }

    private fun trojan(link: String): JSONObject {
        val uri = Uri.parse(link)
        val password = uri.userInfoOrNull().orEmpty()
        require(password.isNotBlank()) { "В ссылке Trojan отсутствует пароль" }
        val query = query(uri).toMutableMap()
        if (query["security"].isNullOrBlank()) query["security"] = "tls"
        if (query["sni"].isNullOrBlank()) query["sni"] = uri.host.orEmpty()
        return JSONObject().put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", uri.host ?: error("В Trojan отсутствует адрес сервера"))
                .put("port", uri.port.takeIf { it > 0 } ?: error("В Trojan отсутствует порт"))
                .put("password", decode(password)))))
            .put("streamSettings", streamSettings(query))
    }

    private fun shadowsocks(link: String): JSONObject {
        val body = link.substringAfter("ss://", "").substringBefore('#')
        val split = body.lastIndexOf('@')
        require(split > 0) { "Неподдерживаемый формат Shadowsocks URI" }
        var credentials = body.substring(0, split)
        val endpoint = body.substring(split + 1)
        if (!credentials.contains(':')) credentials = decodeBase64(credentials) ?: credentials
        val method = credentials.substringBefore(':', "")
        val password = credentials.substringAfter(':', "")
        require(method.isNotBlank() && password.isNotBlank()) { "В Shadowsocks-ссылке отсутствуют метод или пароль" }
        val parsedEndpoint = Uri.parse("ss://placeholder@$endpoint")
        return JSONObject().put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", parsedEndpoint.host ?: error("В Shadowsocks отсутствует адрес сервера"))
                .put("port", parsedEndpoint.port.takeIf { it > 0 } ?: error("В Shadowsocks отсутствует порт"))
                .put("method", method).put("password", password))))
    }

    private fun streamSettings(q: Map<String, String>): JSONObject {
        val network = q["type"]?.ifBlank { null } ?: q["network"]?.ifBlank { null } ?: "tcp"
        val security = q["security"]?.ifBlank { null } ?: "none"
        val stream = JSONObject().put("network", network).put("security", security)
        when (network.lowercase()) {
            "ws", "websocket" -> stream.put("wsSettings", JSONObject()
                .put("path", q["path"] ?: "/")
                .put("headers", JSONObject().put("Host", q["host"] ?: q["hostHeader"] ?: "")))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", q["serviceName"] ?: q["service"] ?: "")
                .put("multiMode", q["mode"] == "multi"))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject()
                .put("path", q["path"] ?: "/").put("host", q["host"] ?: ""))
            "tcp" -> if (q["headerType"] == "http") stream.put("tcpSettings", JSONObject()
                .put("header", JSONObject().put("type", "http")))
        }
        when (security.lowercase()) {
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", q["sni"] ?: q["host"] ?: "")
                .put("fingerprint", q["fp"] ?: "chrome")
                .put("alpn", JSONArray().apply { q["alpn"]?.split(',')?.filter { it.isNotBlank() }?.forEach { put(it) } }))
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", q["sni"] ?: "")
                .put("fingerprint", q["fp"] ?: "chrome")
                .put("publicKey", q["pbk"] ?: q["publicKey"] ?: "")
                .put("shortId", q["sid"] ?: q["shortId"] ?: "")
                .put("spiderX", q["spx"] ?: q["spiderX"] ?: "/"))
        }
        return stream
    }

    private fun query(uri: Uri): Map<String, String> = buildMap {
        uri.encodedQuery?.split('&')?.forEach { item ->
            val key = decode(item.substringBefore('='))
            val value = decode(item.substringAfter('=', ""))
            put(key, value)
        }
    }

    private fun Uri.userInfoOrNull(): String? = encodedAuthority?.substringBefore('@', "")
        ?.takeIf { it.isNotEmpty() }?.let { Uri.decode(it) }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val compact = value.replace('-', '+').replace('_', '/').filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8) }.getOrNull()
    }
}
