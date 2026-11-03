package com.forgebyte.nearby

import com.appstractive.dnssd.DiscoveredService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DiscoveryTest {
    private val config = Harness.config()

    @Test
    fun givenRoom_whenTxtBuilt_thenOnlyPublicFactsAndNeverTheRoomCode() {
        val txt = roomTxt("fake", config, freeSeats = 3)
        assertEquals(setOf("g", "pv", "gv", "n", "f"), txt.keys)
        assertFalse(txt.keys.any { Harness.SECRET in it } || txt.values.any { Harness.SECRET in it })
        assertEquals(mapOf("g" to "fake", "pv" to "$PROTOCOL_VERSION", "gv" to "7", "n" to "Den", "f" to "3"), txt)
    }

    private fun service(txt: Map<String, String>, addresses: List<String> = listOf("192.168.1.7"), host: String = "") =
        DiscoveredService("Den", addresses, host, SERVICE_TYPE, 40123, txt.mapValues { it.value.encodeToByteArray() })

    @Test
    fun givenAdvertisedTxt_whenRead_thenRoomAdMatches() {
        val ad = service(roomTxt("fake", config, 2)).toRoomAd("fake")!!
        assertEquals(RoomAd(ad.key, "Den", "fake", PROTOCOL_VERSION, 7, 2, "192.168.1.7", 40123), ad)
        assertEquals(true, ad.canJoin(myGameVersion = 7))
        assertEquals(false, ad.canJoin(myGameVersion = 6))
        assertEquals(false, ad.copy(seatsFree = 0).canJoin(7))
    }

    @Test
    fun givenOtherGameOrBrokenTxtOrNoAddress_whenRead_thenSkipped() {
        val txt = roomTxt("fake", config, 2)
        assertNull(service(txt).toRoomAd("uno"))
        assertNull(service(txt - "pv").toRoomAd("fake"))
        assertNull(service(txt + ("gv" to "x")).toRoomAd("fake"))
        assertNull(service(txt, addresses = emptyList()).toRoomAd("fake"))
        assertEquals("host.local", service(txt, addresses = emptyList(), host = "host.local").toRoomAd("fake")!!.host)
        assertEquals("192.168.1.7", service(txt, addresses = listOf("fe80::1%wlan0", "192.168.1.7")).toRoomAd("fake")!!.host)
    }
}
