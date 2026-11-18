package com.musicdl.android

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Routes for the per-app tunnel.
 *
 * The Xray core runs inside this very process, and this process is the only one allowed into the
 * TUN. Its own connection to the VPN server would therefore be captured by the tunnel and loop
 * forever. The libv2ray build has no socket protection hook, so the server addresses are carved
 * out of the default routes instead: the result is "everything except these hosts".
 */
internal object VpnRoutes {
    data class Route(val address: InetAddress, val prefix: Int)

    fun routesExcluding(excluded: Collection<InetAddress>): List<Route> {
        val v4 = excluded.filterIsInstance<Inet4Address>().map { it.address }
        val v6 = excluded.filterIsInstance<Inet6Address>().map { it.address }
        val out = ArrayList<Route>()
        split(ByteArray(4), 0, v4, out)
        split(ByteArray(16), 0, v6, out)
        return out
    }

    private fun split(base: ByteArray, length: Int, excluded: List<ByteArray>, out: MutableList<Route>) {
        val inside = excluded.filter { startsWith(it, base, length) }
        if (inside.isEmpty()) {
            out += Route(InetAddress.getByAddress(base.copyOf()), length)
            return
        }
        if (length == base.size * 8) return // this exact host is the excluded one
        val right = base.copyOf().also { it[length / 8] = (it[length / 8].toInt() or (0x80 shr (length % 8))).toByte() }
        split(base.copyOf(), length + 1, inside, out)
        split(right, length + 1, inside, out)
    }

    private fun startsWith(address: ByteArray, prefix: ByteArray, length: Int): Boolean {
        for (bit in 0 until length) {
            val mask = 0x80 shr (bit % 8)
            if ((address[bit / 8].toInt() and mask) != (prefix[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}
