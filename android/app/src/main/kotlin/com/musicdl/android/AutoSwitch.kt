package com.musicdl.android

/** Choosing the next server when the current one stops answering. */
internal object AutoSwitch {
    private val connectable = setOf("vless", "vmess", "trojan", "shadowsocks", "ss")

    fun isConnectable(profile: VpnProfile): Boolean = profile.protocol.lowercase() in connectable

    /**
     * Servers worth trying, fastest first: only protocols the built-in Xray can connect to, never one
     * in [failed], and only if its port answered a TCP probe ([pings] has a positive latency).
     */
    fun order(profiles: List<VpnProfile>, failed: Set<String>, pings: Map<String, Long>): List<VpnProfile> =
        profiles.filter { isConnectable(it) && it.id !in failed && (pings[it.id] ?: -1L) > 0L }
            .sortedBy { pings.getValue(it.id) }
}
