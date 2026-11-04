package com.forgebyte.nearby

/** JmDNS on the JVM needs no setup. */
internal actual fun ensureDiscoveryReady() {}

/** The Mac tools have no Wi-Fi power saving to turn off. */
internal actual fun newWifiLatencyLock(): WifiLatencyLock = object : WifiLatencyLock {
    override fun acquire() {}
    override fun release() {}
}
