package com.forgebyte.nearby

import com.appstractive.dnssd.DiscoveredService
import com.appstractive.dnssd.DiscoveryEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
        assertNull(service(txt, addresses = listOf("[fe80:0:0:0:3436:88ff:fe7a:3451]")).toRoomAd("fake"), "IPv6 only")
    }

    @Test
    fun givenHostAddresses_whenTxtBuilt_thenIpv4OnlyAtMostThree() {
        val txt = roomTxt("fake", config, 3, listOf("192.168.29.15", "fe80::1%wlan0", "10.0.0.2", "x.y", "172.16.0.9", "192.168.43.1"))
        assertEquals("192.168.29.15,10.0.0.2,172.16.0.9", txt["a"])
        assertNull(roomTxt("fake", config, 3, listOf("fe80::1")).get("a"), "no IPv4: no key")
    }

    // Two Android 9-13 hosts on one Wi-Fi both answer to "Android.local" (Mi TV, 7 Nov 2026): the resolver can hand the
    // joiner the other device's address for this room.
    private val mine = listOf("192.168.29.40")
    private val wrong = listOf("192.168.29.21")

    @Test
    fun givenTwoHostsNamedAndroidLocal_whenResolverGivesTheOtherOne_thenTheHostsOwnAddressWins() {
        val oldHost = service(roomTxt("fake", config, 2), wrong, host = "Android.local")
        assertEquals("192.168.29.21", oldHost.toRoomAd("fake", mine)!!.host, "a host without the key: the resolver's pick")
        val newHost = service(roomTxt("fake", config, 2, listOf("192.168.29.15")), wrong, host = "Android.local")
        assertEquals("192.168.29.15", newHost.toRoomAd("fake", mine)!!.host)
    }

    @Test
    fun givenListedAddresses_whenRead_thenMyNetworkThenResolvedIfListedThenFirstListed() {
        val txt = roomTxt("fake", config, 2, listOf("10.0.0.2", "192.168.29.15"))
        assertEquals("192.168.29.15", service(txt, listOf("10.0.0.2")).toRoomAd("fake", mine)!!.host, "my network first")
        assertEquals("10.0.0.2", service(txt, listOf("10.0.0.2")).toRoomAd("fake", listOf("172.16.0.1"))!!.host, "resolved and listed")
        assertEquals("10.0.0.2", service(txt, wrong).toRoomAd("fake", emptyList())!!.host, "resolved not listed: first listed")
        assertEquals("10.0.0.2", service(txt, emptyList()).toRoomAd("fake", emptyList())!!.host, "nothing resolved yet")
        assertEquals("192.168.29.21", service(roomTxt("fake", config, 2) + ("a" to "999.1.1.1,x"), wrong).toRoomAd("fake", mine)!!.host, "junk ignored")
    }

    // Event order as NSD sends it (night run 2026-11-04: the first try found nothing; the logs were gone)

    private val txt = roomTxt("fake", config, 3)
    private val ipv6Only = service(txt, addresses = listOf("fe80::3436:88ff:fe7a:3451"))
    private val ipv4 = service(txt)

    private fun rooms(vararg events: DiscoveryEvent): List<List<RoomAd>> {
        var out: List<List<RoomAd>> = emptyList()
        runTest { out = flowOf(*events).toRooms("fake").toList() }
        return out
    }

    @Test
    fun givenDiscoveredThenResolved_whenRead_thenResolveAskedOnceAndRoomShows() {
        var asked = 0
        val out = rooms(DiscoveryEvent.Discovered(ipv6Only.copy(addresses = emptyList())) { asked++ }, DiscoveryEvent.Resolved(ipv4) {})
        assertEquals(1, asked)
        assertEquals(listOf(emptyList(), emptyList(), listOf("192.168.1.7")), out.map { l -> l.map { it.host } })
    }

    @Test
    fun givenIpv6OnlyFirst_whenIpv4ComesLater_thenAskedAgainAndRoomShowsThen() {
        var again = 0
        val out = rooms(DiscoveryEvent.Resolved(ipv6Only) { again++ }, DiscoveryEvent.Resolved(ipv4) {})
        assertEquals(1, again, "an IPv6-only answer asks for the address again")
        assertEquals(listOf(emptyList(), emptyList(), listOf("192.168.1.7")), out.map { l -> l.map { it.host } })
    }

    @Test
    fun givenOtherGameWithoutAddress_whenResolved_thenNotAskedAgain() {
        var again = 0
        rooms(DiscoveryEvent.Resolved(service(roomTxt("uno", config, 3), addresses = emptyList())) { again++ })
        assertEquals(0, again)
    }

    @Test
    fun givenRoomGoesAway_whenRemovedThenBackOnANewPort_thenListFollows() {
        val out = rooms(DiscoveryEvent.Resolved(ipv4) {}, DiscoveryEvent.Removed(ipv4), DiscoveryEvent.Resolved(ipv4.copy(port = 40999)) {})
        assertEquals(listOf(emptyList(), listOf(40123), emptyList(), listOf(40999)), out.map { l -> l.map { it.port } })
    }
}
