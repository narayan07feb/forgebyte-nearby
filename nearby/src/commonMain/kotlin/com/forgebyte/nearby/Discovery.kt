package com.forgebyte.nearby

import com.appstractive.dnssd.DiscoveredService
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** The DNS-SD service type every ForgeByte room advertises (Android NSD, iOS Bonjour, JVM JmDNS via dns-sd-kt). */
public const val SERVICE_TYPE: String = "_forgebyte._tcp"

/** WebSocket path on the host. */
public const val WS_PATH: String = "/nearby"

/**
 * A room seen on the local network. Tapping it sends a join request the host accepts or declines; the room code
 * never travels in the advertisement (owner, 2026-11-03).
 */
public data class RoomAd(
    val key: String,
    val roomName: String,
    val gameId: String,
    val protocolVersion: Int,
    val gameVersion: Int,
    val seatsFree: Int,
    val host: String,
    val port: Int,
) {
    /** False means "update the app" rather than a join that would be refused. */
    public fun canJoin(myGameVersion: Int): Boolean =
        protocolVersion == PROTOCOL_VERSION && gameVersion == myGameVersion && seatsFree > 0
}

/**
 * How to reach a room without discovery (QR code or a typed code), for routers and hotspots that block mDNS.
 * [fingerprint] pins the room's encryption key, so a code join cannot be intercepted on the way.
 *
 * Anyone who has this full code gets a seat with no host approval and no check code, until the game starts or the room
 * is full. Share it only in person or by QR; the host can kick a seat it does not know.
 */
public data class JoinCode(val host: String, val port: Int, val secret: String, val fingerprint: String) {
    override fun toString(): String = "$host:$port:$secret:$fingerprint"

    public companion object {
        public fun parse(text: String): JoinCode? {
            val parts = text.trim().split(':')
            if (parts.size != 4 || parts.any { it.isBlank() }) return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (parts[3].length != 16 || parts[3].any { it.lowercaseChar() !in "0123456789abcdef" }) return null
            return JoinCode(parts[0], port, parts[2], parts[3].lowercase())
        }
    }
}

internal object Txt {
    const val GAME = "g"
    const val PROTOCOL = "pv"
    const val GAME_VERSION = "gv"
    const val NAME = "n"
    const val FREE = "f"
    const val ADDRESS = "a"
}

/**
 * The advertised TXT record. Public facts only: never the room code or anything a joiner could use to skip the host.
 * [addresses] are the host's own IPv4 addresses (already visible on the network); see [toRoomAd] for why.
 */
internal fun roomTxt(gameId: String, config: RoomConfig, freeSeats: Int, addresses: List<String> = emptyList()): Map<String, String> =
    mapOf(
        Txt.GAME to gameId,
        Txt.PROTOCOL to PROTOCOL_VERSION.toString(),
        Txt.GAME_VERSION to config.gameVersion.toString(),
        Txt.NAME to config.roomName.take(MAX_ROOM_NAME),
        Txt.FREE to freeSeats.toString(),
    ) + addresses.filter(::isIpv4).take(MAX_TXT_ADDRESSES).joinToString(",").let { if (it.isEmpty()) emptyMap() else mapOf(Txt.ADDRESS to it) }

private const val MAX_TXT_ADDRESSES = 3
private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
private fun isIpv4(text: String) = IPV4.matchEntire(text)?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true
private fun network(address: String) = address.substringBeforeLast('.')

internal const val MAX_ROOM_NAME = 40

/** Rooms of [gameId] on the local network, updated as they appear, change and go away. */
public fun discoverRooms(gameId: String): Flow<List<RoomAd>> = flow {
    ensureDiscoveryReady()
    emitAll(discoverServices(SERVICE_TYPE).toRooms(gameId))
}

