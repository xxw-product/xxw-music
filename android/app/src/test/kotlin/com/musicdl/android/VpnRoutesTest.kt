package com.musicdl.android

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutesTest {
    private fun covers(routes: List<VpnRoutes.Route>, ip: String): Boolean {
        val target = InetAddress.getByName(ip).address
        return routes.any { route ->
            val base = route.address.address
            base.size == target.size && (0 until route.prefix).all { bit ->
                val mask = 0x80 shr (bit % 8)
                (base[bit / 8].toInt() and mask) == (target[bit / 8].toInt() and mask)
            }
        }
    }

    @Test fun noExclusionKeepsDefaultRoutes() {
        val routes = VpnRoutes.routesExcluding(emptyList())
        assertEquals(listOf(0, 0), routes.map { it.prefix })
        assertEquals(setOf("0.0.0.0", "0:0:0:0:0:0:0:0"), routes.map { it.address.hostAddress }.toSet())
    }

    @Test fun serverAddressIsNotRoutedIntoTunnel() {
        val server = InetAddress.getByName("203.0.113.7")
        val routes = VpnRoutes.routesExcluding(listOf(server))
        assertFalse(covers(routes, "203.0.113.7"))
        listOf("203.0.113.6", "203.0.113.8", "8.8.8.8", "0.0.0.1", "255.255.255.255", "128.0.0.0")
            .forEach { assertTrue("$it must stay in tunnel", covers(routes, it)) }
        assertTrue(covers(routes, "2606:4700:4700::1111")) // IPv6 default route untouched
    }

    @Test fun ipv6ServerAndSeveralHosts() {
        val routes = VpnRoutes.routesExcluding(listOf(
            InetAddress.getByName("2001:db8::1"), InetAddress.getByName("10.0.0.1"), InetAddress.getByName("10.0.0.2"),
        ))
        assertFalse(covers(routes, "2001:db8::1"))
        assertFalse(covers(routes, "10.0.0.1"))
        assertFalse(covers(routes, "10.0.0.2"))
        assertTrue(covers(routes, "2001:db8::2"))
        assertTrue(covers(routes, "10.0.0.3"))
        assertTrue(covers(routes, "1.1.1.1"))
    }

    @Test fun xudpBaseKeyIsThirtyTwoBytes() {
        val key = XudpKey.derive("device-id" + "com.musicdl.android")
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(key).size)
        assertEquals(key, XudpKey.derive("device-id" + "com.musicdl.android"))
    }
}
