package com.forgebyte.nearby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Bump when a message changes in a way an older app cannot read. A joiner with another version is refused. */
public const val PROTOCOL_VERSION: Int = 1

/** Messages a joiner sends to the host. Moves travel as JSON in the game's own move format. */
@Serializable
public sealed interface ClientMsg {
    /** First message on every connection. [reconnectToken] is set when coming back to a seat after a drop. */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val protocolVersion: Int,
        val gameId: String,
        val gameVersion: Int,
        val name: String,
        val roomSecret: String,
        val reconnectToken: String? = null,
    ) : ClientMsg

    @Serializable
    @SerialName("ready")
    public data class Ready(val ready: Boolean) : ClientMsg

    @Serializable
    @SerialName("move")
    public data class Move(val move: JsonElement) : ClientMsg

    @Serializable
    @SerialName("leave")
    public data object Leave : ClientMsg
}

/** Messages the host sends. Each one goes to one seat only. */
@Serializable
public sealed interface HostMsg {
    /** Join accepted. Keep [reconnectToken] to get the same seat back after a drop; it changes on every welcome. */
    @Serializable
    @SerialName("welcome")
    public data class Welcome(val reconnectToken: String, val lobby: Lobby) : HostMsg

    @Serializable
    @SerialName("refused")
    public data class Refused(val reason: RefuseReason, val message: String) : HostMsg

    @Serializable
    @SerialName("lobby")
    public data class LobbyUpdate(val lobby: Lobby) : HostMsg

    /** The game as this seat may see it. [seq] grows by one with every change, so a client can drop stale views. */
    @Serializable
    @SerialName("view")
    public data class View(val seq: Int, val view: JsonElement) : HostMsg

    @Serializable
    @SerialName("rejected")
    public data class MoveRejected(val message: String) : HostMsg

    @Serializable
    @SerialName("kicked")
    public data object Kicked : HostMsg

    @Serializable
    @SerialName("closed")
    public data class RoomClosed(val reason: CloseReason) : HostMsg
}

@Serializable
public enum class RefuseReason {
    PROTOCOL_MISMATCH, WRONG_GAME, GAME_VERSION_MISMATCH, WRONG_SECRET, ROOM_FULL, GAME_STARTED, RECONNECT_EXPIRED,
}

@Serializable
public enum class CloseReason { HOST_LEFT }

@Serializable
public enum class Phase { LOBBY, PLAYING, OVER, CLOSED }

@Serializable
public enum class SeatKind { HOST, PLAYER, BOT }

/** Public seat info. Never holds a token or anything private. */
@Serializable
public data class SeatInfo(val name: String, val kind: SeatKind, val ready: Boolean, val connected: Boolean)

/** The room as one seat sees it; [yourSeat] is that seat's index in [seats]. */
@Serializable
public data class Lobby(
    val roomName: String,
    val gameId: String,
    val maxSeats: Int,
    val phase: Phase,
    val seats: List<SeatInfo>,
    val yourSeat: Int,
)

public object NearbyProtocol {
    public val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(msg: ClientMsg): String = json.encodeToString(ClientMsg.serializer(), msg)
    public fun encode(msg: HostMsg): String = json.encodeToString(HostMsg.serializer(), msg)

    /** Null for anything that is not a known message (junk, or a newer app's message type). */
    public fun decodeClient(text: String): ClientMsg? = decodeOrNull { json.decodeFromString(ClientMsg.serializer(), text) }
    public fun decodeHost(text: String): HostMsg? = decodeOrNull { json.decodeFromString(HostMsg.serializer(), text) }

    private inline fun <T> decodeOrNull(block: () -> T): T? =
        try { block() } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
}
