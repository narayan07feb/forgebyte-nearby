@file:OptIn(ExperimentalForeignApi::class)

package com.forgebyte.nearby

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.sockaddr_in

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    if (size == 0) return@also
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed: $status" }
}

/** Apple's CryptoKit: ECDH P-256, HKDF-SHA256, AES-GCM and SHA-256, like the JDK provider on Android. */
internal actual val cryptoProvider: CryptographyProvider = CryptographyProvider.CryptoKit

/** Bonjour on iOS needs no setup call. */
internal actual fun ensureDiscoveryReady() {}

internal actual fun localAddresses(): List<String> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) return emptyList()
    try {
        generateSequence(head.value) { it.pointed.ifa_next }.mapNotNull { ifa ->
            val flags = ifa.pointed.ifa_flags.toInt()
            val addr = ifa.pointed.ifa_addr ?: return@mapNotNull null
            if (flags and IFF_UP == 0 || flags and IFF_LOOPBACK != 0 || addr.pointed.sa_family.toInt() != AF_INET) {
                return@mapNotNull null
            }
            // s_addr is in network byte order, so the first octet is the lowest byte on these little-endian chips.
            val ip = addr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.toLong()
            listOf(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff).takeIf(::siteLocal)
                ?.joinToString(".")
        }.toList()
    } finally {
        freeifaddrs(head.value)
    }
}

/** 10/8, 172.16/12 and 192.168/16, like Java's Inet4Address.isSiteLocalAddress. */
internal fun siteLocal(o: List<Long>): Boolean =
    o[0] == 10L || (o[0] == 172L && o[1] in 16L..31L) || (o[0] == 192L && o[1] == 168L)
