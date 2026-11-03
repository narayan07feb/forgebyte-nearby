package com.forgebyte.nearby

/** The connection id the host's own seat uses. The transport delivers these messages on the host phone itself. */
public const val HOST_CONN: String = "host"

public class RoomConfig(
    public val roomName: String,
    public val hostName: String,
    public val gameVersion: Int,
    public val roomSecret: String,
    public val maxSeats: Int,
    public val minSeats: Int = 2,
    public val reconnectWindowMs: Long = 60_000,
) {
    init {
        require(minSeats in 1..maxSeats) { "need 1 <= minSeats <= maxSeats" }
    }
}

/** What the transport must do after a room call: send a message to a connection, or close it. */
public sealed interface RoomOutput {
    public data class Send(val conn: String, val msg: HostMsg) : RoomOutput
    public data class Close(val conn: String) : RoomOutput
}

/**
 * The room on the host phone: seats, lobby, the game state and the referee. Pure logic, no sockets: the transport
 * passes in what arrived and carries out the returned [RoomOutput]s. Not thread safe; call it from one coroutine.
 *
 * Seat 0 is the host. In the lobby a seat that leaves, is kicked or stays dropped past the reconnect window is
 * removed; once the game started, a host bot takes that seat over so the game can go on.
 */
public class Room<S, M, V>(
    private val game: NearbyGame<S, M, V>,
    private val config: RoomConfig,
    private val clock: () -> Long,
    private val newToken: () -> String = ::secureToken,
) {
    private class Seat(var name: String, var kind: SeatKind, var conn: String?) {
        var token: String? = null
        var ready = kind != SeatKind.PLAYER
        var droppedAt: Long? = null
    }

    private val seats = mutableListOf(Seat(cleanName(config.hostName, 0), SeatKind.HOST, HOST_CONN))
    private var out = mutableListOf<RoomOutput>()
    private var seq = 0

    public var phase: Phase = Phase.LOBBY
        private set

    internal var state: S? = null
        private set

    public val seatCount: Int get() = seats.size

    /** The room as the host sees it. */
    public val hostLobby: Lobby get() = lobbyFor(0)

    /** Every human joiner is connected and ready, and there are enough seats. */
    public val canStart: Boolean
        get() = phase == Phase.LOBBY && seats.size >= config.minSeats &&
            seats.all { it.kind != SeatKind.PLAYER || (it.ready && it.conn != null) }

    public fun onMessage(conn: String, msg: ClientMsg): List<RoomOutput> = step {
        val seat = seatOf(conn)
        when {
            msg is ClientMsg.Hello -> if (seat == null) hello(conn, msg)
            seat == null -> Unit // nothing but hello before a seat is given
            msg is ClientMsg.Ready -> if (phase == Phase.LOBBY) {
                seats[seat].ready = msg.ready
                broadcastLobby()
            }
            msg is ClientMsg.Move -> remoteMove(seat, msg)
            msg is ClientMsg.Leave -> {
                out += RoomOutput.Close(conn)
                drop(seat)
            }
        }
    }

    public fun onDisconnect(conn: String): List<RoomOutput> = step {
        val seat = seatOf(conn) ?: return@step
        seats[seat].conn = null
        seats[seat].droppedAt = clock()
        broadcastLobby()
    }

    /** Call about once a second so dropped seats past the reconnect window are freed or taken over by a bot. */
    public fun tick(): List<RoomOutput> = step {}

    public fun addBot(): List<RoomOutput> = step {
        if (phase != Phase.LOBBY || seats.size >= config.maxSeats) return@step
        seats += Seat("Bot ${seats.count { it.kind == SeatKind.BOT } + 1}", SeatKind.BOT, null)
        broadcastLobby()
    }

    public fun kick(seat: Int): List<RoomOutput> = step {
        if (seat !in 1 until seats.size) return@step
        seats[seat].conn?.let { out += RoomOutput.Send(it, HostMsg.Kicked); out += RoomOutput.Close(it) }
        drop(seat)
    }

    public fun start(seed: Long): List<RoomOutput> = step {
        if (!canStart) return@step
        state = game.newGame(seats.size, seed)
        phase = Phase.PLAYING
        runBots()
        changed()
    }

    public fun hostMove(move: M): List<RoomOutput> = step { play(0, move) }

    /** The host closes the room: everyone is told and disconnected. */
    public fun hostLeave(): List<RoomOutput> = step {
        phase = Phase.CLOSED
        seats.drop(1).mapNotNull { it.conn }.forEach {
            out += RoomOutput.Send(it, HostMsg.RoomClosed(CloseReason.HOST_LEFT))
            out += RoomOutput.Close(it)
        }
    }

    private inline fun step(block: () -> Unit): List<RoomOutput> {
        out = mutableListOf()
        if (phase != Phase.CLOSED) {
            expireDropped()
            block()
        }
        return out
    }

    private fun hello(conn: String, h: ClientMsg.Hello) {
        val refusal = when {
            h.protocolVersion != PROTOCOL_VERSION -> RefuseReason.PROTOCOL_MISMATCH to
                "This room uses a different version of nearby play. Update the app on both phones."
            h.gameId != game.gameId -> RefuseReason.WRONG_GAME to "This room is for another game."
            h.gameVersion != config.gameVersion -> RefuseReason.GAME_VERSION_MISMATCH to
                "The host has game version ${config.gameVersion}, you have ${h.gameVersion}. Update the app on both phones."
            !sameSecret(h.roomSecret, config.roomSecret) -> RefuseReason.WRONG_SECRET to "Wrong room code."
            else -> null
        }
        if (refusal != null) return refuse(conn, refusal.first, refusal.second)

        if (h.reconnectToken != null) {
            val seat = seats.indexOfFirst { t -> t.token?.let { sameSecret(it, h.reconnectToken) } == true }
            if (seat < 0) return refuse(conn, RefuseReason.RECONNECT_EXPIRED, "Your seat is gone. Join again.")
            seats[seat].conn?.let { out += RoomOutput.Close(it) } // a half-open old socket
            seats[seat].conn = conn
            seats[seat].droppedAt = null
            welcome(seat)
            if (phase != Phase.LOBBY) sendView(seat)
            return
        }
        if (phase != Phase.LOBBY) return refuse(conn, RefuseReason.GAME_STARTED, "The game has already started.")
        if (seats.size >= config.maxSeats) return refuse(conn, RefuseReason.ROOM_FULL, "The room is full.")
        seats += Seat(cleanName(h.name, seats.size), SeatKind.PLAYER, conn)
        welcome(seats.lastIndex)
    }

    private fun refuse(conn: String, reason: RefuseReason, message: String) {
        out += RoomOutput.Send(conn, HostMsg.Refused(reason, message))
        out += RoomOutput.Close(conn)
    }

    /** A fresh token on every welcome, so an old one cannot be replayed. */
    private fun welcome(seat: Int) {
        val token = newToken()
        seats[seat].token = token
        out += RoomOutput.Send(seats[seat].conn!!, HostMsg.Welcome(token, lobbyFor(seat)))
        broadcastLobby(except = seat)
    }

    private fun remoteMove(seat: Int, msg: ClientMsg.Move) {
        val move = try {
            NearbyProtocol.json.decodeFromJsonElement(game.moveSerializer, msg.move)
        } catch (_: IllegalArgumentException) {
            return reject(seat, "Unknown move.")
        }
        play(seat, move)
    }

    private fun play(seat: Int, move: M) {
        val current = state
        if (phase != Phase.PLAYING || current == null) return reject(seat, "No game is running.")
        game.apply(current, seat, move).fold(
            onSuccess = {
                state = it
                runBots()
                changed()
            },
            onFailure = { reject(seat, it.message ?: "That move is not allowed.") },
        )
    }

    private fun reject(seat: Int, message: String) {
        seats[seat].conn?.let { out += RoomOutput.Send(it, HostMsg.MoveRejected(message)) }
    }

    /** Lobby: the seat goes away. In a game: a bot plays it from now on. */
    private fun drop(seat: Int) {
        if (phase == Phase.LOBBY) {
            seats.removeAt(seat)
            broadcastLobby()
            return
        }
        seats[seat].apply { kind = SeatKind.BOT; conn = null; token = null; droppedAt = null; ready = true }
        if (phase == Phase.PLAYING) runBots()
        changed()
    }

    private fun expireDropped() {
        val now = clock()
        for (i in seats.indices.reversed()) {
            val at = seats[i].droppedAt ?: continue
            if (now - at >= config.reconnectWindowMs) drop(i)
        }
    }

    private fun runBots() {
        repeat(MAX_BOT_STEPS) {
            var moved = false
            for (i in seats.indices) {
                val s = state ?: return
                if (game.isOver(s)) return
                if (seats[i].kind != SeatKind.BOT) continue
                val move = game.botMove(s, i) ?: continue
                game.apply(s, i, move).onSuccess { state = it; moved = true }
            }
            if (!moved) return
        }
    }

    /** The game changed: every seat gets its own view, and the lobby when the game ended. */
    private fun changed() {
        val s = state ?: return
        seq++
        if (game.isOver(s)) phase = Phase.OVER
        seats.indices.forEach(::sendView)
        broadcastLobby()
    }

    private fun sendView(seat: Int) {
        val conn = seats[seat].conn ?: return
        val view = NearbyProtocol.json.encodeToJsonElement(game.viewSerializer, game.viewFor(state ?: return, seat))
        out += RoomOutput.Send(conn, HostMsg.View(seq, view))
    }

    private fun broadcastLobby(except: Int = -1) {
        seats.forEachIndexed { i, s ->
            if (i != except) s.conn?.let { out += RoomOutput.Send(it, HostMsg.LobbyUpdate(lobbyFor(i))) }
        }
    }

    private fun lobbyFor(seat: Int) = Lobby(
        roomName = config.roomName,
        gameId = game.gameId,
        maxSeats = config.maxSeats,
        phase = phase,
        seats = seats.map { SeatInfo(it.name, it.kind, it.ready, it.kind == SeatKind.BOT || it.conn != null) },
        yourSeat = seat,
    )

    private fun seatOf(conn: String) = seats.indexOfFirst { it.conn == conn }.takeIf { it >= 0 }

    private companion object {
        const val MAX_NAME = 20
        const val MAX_BOT_STEPS = 10_000

        fun cleanName(raw: String, seat: Int) =
            raw.filter { !it.isISOControl() }.trim().take(MAX_NAME).ifBlank { "Player ${seat + 1}" }

        /** Compares in time that does not depend on where the strings differ. */
        fun sameSecret(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

/** A random 128-bit token from the platform's secure random source. */
internal expect fun secureToken(): String
