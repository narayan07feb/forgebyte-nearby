package com.forgebyte.nearby

import com.appstractive.dnssd.DiscoveredService
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

/** How to reach a room without discovery (QR code or a typed code), for routers and hotspots that block mDNS. */
public data class JoinCode(val host: String, val port: Int, val secret: String) {
    override fun toString(): String = "$host:$port:$secret"

    public companion object {
        public fun parse(text: String): JoinCode? {
            val parts = text.trim().split(':')
            if (parts.size != 3 || parts[0].isBlank() || parts[2].isBlank()) return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return JoinCode(parts[0], port, parts[2])
        }
    }
}

internal object Txt {
    const val GAME = "g"
    const val PROTOCOL = "pv"
    const val GAME_VERSION = "gv"
    const val NAME = "n"
    const val FREE = "f"
}

/** The advertised TXT record. Public facts only: never the room code or anything a joiner could use to skip the host. */
internal fun roomTxt(gameId: String, config: RoomConfig, freeSeats: Int): Map<String, String> = mapOf(
    Txt.GAME to gameId,
    Txt.PROTOCOL to PROTOCOL_VERSION.toString(),
    Txt.GAME_VERSION to config.gameVersion.toString(),
    Txt.NAME to config.roomName.take(MAX_ROOM_NAME),
    Txt.FREE to freeSeats.toString(),
)

internal const val MAX_ROOM_NAME = 40

/** Rooms of [gameId] on the local network, updated as they appear, change and go away. */
public fun discoverRooms(gameId: String): Flow<List<RoomAd>> = flow {
    val found = mutableMapOf<String, RoomAd>()
    emit(emptyList())
    discoverServices(SERVICE_TYPE).collect { event ->
        when (event) {
            is DiscoveryEvent.Discovered -> event.resolve() // addresses arrive with Resolved
            is DiscoveryEvent.Resolved -> event.service.toRoomAd(gameId)
                ?.let { found[event.service.key] = it } ?: found.remove(event.service.key)
            is DiscoveryEvent.Removed -> found.remove(event.service.key)
        }
        emit(found.values.toList())
    }
}

internal fun DiscoveredService.toRoomAd(gameId: String): RoomAd? {
    fun text(key: String) = txt[key]?.decodeToString()
    if (text(Txt.GAME) != gameId) return null
    // Prefer IPv4: NSD and JmDNS can list a link-local IPv6 address first, which the join code cannot carry.
    val address = addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull() ?: host.takeIf { it.isNotBlank() }
        ?: return null
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

/** Site-local IPv4 addresses of this device, for the join code. */
internal expect fun localAddresses(): List<String>
