package com.musicdl.android

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnShareLinkParserTest {
    @Test
    fun parsesVlessAndKeepsDisplayName() {
        val profiles = VpnShareLinkParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@example.net:443?security=tls#Fast%20Node",
        )
        assertEquals(1, profiles.size)
        assertEquals("Fast Node", profiles.single().name)
        assertEquals("example.net", profiles.single().server)
        assertEquals(443, profiles.single().port)
        assertEquals("Vless", profiles.single().protocol)
    }

    @Test
    fun decodesBase64SubscriptionWithSeveralLinks() {
        val text = "vless://00000000-0000-0000-0000-000000000000@one.example:443#One\ntrojan://secret@two.example:8443#Two"
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray())
        val profiles = VpnShareLinkParser.parse(encoded)
        assertEquals(2, profiles.size)
        assertEquals(setOf("one.example", "two.example"), profiles.map { it.server }.toSet())
    }

    @Test
    fun parsesVmessJsonShareLink() {
        val json = """{"add":"vmess.example","port":"8443","ps":"VMess test"}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val profiles = VpnShareLinkParser.parse("vmess://$encoded")
        assertEquals(1, profiles.size)
        assertEquals("VMess", profiles.single().protocol)
        assertEquals("vmess.example", profiles.single().server)
        assertEquals(8443, profiles.single().port)
    }

    @Test
    fun ignoresUnsupportedOrMalformedEntries() {
        val profiles = VpnShareLinkParser.parse("ssr://not-supported\nvless://broken")
        assertTrue(profiles.isEmpty())
    }
}
