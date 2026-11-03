package com.forgebyte.nearby

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureToken(): String =
    ByteArray(16).also(random::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal actual fun localAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
