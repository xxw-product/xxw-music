package com.musicdl.android

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Server latency as the time of a TCP handshake to the server port. DNS is resolved first and is not
 * counted. It works without the VPN core, so it can be used before connecting. UDP-based protocols
 * (Hysteria, TUIC, WireGuard) have no TCP port to probe and are reported as unsupported.
 */
object ServerPing {
    const val UNREACHABLE = -1L
    const val UNSUPPORTED = -3L

    private val udpProtocols = setOf("hysteria", "hysteria2", "tuic", "wireguard")

    fun isMeasurable(protocol: String): Boolean = protocol.lowercase() !in udpProtocols

    /** Best of [attempts] handshakes in ms, or [UNREACHABLE]. */
    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = 3_000, attempts: Int = 2): Long =
        withContext(Dispatchers.IO) {
            val address = runCatching {
                InetAddress.getAllByName(host).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() }
            }.getOrNull() ?: return@withContext UNREACHABLE
            var best = Long.MAX_VALUE
            repeat(attempts) {
                val started = System.nanoTime()
                val ok = runCatching {
                    Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
                }.isSuccess
                if (ok) best = minOf(best, ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L))
            }
            if (best == Long.MAX_VALUE) UNREACHABLE else best
        }

    suspend fun ping(profile: VpnProfile): Long =
        if (!isMeasurable(profile.protocol)) UNSUPPORTED else tcpPing(profile.server, profile.port)

    /** Pings all [profiles] with limited parallelism and reports each result as soon as it is known. */
    suspend fun pingAll(profiles: List<VpnProfile>, parallelism: Int = 6, onResult: (String, Long) -> Unit) {
        val gate = Semaphore(parallelism)
        coroutineScope {
            profiles.map { profile -> async { gate.withPermit { onResult(profile.id, ping(profile)) } } }.awaitAll()
        }
    }
}
