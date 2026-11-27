package com.forgebyte.nearby.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HotspotAddressTest {
    private val home = "wlan0" to "192.168.29.137"

    @Test
    fun `the new address on the hotspot interface is picked, not the home Wi-Fi`() {
        assertEquals("192.168.49.1", pickHotspotAddress(listOf(home), listOf(home, "ap0" to "192.168.49.1")))
    }

    @Test
    fun `a hotspot on wlan1 or swlan0 is picked`() {
        assertEquals("10.203.7.88", pickHotspotAddress(listOf(home), listOf(home, "wlan1" to "10.203.7.88")))
        assertEquals("192.168.43.1", pickHotspotAddress(listOf(home), listOf(home, "swlan0" to "192.168.43.1")))
    }

    @Test
    fun `a new hotspot-named address wins over another new address`() {
        val now = listOf(home, "tun0" to "10.8.0.2", "ap0" to "192.168.49.1")
        assertEquals("192.168.49.1", pickHotspotAddress(listOf(home), now))
    }

    @Test
    fun `any new address is used when no interface has a hotspot name`() {
        assertEquals("192.168.12.1", pickHotspotAddress(listOf(home), listOf(home, "eth9" to "192.168.12.1")))
    }

    @Test
    fun `a hotspot interface already up before the start is used when nothing is new`() {
        val ap = "ap0" to "192.168.49.1"
        assertEquals("192.168.49.1", pickHotspotAddress(listOf(home, ap), listOf(home, ap)))
    }

    @Test
    fun `no hotspot address yet gives null`() {
        assertNull(pickHotspotAddress(listOf(home), listOf(home)))
    }
}
