package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortCodeTest {
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val samples = listOf(ShortCode(1, 1), ShortCode(254, 65535), ShortCode(7, 40123), ShortCode(103, 41237),
        ShortCode(128, 32768), ShortCode(31, 1023))

    /** A second, independent encoder, so tests can build codes the real one never makes. */
    private fun encode(value: Int): String {
        val data = (0 until 5).map { i -> (value shr (5 * (4 - i))) and 31 }
        val sum = data.withIndex().sumOf { (i, v) -> (i + 1) * v }
        val check = (0 until 31).single { (sum + 6 * it) % 31 == 0 }
        return (data + check).joinToString("") { alphabet[it].toString() }
    }

    @Test
    fun givenCodes_whenWrittenAndRead_thenRoundTripInXxxDashXxxForm() {
        samples.forEach { code ->
            val text = code.toString()
            assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{3}-[0-9A-HJKMNP-TV-Z]{3}").matches(text), text)
            assertEquals(code, ShortCode.parse(text), text)
            assertEquals(text, encode((code.lastByte shl 16) or code.port).let { it.take(3) + "-" + it.drop(3) })
        }
    }

    @Test
    fun givenTypedLoosely_whenRead_thenCaseDashesSpacesAndLookAlikesAreAccepted() {
        samples.forEach { code ->
            val text = code.toString()
            assertEquals(code, ShortCode.parse(text.lowercase()))
            assertEquals(code, ShortCode.parse(" ${text.replace("-", "")} "))
            assertEquals(code, ShortCode.parse(text.replace("-", " - ")))
            assertEquals(code, ShortCode.parse(text.replace('0', 'O').replace('1', 'I')))
            assertEquals(code, ShortCode.parse(text.replace('1', 'l')))
        }
    }

    @Test
    fun givenOneWrongCharacter_whenRead_thenRefusedUnlessItIsAZeroZMixUp() {
        samples.forEach { code ->
            val text = code.toString().replace("-", "")
            text.indices.forEach { i ->
                alphabet.filter { it != text[i] }.forEach { c ->
                    val typo = text.substring(0, i) + c + text.substring(i + 1)
                    if (setOf(text[i], c) != setOf('0', 'Z')) assertNull(ShortCode.parse(typo), "$text -> $typo")
                }
            }
        }
    }

    @Test
    fun givenTwoNeighboursSwapped_whenRead_thenRefusedUnlessTheyAreZeroAndZ() {
        samples.forEach { code ->
            val text = code.toString().replace("-", "")
            (0 until text.length - 1).filter { text[it] != text[it + 1] }.forEach { i ->
                val swapped = text.substring(0, i) + text[i + 1] + text[i] + text.substring(i + 2)
                if (setOf(text[i], text[i + 1]) != setOf('0', 'Z')) assertNull(ShortCode.parse(swapped), swapped)
            }
        }
    }

    @Test
    fun givenJunkOrImpossibleValues_whenRead_thenNull() {
        listOf("", "K7Q-2M", "K7Q-2MXX", "K7Q-2MU", "K7Q_2MX", "192.168.1.7:40123").forEach {
            assertNull(ShortCode.parse(it), it)
        }
        assertNull(ShortCode.parse(encode(1 shl 24 or (7 shl 16) or 40123)), "version bit set: a newer code")
        assertNull(ShortCode.parse(encode(0 shl 16 or 40123)), "last byte 0 is the network address")
        assertNull(ShortCode.parse(encode(255 shl 16 or 40123)), "last byte 255 is broadcast")
        assertNull(ShortCode.parse(encode(7 shl 16)), "port 0")
    }

    @Test
    fun givenHostAddress_whenCodeMade_thenOnlyUsableIpv4AddressesGetOne() {
        assertEquals(ShortCode(7, 40123), ShortCode.of("192.168.1.7", 40123))
        assertEquals(ShortCode(1, 5), ShortCode.of("10.0.0.1", 5))
        listOf("192.168.1.0", "192.168.1.255", "fe80::1", "host.local", "1.2.3").forEach {
            assertNull(ShortCode.of(it, 40123), it)
        }
        assertNull(ShortCode.of("192.168.1.7", 0))
        assertFailsWith<IllegalArgumentException> { ShortCode(0, 1) }
        assertFailsWith<IllegalArgumentException> { ShortCode(1, 65536) }
    }

    @Test
    fun givenWifiAndHotspotOrVpn_whenAddressesOrdered_thenWifiComesFirstAndPicksTheHostsNetwork() {
        val hotspotAndWifi = listOf("ap0" to "192.168.43.1", "wlan0" to "192.168.1.12")
        assertEquals(listOf("192.168.1.12", "192.168.43.1"), wifiFirst(hotspotAndWifi))
        assertEquals("192.168.1.7", wifiFirst(hotspotAndWifi).firstNotNullOfOrNull(ShortCode(7, 40123)::hostOn))
        assertEquals(listOf("10.0.0.4", "10.8.0.2"), wifiFirst(listOf("utun3" to "10.8.0.2", "en0" to "10.0.0.4")))
        assertEquals(listOf("192.168.1.5", "172.16.0.9", "10.8.0.2"),
            wifiFirst(listOf("rmnet0" to "172.16.0.9", "wlan0" to "192.168.1.5", "tun0" to "10.8.0.2")), "others keep their order")
        // The Lava's names (ceo, read-only ip addr, 2026-11-05): mobile data seth_lte*, VoWiFi vowifi_tun*, Wi-Fi wlan0.
        assertEquals(listOf("192.168.0.103", "10.120.4.7", "10.66.0.2"),
            wifiFirst(listOf("seth_lte0" to "10.120.4.7", "vowifi_tun0" to "10.66.0.2", "wlan0" to "192.168.0.103")))
        assertEquals(emptyList(), wifiFirst(emptyList()))
    }

    @Test
    fun givenJoinerAddress_whenHostLookedUp_thenSameFirstThreeBytesAndTheCodesLastByte() {
        assertEquals("192.168.43.7", ShortCode(7, 40123).hostOn("192.168.43.12"))
        assertEquals("10.0.0.200", ShortCode(200, 1).hostOn("10.0.0.5"))
        listOf("fe80::1", "1.2.3", "1.2.3.x", "1.2.3.256").forEach { assertNull(ShortCode(7, 1).hostOn(it), it) }
    }
}
