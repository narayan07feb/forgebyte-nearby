package com.forgebyte.nearby

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

/** The platform's own crypto (Android: Conscrypt/BoringSSL; JVM: SunEC and SunJCE). No BouncyCastle. */
internal actual val cryptoProvider: CryptographyProvider = CryptographyProvider.JDK()

internal actual fun localAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
