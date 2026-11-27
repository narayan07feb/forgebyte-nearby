package com.forgebyte.nearby.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HotspotNetworkTest {
    @Test
    fun `wifiQr is the standard Wi-Fi QR text`() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:k3x9q2pw;;", HotspotNetwork("AndroidShare_1234", "k3x9q2pw").wifiQr())
    }

    @Test
    fun `special characters are escaped and read back`() {
        val network = HotspotNetwork("""a;b,c:d\e"f""", """p;w,x:y\z"""")
        assertEquals("""WIFI:T:WPA;S:a\;b\,c\:d\\e\"f;P:p\;w\,x\:y\\z\";;""", network.wifiQr())
        assertEquals(network, HotspotNetwork.parseWifiQr(network.wifiQr()))
    }

    @Test
    fun `emoji and Hindi network names read back`() {
        val network = HotspotNetwork("खेल 🎴", "पासवर्ड123")
        assertEquals(network, HotspotNetwork.parseWifiQr(network.wifiQr()))
    }

    @Test
    fun `fields in any order and any case of WIFI are read`() {
        assertEquals(HotspotNetwork("Net", "secret12"), HotspotNetwork.parseWifiQr("wifi:S:Net;H:false;P:secret12;T:WPA2;;"))
    }

    @Test
    fun `not a Wi-Fi QR, an open network or a missing field gives null`() {
        assertNull(HotspotNetwork.parseWifiQr("FBN1:abc"))
        assertNull(HotspotNetwork.parseWifiQr("WIFI:T:nopass;S:Open;P:x;;"))
        assertNull(HotspotNetwork.parseWifiQr("WIFI:T:WPA;P:secret12;;"))
        assertNull(HotspotNetwork.parseWifiQr("WIFI:T:WPA;S:Net;;"))
        assertNull(HotspotNetwork.parseWifiQr("WIFI:T:WPA;S:;P:secret12;;"))
    }

    @Test
    fun `toString never holds the password`() {
        val text = HotspotNetwork("Net", "secret12").toString()
        assertFalse("secret12" in text)
        assertEquals("HotspotNetwork(ssid=Net)", text)
    }
}
