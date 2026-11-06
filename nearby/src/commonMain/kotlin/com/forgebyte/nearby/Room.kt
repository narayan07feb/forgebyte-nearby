package com.forgebyte.nearby

/** The connection id the host's own seat uses. The transport delivers these messages on the host phone itself. */
public const val HOST_CONN: String = "host"

public class RoomConfig(
    /**
     * The room's label in the discovery advert. It goes unencrypted to every device on the local network: never put
     * a player name here; use a neutral label such as the game name and a short random word.
     */
    public val roomName: String,
    public val hostName: String,
    public val gameVersion: Int,
    /**
     * The secret in the full join code ([JoinCode], QR or typed). Anyone who has the full code gets a seat with no host
     * approval and no check code, until the game starts or the room is full. Make it with a secure random source, and
     * share the code only in person or by QR.
     */
    public val roomSecret: String,
    public val maxSeats: Int,
    public val minSeats: Int = 2,
    public val reconnectWindowMs: Long = 60_000,
    /** A join request the host has not answered by then is refused. */
    public val requestTimeoutMs: Long = 60_000,
    /** More open requests than this are refused straight away, so a flood cannot fill the host's screen. */
    public val maxPendingRequests: Int = 4,
    /** Bots play one move at a time, this far apart, so everyone sees each move (owner, 2026-11-03). 0: all at once. */
    public val botDelayMs: Long = 1_000,
    /**
     * A player (or the host) who has not moved this long after their turn began gets their seat's bot move played by
     * the host, like the online games' turn timer (owner, 2026-11-03). 0: no timer.
     */
    public val turnTimeoutMs: Long = 30_000,
) {
    init {
        require(minSeats in 1..maxSeats) { "need 1 <= minSeats <= maxSeats" }
    }
}

/**
 * Someone asking to join; the host accepts or declines it by [id]. The host's screen shows [checkCode] next to the
 * name; the joiner's screen shows its own. Accept only when they match: a different code means someone on the
 * network sits between the two phones.
 */
