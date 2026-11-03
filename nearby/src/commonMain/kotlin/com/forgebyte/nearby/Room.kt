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
    /** A join request the host has not answered by then is refused. */
    public val requestTimeoutMs: Long = 60_000,
    /** More open requests than this are refused straight away, so a flood cannot fill the host's screen. */
    public val maxPendingRequests: Int = 4,
) {
    init {
        require(minSeats in 1..maxSeats) { "need 1 <= minSeats <= maxSeats" }
    }
}

/** Someone asking to join; the host accepts or declines it by [id]. */
public data class JoinRequest(val id: Int, val name: String)

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
 *
 * Three ways in: a hello with the room code (QR or typed) takes a seat at once; a hello with a reconnect token
 * takes back that seat; a hello with neither is a join request (name only) that waits for the host's answer.
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

    private class Pending(val id: Int, val conn: String, val name: String, val at: Long)

    private val seats = mutableListOf(Seat(cleanName(config.hostName, 0), SeatKind.HOST, HOST_CONN))
    private val pending = mutableListOf<Pending>()
    private var nextRequest = 1
    private var out = mutableListOf<RoomOutput>()
    private var seq = 0

    public var phase: Phase = Phase.LOBBY
        private set

    internal var state: S? = null
        private set

    public val seatCount: Int get() = seats.size

    /** Join requests waiting for the host, oldest first. */
    public val joinRequests: List<JoinRequest> get() = pending.map { JoinRequest(it.id, it.name) }

    /** The room as the host sees it. */
    public val hostLobby: Lobby get() = lobbyFor(0)

    /** Every human joiner is connected and ready, and there are enough seats. */
    public val canStart: Boolean
        get() = phase == Phase.LOBBY && seats.size >= config.minSeats &&
            seats.all { it.kind != SeatKind.PLAYER || (it.ready && it.conn != null) }

    public fun onMessage(conn: String, msg: ClientMsg): List<RoomOutput> = step {
        val seat = seatOf(conn)
        when {
            msg is ClientMsg.Hello -> if (seat == null && pending.none { it.conn == conn }) hello(conn, msg)
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
        pending.removeAll { it.conn == conn }
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

    public fun acceptJoin(id: Int): List<RoomOutput> = step {
        val p = takeRequest(id) ?: return@step
        when {
            phase != Phase.LOBBY -> refuse(p.conn, RefuseReason.GAME_STARTED, GAME_STARTED)
            seats.size >= config.maxSeats -> refuse(p.conn, RefuseReason.ROOM_FULL, ROOM_FULL)
            else -> {
                seats += Seat(p.name, SeatKind.PLAYER, p.conn)
                welcome(seats.lastIndex)
            }
        }
    }

    public fun declineJoin(id: Int): List<RoomOutput> = step {
        takeRequest(id)?.let { refuse(it.conn, RefuseReason.DECLINED, "The host did not let you in.") }
    }

    public fun start(seed: Long): List<RoomOutput> = step {
        if (!canStart) return@step
        refuseAllRequests(RefuseReason.GAME_STARTED, GAME_STARTED)
        state = game.newGame(seats.size, seed)
        phase = Phase.PLAYING
        runBots()
        changed()
    }

    public fun hostMove(move: M): List<RoomOutput> = step { play(0, move) }

    /** The host closes the room: everyone is told and disconnected. */
    public fun hostLeave(): List<RoomOutput> = step {
        phase = Phase.CLOSED
        (seats.drop(1).mapNotNull { it.conn } + pending.map { it.conn }).forEach {
            out += RoomOutput.Send(it, HostMsg.RoomClosed(CloseReason.HOST_LEFT))
            out += RoomOutput.Close(it)
        }
        pending.clear()
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
            h.reconnectToken == null && h.roomSecret != null && !sameSecret(h.roomSecret, config.roomSecret) ->
                RefuseReason.WRONG_SECRET to "Wrong room code."
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
        if (phase != Phase.LOBBY) return refuse(conn, RefuseReason.GAME_STARTED, GAME_STARTED)
        if (seats.size >= config.maxSeats) return refuse(conn, RefuseReason.ROOM_FULL, ROOM_FULL)
        if (h.roomSecret == null) {
            if (pending.size >= config.maxPendingRequests) {
                return refuse(conn, RefuseReason.TOO_MANY_REQUESTS, "The host has too many requests. Try again soon.")
            }
            pending += Pending(nextRequest++, conn, cleanName(h.name, seats.size), clock())
            return
        }
        seats += Seat(cleanName(h.name, seats.size), SeatKind.PLAYER, conn)
        welcome(seats.lastIndex)
    }

    private fun takeRequest(id: Int) = pending.find { it.id == id }?.also { pending.remove(it) }

    private fun refuseAllRequests(reason: RefuseReason, message: String) {
        pending.forEach { refuse(it.conn, reason, message) }
        pending.clear()
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
        pending.filter { now - it.at >= config.requestTimeoutMs }.forEach {
            pending.remove(it)
            refuse(it.conn, RefuseReason.REQUEST_TIMED_OUT, "The host did not answer.")
        }
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
        const val GAME_STARTED = "The game has already started."
        const val ROOM_FULL = "The room is full."
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