/**
 * The rooms of [gameId] seen in dns-sd [DiscoveryEvent]s; starts with an empty list. The events and this mapping run on
 * [Dispatchers.IO], never on the collector's thread: on Android below 14, dns-sd-kt looks up each room's host name
 * (reverse DNS) where the flow runs, and on the main thread that crashed the app (NetworkOnMainThreadException, Mi A3,
 * Android 11, 7 Nov 2026).
 */
internal fun Flow<DiscoveryEvent>.toRooms(gameId: String, mine: () -> List<String> = ::localAddresses): Flow<List<RoomAd>> = flow {
    val myAddresses = mine()
    val found = mutableMapOf<String, RoomAd>()
    emit(emptyList())
    collect { event ->
        when (event) {
            is DiscoveryEvent.Discovered -> event.resolve() // addresses arrive with Resolved
            is DiscoveryEvent.Resolved -> {
                val ad = event.service.toRoomAd(gameId, myAddresses)
                if (ad != null) found[event.service.key] = ad
                // Resolved without a usable address yet: ask again, the IPv4 record often comes next.
                else if (event.service.txt[Txt.GAME]?.decodeToString() == gameId) event.resolve()
            }
            is DiscoveryEvent.Removed -> found.remove(event.service.key)
        }
        emit(found.values.toList())
    }
}.flowOn(Dispatchers.IO)

/**
 * The room in a resolved service, or null if it is not a [gameId] room or has no usable address. [mine] are this
 * device's IPv4 addresses.
 *
 * The address: the resolver looks up the service's host name, and Android 9 to 13 hosts all use the same name,
 * `Android.local` (Mi TV, 7 Nov 2026). With two of them on one Wi-Fi, the lookup can return the other device's address.
 * So a host's own address from the TXT record wins: the one on this device's network (same first three bytes), else
 * the resolved one if the host lists it, else the first listed. Rooms from hosts without that key use the resolved
 * address as before.
 */
internal fun DiscoveredService.toRoomAd(gameId: String, mine: List<String> = emptyList()): RoomAd? {
    fun text(key: String) = txt[key]?.decodeToString()
    if (text(Txt.GAME) != gameId) return null
    // IPv4 or a host name only: NSD and JmDNS can resolve just a link-local IPv6 address at first ("[fe80::..]"),
    // which cannot be dialled without the interface scope (seen on the Lava, 2026-11-03).
    val resolved = (addresses + host).firstOrNull { it.isNotBlank() && ':' !in it }
    val listed = text(Txt.ADDRESS)?.split(',')?.filter(::isIpv4).orEmpty()
    val address = listed.firstOrNull { a -> mine.any { network(it) == network(a) } }
        ?: resolved?.takeIf { listed.isEmpty() || it in listed } ?: listed.firstOrNull() ?: resolved ?: return null
    return RoomAd(
        key = key,
        roomName = text(Txt.NAME) ?: name,
        gameId = gameId,
        protocolVersion = text(Txt.PROTOCOL)?.toIntOrNull() ?: return null,
        gameVersion = text(Txt.GAME_VERSION)?.toIntOrNull() ?: return null,
        seatsFree = text(Txt.FREE)?.toIntOrNull() ?: 0,
        host = address,
        port = port,
    )
}

/** Android: throws until NearbyAndroid.init(context) ran. Elsewhere nothing to do. */
internal expect fun ensureDiscoveryReady()

/** Site-local IPv4 addresses of this device, Wi-Fi first ([wifiFirst]), for the join and short codes. */
internal expect fun localAddresses(): List<String>

/**
 * Addresses as (interface name, address) pairs, Wi-Fi first: wlan* on Android, en0 on a Mac. Then the rest (a hotspot
 * the phone hosts, a VPN) in the order given, so a joiner on both picks its Wi-Fi network for a short code.
 */
internal fun wifiFirst(addresses: List<Pair<String, String>>): List<String> =
    addresses.sortedBy { (name, _) -> if (name.startsWith("wlan") || name == "en0") 0 else 1 }.map { it.second }
