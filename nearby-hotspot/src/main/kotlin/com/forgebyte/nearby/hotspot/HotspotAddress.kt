package com.forgebyte.nearby.hotspot

import java.net.Inet4Address
import java.net.NetworkInterface

/** Hotspot interface names seen on Android phones (AOSP `ap0`, Qualcomm `swlan0`, some phones `wlan1` or `softap0`). */
private val HOTSPOT_NAMES = Regex("^(ap|swlan|softap)\\d+$|^wlan[1-9]\\d*$")

/**
 * The host's address on the hotspot it just started, from (interface name, IPv4 address) pairs taken [before] and
 * [now]. A new address on a hotspot-named interface comes first, then any new address, then an older address on a
 * hotspot-named interface. Null until Android has set one. This keeps the short code off the home Wi-Fi and mobile
 * data, whose interfaces were already up before the start.
 */
internal fun pickHotspotAddress(before: List<Pair<String, String>>, now: List<Pair<String, String>>): String? {
    val fresh = now.filter { it !in before }
    return (fresh.firstOrNull { HOTSPOT_NAMES.matches(it.first) } ?: fresh.firstOrNull()
        ?: now.firstOrNull { HOTSPOT_NAMES.matches(it.first) })?.second
}

/** Site-local IPv4 addresses of this device as (interface name, address). */
internal fun siteLocalAddresses(): List<Pair<String, String>> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
        .filter { (_, address) -> address is Inet4Address && address.isSiteLocalAddress }
        .mapNotNull { (name, address) -> address.hostAddress?.let { name to it } }
