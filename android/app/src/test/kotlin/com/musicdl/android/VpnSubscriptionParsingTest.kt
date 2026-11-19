package com.musicdl.android

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSubscriptionParsingTest {
    private val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&sni=example.com#Server%20A"
    private val trojan = "trojan://pass@example.org:443#Server%20B"

    @Test fun plainHttpsUrlIsASubscription() {
        assertEquals("https://panel.example/sub/abc123",
            VpnShareLinkParser.extractSubscriptionUrl("https://panel.example/sub/abc123"))
    }

    @Test fun urlInsideTextIsExtracted() {
        assertEquals("https://panel.example/sub/abc?x=1",
            VpnShareLinkParser.extractSubscriptionUrl("Ваша подписка: https://panel.example/sub/abc?x=1)."))
    }

    @Test fun appImportLinksAreUnwrapped() {
        assertEquals("https://panel.example/sub/abc",
            VpnShareLinkParser.extractSubscriptionUrl("happ://add/https://panel.example/sub/abc"))
        assertEquals("https://panel.example/sub/abc",
            VpnShareLinkParser.extractSubscriptionUrl("clash://install-config?url=https%3A%2F%2Fpanel.example%2Fsub%2Fabc&name=x"))
    }

    @Test fun shareLinksAreNotSubscriptions() {
        assertNull(VpnShareLinkParser.extractSubscriptionUrl(vless))
        assertNull(VpnShareLinkParser.extractSubscriptionUrl("$vless\n$trojan"))
        assertNull(VpnShareLinkParser.extractSubscriptionUrl(Base64.getEncoder().encodeToString("$vless\n$trojan".toByteArray())))
    }

    @Test fun base64SubscriptionBodyIsParsed() {
        val body = Base64.getEncoder().encodeToString("$vless\n$trojan\nhttps://support.example/help".toByteArray())
        val profiles = VpnShareLinkParser.parse(body, "https://panel.example/sub/abc")
        assertEquals(listOf("Vless", "Trojan"), profiles.map { it.protocol })
        assertEquals(listOf("Server A", "Server B"), profiles.map { it.name })
        assertTrue(profiles.all { it.subscriptionUrl == "https://panel.example/sub/abc" })
    }

    @Test fun httpLinksInsideSubscriptionAreNotServers() {
        assertTrue(VpnShareLinkParser.parse("https://support.example/help\nhttp://x.example:8080", null).isEmpty())
    }

    @Test fun emptyResultsAreExplained() {
        assertTrue(VpnShareLinkParser.explainEmpty("<!DOCTYPE html><html>", true).contains("веб-страница"))
        assertTrue(VpnShareLinkParser.explainEmpty("proxies:\n  - name: a", true).contains("Clash"))
        assertTrue(VpnShareLinkParser.explainEmpty("{\"outbounds\":[]}", true).contains("JSON"))
    }

    @Test fun shortBodyIsReadWithoutEof() {
        val data = "vless://abc@example.com:443#x".toByteArray()
        val source = okio.Buffer().write(data)
        assertEquals(data.toList(), readBounded(source, 2 * 1024 * 1024).toList())
    }

    @Test fun oversizedBodyIsRejected() {
        val source = okio.Buffer().write(ByteArray(2_000))
        val error = runCatching { readBounded(source, 1_000) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun errorsAlwaysHaveText() {
        assertEquals("Ошибка (EOFException)", java.io.EOFException().vpnMessage("Ошибка"))
        assertEquals("boom", IllegalStateException("boom").vpnMessage("Ошибка"))
    }

    @Test fun encryptedProviderLinksAreRecognised() {
        assertTrue(VpnShareLinkParser.isEncryptedProviderLink("happ://crypt4/AbCdEf=="))
        assertTrue(VpnShareLinkParser.isEncryptedProviderLink("incy://crypt1/xyz"))
        assertTrue(!VpnShareLinkParser.isEncryptedProviderLink("happ://add/https://panel.example/sub/abc"))
    }

    @Test fun incyImportLinkIsUnwrapped() {
        assertEquals("https://panel.example/sub/abc",
            VpnShareLinkParser.extractSubscriptionUrl("incy://import/https://panel.example/sub/abc"))
    }

    @Test fun autoClientTriesHappFirst() {
        assertEquals(SubscriptionClient.HAPP, SubscriptionClient.AUTO_ORDER.first())
        assertEquals(SubscriptionClient.AUTO, SubscriptionClient.byId(null))
        assertEquals(SubscriptionClient.INCY, SubscriptionClient.byId("incy"))
    }

    @Test fun namesWithSpacesAndRawSymbolsStillParse() {
        val body = "vless://11111111-2222-3333-4444-555555555555@my_host.example:443?security=tls&path=/a|b#Германия 🇩🇪 Быстрый\n" +
            "trojan://pass@example.org:8443#Server Two"
        val profiles = VpnShareLinkParser.parse(body, null)
        assertEquals(listOf("Германия 🇩🇪 Быстрый", "Server Two"), profiles.map { it.name })
        assertEquals("my_host.example", profiles[0].server)
        assertEquals(443, profiles[0].port)
        assertEquals(8443, profiles[1].port)
    }

    @Test fun previewHidesSecrets() {
        val p = VpnShareLinkParser.preview("Use Happ app token=AbCdEf0123456789XyZ0123456789")
        assertTrue(p.contains("Use Happ app"))
        assertTrue(!p.contains("AbCdEf0123456789"))
    }
}
