package com.musicdl.android

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPingTest {
    @Test fun openPortReportsLatency() = runBlocking {
        ServerSocket(0).use { server ->
            val ms = ServerPing.tcpPing("127.0.0.1", server.localPort)
            assertTrue("latency $ms", ms in 1..2_000)
        }
    }

    @Test fun closedPortIsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // closed right away
        assertEquals(ServerPing.UNREACHABLE, ServerPing.tcpPing("127.0.0.1", port, timeoutMs = 500, attempts = 1))
    }

    @Test fun udpProtocolsAreNotMeasured() = runBlocking {
        assertFalse(ServerPing.isMeasurable("Hysteria2"))
        assertTrue(ServerPing.isMeasurable("Vless"))
        val profile = VpnProfile("1", "n", "TUIC", "127.0.0.1", 1, "tuic://x")
        assertEquals(ServerPing.UNSUPPORTED, ServerPing.ping(profile))
    }

    @Test fun pingAllReportsEveryProfile() = runBlocking {
        ServerSocket(0).use { server ->
            val profiles = (1..8).map { VpnProfile("$it", "n$it", "Vless", "127.0.0.1", server.localPort, "vless://x") }
            val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
            ServerPing.pingAll(profiles) { id, ms -> seen[id] = ms }
            assertEquals(8, seen.size)
            assertTrue(seen.values.all { it > 0 })
        }
    }

    private fun profile(id: String, protocol: String = "Vless") = VpnProfile(id, id, protocol, "h$id", 443, "x")

    @Test fun autoSwitchPicksFastestReachableConnectableServer() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"), profile("d", "Hysteria2"), profile("e"), profile("f"))
        val pings = mapOf("a" to 300L, "b" to 40L, "c" to ServerPing.UNREACHABLE, "d" to 10L, "e" to 120L)
        // f has no result, c is down, d is UDP-only, a is the server that just failed
        val order = AutoSwitch.order(profiles, failed = setOf("a"), pings = pings).map { it.id }
        assertEquals(listOf("b", "e"), order)
    }

    @Test fun autoSwitchReturnsNothingWhenEverythingIsDown() {
        val profiles = listOf(profile("a"), profile("b"))
        assertTrue(AutoSwitch.order(profiles, emptySet(), mapOf("a" to -1L, "b" to -1L)).isEmpty())
    }
}
