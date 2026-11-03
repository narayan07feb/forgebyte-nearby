package com.forgebyte.nearby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Bump when a message changes in a way an older app cannot read. A joiner with another version is refused. */
public const val PROTOCOL_VERSION: Int = 2

/** Messages a joiner sends to the host. Moves travel as JSON in the game's own move format. */
@Serializable
public sealed interface ClientMsg {
    /**
     * First message on every connection. With [reconnectToken]: take back that seat after a drop. With [roomSecret]
     * (from the QR or typed code): join at once. With neither: ask the host to let you in.
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val protocolVersion: Int,
        val gameId: String,
        val gameVersion: Int,
        val name: String,
        val roomSecret: String? = null,
        val reconnectToken: String? = null,
        /**
         * Random, made once per join and kept across retries until the Welcome: a retry whose first socket died
         * after the host seated it (or queued its request) gets the same seat or request back, not a second one.
         * Optional: older joiners do not send it.
         */
        val joinId: String? = null,
    ) : ClientMsg

    @Serializable
    @SerialName("ready")
    public data class Ready(val ready: Boolean) : ClientMsg

    /**
     * [seenSeq] is the seq of the view the player acted on, so a move that lost a race can be told apart from an
     * illegal one ("Too late, another player already did that"). Optional: older joiners do not send it.
     */
    @Serializable
    @SerialName("move")
    public data class Move(val move: JsonElement, val seenSeq: Int? = null) : ClientMsg

    @Serializable
    @SerialName("leave")
    public data object Leave : ClientMsg
}

/** Messages the host sends. Each one goes to one seat only. */
@Serializable
public sealed interface HostMsg {
    /** Join accepted (by code, by the host, or a reconnect). Keep [reconnectToken] to get the same seat back after a drop; it changes on every welcome. */
    @Serializable
    @SerialName("welcome")
    public data class Welcome(val reconnectToken: String, val lobby: Lobby) : HostMsg

    @Serializable
    @SerialName("refused")
    public data class Refused(val reason: RefuseReason, val message: String) : HostMsg

    @Serializable
    @SerialName("lobby")
    public data class LobbyUpdate(val lobby: Lobby) : HostMsg

    /**
     * The game as this seat may see it. [seq] grows by one with every change, so a client can drop stale views.
     * [turn] is set while a player's turn clock runs (null for bots, a finished game or a room without a timer).
     */
    @Serializable
    @SerialName("view")
    public data class View(val seq: Int, val view: JsonElement, val turn: TurnInfo? = null) : HostMsg

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

/**
 * Whose turn clock is running and how much time was left when the host sent this. Remaining time, not a clock time,
 * because the phones' clocks differ; the receiver counts down from when it arrived.
 */
@Serializable
public data class TurnInfo(val seat: Int, val endsInMs: Long)

@Serializable
public enum class RefuseReason {
    PROTOCOL_MISMATCH, WRONG_GAME, GAME_VERSION_MISMATCH, WRONG_SECRET, ROOM_FULL, GAME_STARTED, RECONNECT_EXPIRED,
    DECLINED, REQUEST_TIMED_OUT, TOO_MANY_REQUESTS,
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

    // SerializationException is an IllegalArgumentException.
    private inline fun <T> decodeOrNull(block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { null }
}

/** A running turn clock as this phone sees it: whose turn, and when it runs out on this phone's own clock. */
public data class TurnTimer(val seat: Int, val deadline: TimeSource.Monotonic.ValueTimeMark) {
    /** Time left, never below zero; read it on each frame of a countdown. */
    public val remaining: Duration get() = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)

    internal companion object {
        fun from(info: TurnInfo?): TurnTimer? =
            info?.let { TurnTimer(it.seat, TimeSource.Monotonic.markNow() + it.endsInMs.milliseconds) }
    }
}