public data class JoinRequest(val id: Int, val name: String, val checkCode: String)

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
    /** The check code of a connection's encrypted channel, shown with its join request. */
    private val checkCodeOf: (conn: String) -> String = { "" },
) {
    private class Seat(var name: String, var kind: SeatKind, var conn: String?) {
        var token: String? = null
        var ready = kind != SeatKind.PLAYER
        var droppedAt: Long? = null
        /** A bot plays this seat for a player who dropped past the reconnect window; the token still brings them back. */
        var takenOver = false
        /** The joiner's join id, so a retry that never got its Welcome finds this seat again. */
        var joinId: String? = null
    }

    private class Pending(val id: Int, var conn: String, val name: String, val at: Long, var checkCode: String, val joinId: String?)

    private val seats = mutableListOf(Seat(cleanName(config.hostName, 0), SeatKind.HOST, HOST_CONN))
    private val pending = mutableListOf<Pending>()
    private var nextRequest = 1
    private var out = mutableListOf<RoomOutput>()
    private var seq = 0

    // What is due in a game: the bots' planned move at [botAt] (chosen once, against the current state), and the
    // human [turnSeat]'s deadline. Both can run at once (a bot may plan a catch during a human's turn).
    private var plan: BotMove<M>? = null
    private var botAt: Long? = null
    private var turnSeat: Int? = null
    private var turnDeadline: Long? = null

    public var phase: Phase = Phase.LOBBY
        private set

    internal var state: S? = null
        private set

    public val seatCount: Int get() = seats.size

    /** Join requests waiting for the host, oldest first. */
    public val joinRequests: List<JoinRequest> get() = pending.map { JoinRequest(it.id, it.name, it.checkCode) }

    /**
     * When the room next needs a [tick]: a bot move, a turn deadline, a reconnect window or a join request running
     * out. Null when nothing is waiting. The transport sleeps until then.
     */
    public val nextWakeAt: Long?
        get() = (listOfNotNull(botAt, turnDeadline) +
            seats.mapNotNull { s -> s.droppedAt?.let { it + config.reconnectWindowMs } } +
            pending.map { it.at + config.requestTimeoutMs }).minOrNull()

    /** The room as the host sees it. */
    public val hostLobby: Lobby get() = lobbyFor(0)

    /** Every human joiner is connected and ready, and there are enough seats. */
    public val canStart: Boolean
        get() = phase == Phase.LOBBY && seats.size >= config.minSeats &&
            seats.all { it.kind != SeatKind.PLAYER || (it.ready && it.conn != null) }

    public fun onMessage(conn: String, msg: ClientMsg): List<RoomOutput> = step {
        val seat = seatOf(conn)
        // Any message after the Hello proves this connection got its Welcome: the join id is no longer needed.
        if (seat != null && msg !is ClientMsg.Hello) seats[seat].joinId = null
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
                seats += Seat(p.name, SeatKind.PLAYER, p.conn).apply { joinId = p.joinId }
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
        advance()
    }

    /** [seenSeq]: the seq of the view the host acted on (see [ClientMsg.Move.seenSeq]). */
    public fun hostMove(move: M, seenSeq: Int? = null): List<RoomOutput> = step { play(0, move, seenSeq) }

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
            fireDue()
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
            seats[seat].joinId = null // a token means the Welcome arrived
            return reseat(seat, conn)
        }
        if (h.joinId != null) {
            // A retry of a join whose first socket died before its Welcome: same seat, or same request, again.
            val seat = seats.indexOfFirst { t -> t.joinId?.let { sameSecret(it, h.joinId) } == true }
            if (seat >= 0) return reseat(seat, conn)
            pending.firstOrNull { p -> p.joinId?.let { sameSecret(it, h.joinId) } == true }?.let { p ->
                if (p.conn != conn) out += RoomOutput.Close(p.conn)
                p.conn = conn
                p.checkCode = checkCodeOf(conn)
                return
            }
        }
        if (phase != Phase.LOBBY) return refuse(conn, RefuseReason.GAME_STARTED, GAME_STARTED)
        if (seats.size >= config.maxSeats) return refuse(conn, RefuseReason.ROOM_FULL, ROOM_FULL)
        if (h.roomSecret == null) {
            if (pending.size >= config.maxPendingRequests) {
                return refuse(conn, RefuseReason.TOO_MANY_REQUESTS, "The host has too many requests. Try again soon.")
            }
            pending += Pending(nextRequest++, conn, cleanName(h.name, seats.size), clock(), checkCodeOf(conn), h.joinId)
            return
        }
        seats += Seat(cleanName(h.name, seats.size), SeatKind.PLAYER, conn).apply { joinId = h.joinId }
        welcome(seats.lastIndex)
    }

    /** The seat's player is back on [conn] (with their token, or their join id before any Welcome). */
    private fun reseat(seat: Int, conn: String) {
        // A bot seat that was not taken over after the reconnect window was left or kicked: nobody gets it back.
        if (seats[seat].kind == SeatKind.BOT && !seats[seat].takenOver) {
            return refuse(conn, RefuseReason.RECONNECT_EXPIRED, "Your seat is gone. Join again.")
        }
        seats[seat].conn?.takeIf { it != conn }?.let { out += RoomOutput.Close(it) } // a half-open old socket
        seats[seat].conn = conn
        seats[seat].droppedAt = null
        val returning = seats[seat].takenOver
        // Back after the window: the seat is theirs again at once and any bot plan made for it is dropped.
        if (returning) seats[seat].apply { kind = SeatKind.PLAYER; takenOver = false }
        welcome(seat)
        if (returning) {
            schedule()
            changed()
        } else if (phase != Phase.LOBBY) {
            sendView(seat)
        }
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
        play(seat, move, msg.seenSeq)
    }

    private fun play(seat: Int, move: M, seenSeq: Int? = null) {
        val current = state
        if (phase != Phase.PLAYING || current == null) return reject(seat, "No game is running.")
        game.apply(current, seat, move).fold(
            onSuccess = {
                state = it
                advance(mover = seat)
            },
            onFailure = {
                if (seenSeq != null && seenSeq < seq) {
                    // Legal on the screen the player saw, but someone else moved first: say so and show the table now.
                    reject(seat, TOO_LATE)
                    sendView(seat)
                } else {
                    reject(seat, it.message ?: "That move is not allowed.")
                }
            },
        )
    }

    private fun reject(seat: Int, message: String) {
        seats[seat].conn?.let { out += RoomOutput.Send(it, HostMsg.MoveRejected(message)) }
    }

    /**
     * Lobby: the seat goes away. In a game: a bot plays it from now on; after a drop past the reconnect window
     * ([keepToken]) the player can still come back with their token and take it over again.
     */
    private fun drop(seat: Int, keepToken: Boolean = false) {
        if (phase == Phase.LOBBY) {
            seats.removeAt(seat)
            broadcastLobby()
            return
        }
        seats[seat].apply {
            kind = SeatKind.BOT
            takenOver = keepToken
            if (!keepToken) {
                token = null
                joinId = null
            }
            conn = null
            droppedAt = null
            ready = true
        }
        advance()
    }

    private fun expireDropped() {
        val now = clock()
        pending.filter { now - it.at >= config.requestTimeoutMs }.forEach {
            pending.remove(it)
            refuse(it.conn, RefuseReason.REQUEST_TIMED_OUT, "The host did not answer.")
        }
        for (i in seats.indices.reversed()) {
            val at = seats[i].droppedAt ?: continue
            if (now - at >= config.reconnectWindowMs) drop(i, keepToken = true)
        }
    }

    /**
     * After the game changed ([mover] made the move): bots now (no delay) or a planned bot move, the turn clock, then
     * everyone's view.
     */
    private fun advance(mover: Int? = null) {
        if (config.botDelayMs == 0L) runBots()
        schedule(restartClockFor = mover)
        changed()
    }

    private fun botSeats() = seats.indices.filter { seats[it].kind == SeatKind.BOT }.toSet()

    /**
     * Plans what is due against the current state. Bots: one [NearbyGame.nextBotMove] answer, played after
     * [RoomConfig.botDelayMs] unless another move lands first (then this runs again and the plan is replaced). Clock:
     * the human whose turn it is keeps their deadline until the turn moves to another seat or they move themselves,
     * so an off-turn bot move (a catch) does not eat or reset their time.
     */
    private fun schedule(restartClockFor: Int? = null) {
        plan = null
        botAt = null
        val s = state
        if (s == null || phase != Phase.PLAYING || game.isOver(s)) {
            turnSeat = null
            turnDeadline = null
            return
        }
        val bots = botSeats()
        if (bots.isNotEmpty() && config.botDelayMs > 0) {
            plan = game.nextBotMove(s, bots, seats.size)?.takeIf { it.seat in bots }
            if (plan != null) botAt = clock() + config.botDelayMs
        }
        val human = game.currentSeat(s, seats.size)?.takeIf { it in seats.indices && seats[it].kind != SeatKind.BOT }
        when {
            human == null || config.turnTimeoutMs <= 0 -> {
                turnSeat = null
                turnDeadline = null
            }
            human != turnSeat || turnDeadline == null || restartClockFor == human -> {
                turnSeat = human
                turnDeadline = clock() + config.turnTimeoutMs
            }
        }
    }

    /**
     * Plays the one thing that is due, the earlier first (a tie goes to the turn clock): the bots' planned move, or the
     * bot move for a human whose time ran out. Either way the move counts as applied, so pending plans are replaced.
     */
    private fun fireDue() {
        val now = clock()
        val s = state ?: return
        if (phase != Phase.PLAYING) return
        val clockDue = turnDeadline?.takeIf { now >= it }
        val botDue = botAt?.takeIf { now >= it }
        val (seat, move) = when {
            clockDue != null && (botDue == null || clockDue <= botDue) -> turnSeat?.let { it to game.botMove(s, it) }
            botDue != null -> plan?.let { it.seat to it.move }
            else -> null
        } ?: return
        val next = move?.let { game.apply(s, seat, it).getOrNull() }
        if (next == null) { // the game offered nothing playable: stop rather than spin on it
            trace("room: due move for seat $seat was not playable; stopping the clock")
            plan = null
            botAt = null
            turnDeadline = null
            return
        }
        state = next
        advance(mover = seat)
    }

    /** No delay: bots play until none wants to, at most [MAX_BOT_STEPS] moves; hitting the cap stops, never throws. */
    private fun runBots() {
        repeat(MAX_BOT_STEPS) {
            val s = state ?: return
            if (game.isOver(s)) return
            val bots = botSeats()
            val next = game.nextBotMove(s, bots, seats.size)?.takeIf { it.seat in bots } ?: return
            state = game.apply(s, next.seat, next.move).getOrNull()
                ?: return trace("room: bot move for seat ${next.seat} was refused by the game; bots stop")
        }
        trace("room: bots made $MAX_BOT_STEPS moves in a row; stopped (a game bug or an endless bot loop)")
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
        val turn = turnDeadline?.let { TurnInfo(turnSeat!!, (it - clock()).coerceAtLeast(0)) }
        out += RoomOutput.Send(conn, HostMsg.View(seq, view, turn))
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
        const val TOO_LATE = "Too late, another player already did that."

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
internal fun secureToken(): String = secureRandomBytes(16).toHex()

internal expect fun secureRandomBytes(size: Int): ByteArray

internal const val SEED_BYTES = 8

/** The bytes as one big-endian Long (8 bytes fill it): a deal seed from [secureRandomBytes]. */
internal fun ByteArray.toSeed(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
