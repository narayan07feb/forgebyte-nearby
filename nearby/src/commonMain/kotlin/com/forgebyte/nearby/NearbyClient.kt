package com.forgebyte.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

public sealed interface ClientStatus {
    public data object Connecting : ClientStatus
    /** A join request was sent; the host has not answered yet. */
    public data object WaitingForHost : ClientStatus
    public data object Connected : ClientStatus
    /** The connection dropped; trying to get the same seat back. */
    public data object Reconnecting : ClientStatus
    public data class Ended(val reason: EndReason) : ClientStatus
}

public sealed interface EndReason {
    public data class Refused(val reason: RefuseReason, val message: String) : EndReason
    public data object Kicked : EndReason
    public data object HostLeft : EndReason
    public data object Left : EndReason
    /** Could not reach the host (first connect), or could not get back within the retry time. */
    public data object Unreachable : EndReason
    /**
     * The room's encryption key is not the one in the join code (or, on a reconnect, not the one we first joined):
     * a wrong or old code, or someone on the network pretending to be the room.
     */
    public data object HostKeyMismatch : EndReason
}

/** All clients in the app write their saved seat one at a time (see [NearbyClient]). */
private val seatLock = Mutex()

/**
 * A joiner's side of a room. Call [join] once; [status], [lobby] and [view] follow the room from then on.
 *
 * To end the client without leaving the seat (the app's screen or process goes away), cancel the Job that [join],
 * [requestJoin] or [resume] returned: nothing is sent to the host, which keeps the seat for its reconnect window, and
 * the saved seat stays in the [SeatStore] for a later [resume]. To give the seat up, call [leave].
 *
 * The store is shared: this client saves its seat on each Welcome, and later writes or clears it only while the store
 * still holds the seat this client saved, so an older client cannot erase or change a newer client's seat. The library
 * makes these checks and writes one at a time for all clients in the app; an app that writes the same store itself must
 * not do so while a client runs. A Welcome always saves: the newest Welcome wins.
 */
public class NearbyClient<M, V>(
    private val gameId: String,
    private val gameVersion: Int,
    private val moveSerializer: KSerializer<M>,
    private val viewSerializer: KSerializer<V>,
    private val scope: CoroutineScope,
    /**
     * How long the first connect keeps trying. A host phone whose Wi-Fi is dozing can miss the first attempts
     * (seen on the Lava, 2026-11-03: "Host is down" until the phone sent traffic).
     */
    private val firstConnectFor: Duration = 10.seconds,
    /** Where the seat is kept for [resume] after the app is killed; see [SeatStore]. Default: memory only. */
    private val seatStore: SeatStore = InMemorySeatStore(),
) {
    private val http = HttpClient(CIO) {
        // One slow attempt must not use up the whole first-connect time: each try gets its own limit.
        engine { endpoint { connectTimeout = CONNECT_FOR.inWholeMilliseconds } }
        install(WebSockets) {
            pingIntervalMillis = 5_000
            maxFrameSize = 256L * 1024
        }
    }
    /** The open connection and its encryption; null while disconnected. */
    private class Link(val socket: DefaultClientWebSocketSession, val secure: SecureSession)

    @Volatile private var link: Link? = null
    @Volatile private var pausedSince: TimeSource.Monotonic.ValueTimeMark? = null
    @Volatile private var pausedBefore: Duration = Duration.ZERO

    /** The connect attempt now running, so a silent-link watchdog can end it; see [bounded]. */
    @Volatile private var attemptJob: Job? = null
    private val sendLock = Mutex() // seal and send in one step, so frame counters reach the host in order
    @Volatile private var leaving = false
    /** Completes when the HTTP client is closed; tests check teardown with it. */
    internal val httpClosed: Job get() = http.coroutineContext[Job]!!

    /** The token of the seat this client last saved; the store is changed for this client only while it holds it. */
    @Volatile private var savedToken: String? = null
    @Volatile private var lastSeq = 0

    /** This connection has its Welcome. The host takes any later message as proof of that, so none goes out before. */
    @Volatile private var welcomed = false

    private val _status = MutableStateFlow<ClientStatus>(ClientStatus.Connecting)
    public val status: StateFlow<ClientStatus> = _status.asStateFlow()

    private val _lobby = MutableStateFlow<Lobby?>(null)
    public val lobby: StateFlow<Lobby?> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<V?>(null)
    public val view: StateFlow<V?> = _view.asStateFlow()

    private val _seqView = MutableStateFlow<SeqView<V>?>(null)
    /** [view] with its seq, as one value: draw from this and pass its seq to [move]. */
    public val seqView: StateFlow<SeqView<V>?> = _seqView.asStateFlow()

    private val _turnTimer = MutableStateFlow<TurnTimer?>(null)
    /** The running turn clock (whose turn, time left) for a countdown; null while bots play or with no timer. */
    public val turnTimer: StateFlow<TurnTimer?> = _turnTimer.asStateFlow()

    private val _checkCode = MutableStateFlow<String?>(null)
    /** Show this while waiting for the host: the host sees the same six digits next to the request. */
    public val checkCode: StateFlow<String?> = _checkCode.asStateFlow()

    private val _rejections = MutableSharedFlow<String>(extraBufferCapacity = 8)
    public val rejections: SharedFlow<String> = _rejections.asSharedFlow()

    /**
     * Joins with the full room code from the QR or typed code. The host does not approve this join and no check code
     * is needed: whoever has the full code gets a seat until the game starts (see [JoinCode]). A room with
     * [RoomConfig.approveCodeJoins] is the exception: there [status] is [ClientStatus.WaitingForHost] until the host
     * answers, so show [checkCode] as for [requestJoin].
     */
    public fun join(code: JoinCode, name: String): Job = connect(code.host, code.port, code.secret, code.fingerprint, name)

    /** Asks a room found nearby to let us in; [status] is [ClientStatus.WaitingForHost] until the host answers. */
    public fun requestJoin(room: RoomAd, name: String): Job = connect(room.host, room.port, secret = null, fingerprint = null, name)

    /**
     * Asks the room behind a typed [ShortCode] to let us in, exactly like [requestJoin] for a room found nearby: the
     * host accepts or declines, and both sides show [checkCode]. The host is looked for on this device's Wi-Fi network
     * (same first three address bytes). Ends [EndReason.Unreachable] when this device has no Wi-Fi address or the room
     * is not on its network.
     */
    public fun requestJoin(code: ShortCode, name: String): Job {
        val host = myAddresses().firstNotNullOfOrNull(code::hostOn)
            ?: return scope.launch { _status.value = ClientStatus.Ended(EndReason.Unreachable) }
                .also { job -> job.invokeOnCompletion { http.close() } }
        return connect(host, code.port, secret = null, fingerprint = null, name)
    }

    /**
     * Takes back the seat this app had before it was killed ([SeatStore.load]). The room's key must match
     * [SavedSeat.fingerprint] before the token is sent, so another host now on the same address never gets it
     * ([EndReason.HostKeyMismatch]). The host gives the seat back, also after a bot took it over, unless the player
     * left or was kicked; it then refuses, and the saved seat is cleared.
     */
    public fun resume(seat: SavedSeat): Job {
        require(seat.gameId == gameId) { "the saved seat is for ${seat.gameId}, not $gameId" }
        // A seat kept after a drop expires 10 min after that drop (ceo, 16 Nov 2026): it is not tried at all, so its
        // token never goes out and a dead room costs no retry time.
        val droppedAt = seat.droppedAtMs
        if (droppedAt != null && wallClock() - droppedAt > keptSeatFor.inWholeMilliseconds) {
            trace("joiner: kept seat dropped ${wallClock() - droppedAt} ms ago; not resumed")
            http.close()
            // Under the store lock, and only this exact record: a newer save of the same seat is not expired. It runs at
            // once when the lock is free, so Ended is set before resume() returns in the usual case.
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                seatLock.withLock { if (isActive && seatStore.load() == seat) seatStore.clear() } // cancelled: keep it
                _status.value = ClientStatus.Ended(EndReason.Unreachable)
            }
        }
        return connect(seat.host, seat.port, secret = null, fingerprint = seat.fingerprint, seat.name, resumeToken = seat.token)
    }

    /** Wall clock in epoch ms, for the age of a kept seat; tests set it. */
    @OptIn(ExperimentalTime::class)
    internal var wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** How long a seat kept after a drop may still be resumed (ceo, 16 Nov 2026: 10 min after the last drop). */
    internal var keptSeatFor: Duration = KEPT_SEAT_FOR

    /** How long a dropped seat keeps reconnecting before it ends [EndReason.Unreachable]; tests shorten it. */
    internal var retryFor: Duration = RETRY_FOR

    /** This device's site-local IPv4 addresses; tests point it at loopback. */
    internal var myAddresses: () -> List<String> = ::localAddresses

    /**
     * Connects and keeps the seat: after a drop it reconnects with the seat's token every second, until the host
     * answers or [RETRY_FOR] passes. The host decides whether the seat is still there.
     */
    private fun connect(
        host: String, port: Int, secret: String?, fingerprint: String?, name: String, resumeToken: String? = null,
    ): Job = scope.launch {
        savedToken = resumeToken // a resumed seat is this client's own
        val started = TimeSource.Monotonic.markNow()
        val joinId = secureRandomBytes(16).toHex() // one per join, kept across retries until the Welcome
        var pinned = fingerprint // after the first join, every reconnect must reach the same room key
        var token: String? = resumeToken
        // A resume is a reconnect from the start: its retry time runs from now, so a dead host cannot hold it.
        var droppedAt: TimeSource.Monotonic.ValueTimeMark? = if (resumeToken != null) started else null
        var pausedAtDrop = pausedTotal() // time paused before this drop; only later pauses stop the retry clock
        var saved: SavedSeat? = null // the seat as last welcomed, so a drop can record when it happened
        // How long the retry clock has run since the drop, without the time the app was paused.
        fun retried(at: TimeSource.Monotonic.ValueTimeMark) = at.elapsedNow() - (pausedTotal() - pausedAtDrop)
        while (true) {
            var end: EndReason? = null
            // The time this attempt may take to get through the key exchange; null while there is no limit yet.
            // A reconnect attempt also gets its own limit (connect + key exchange): one attempt that hangs on a port that
            // accepts but never answers must not use up the whole retry time (16 Nov device run and test).
            val left = if (token == null) firstConnectFor - started.elapsedNow()
                else droppedAt?.let {
                    val each = minOf(retryFor - retried(it), CONNECT_FOR + HANDSHAKE_FOR)
                    if (pausedSince != null) each.coerceAtLeast(CONNECT_FOR) else each // paused: keep trying, never give up
                }
            var shook = false
            try {
                trace("joiner: connecting to $host:$port")
                bounded(left, done = { shook }) { http.webSocket(host = host, port = port, path = WS_PATH) {
                    trace("joiner: socket open")
                    val shake = withTimeoutOrNull(HANDSHAKE_FOR) {
                        joinerHandshake(
                            pinned,
                            receive = { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() },
                            send = { send(Frame.Text(it)) },
                        )
                    } ?: Handshake.Failed
                    trace("joiner: key exchange ${shake::class.simpleName}")
                    val secure = when (shake) {
                        is Handshake.Ok -> shake.session
                        Handshake.VersionMismatch -> {
                            end = EndReason.Refused(RefuseReason.PROTOCOL_MISMATCH, VERSION_MESSAGE)
                            return@webSocket
                        }
                        Handshake.WrongHostKey -> {
                            end = EndReason.HostKeyMismatch
                            return@webSocket
                        }
                        Handshake.Failed -> return@webSocket // treated as a drop below
                    }
                    shook = true
                    pinned = secure.hostFingerprint
                    _checkCode.value = secure.checkCode
                    link = Link(this, secure)
                    // A link that goes silent with no FIN: Ktor's pinger starts the close after its pong timeout, but the
                    // close never gets an answer, so 'incoming' would wait until the link comes back. End the attempt soon
                    // after the close starts; the grace lets a normal close deliver the host's last messages first.
                    val session = this
                    val watchdog = launch {
                        val reason = session.closeReason.await()
                        delay(CLOSE_GRACE)
                        trace("joiner: session closing ($reason) but still open; ending this attempt")
                        // Neither session.cancel() nor ending the read loop is enough: webSocket {} then waits for the
                        // close to finish, which a silent link never does (16 Nov test). Cancel the whole attempt.
                        attemptJob?.cancel()
                    }
                    // A reconnect proves the seat with its token alone, so a seat that joined by request comes back too.
                    val hello = ClientMsg.Hello(
                        PROTOCOL_VERSION, gameId, gameVersion, name, secret.takeIf { token == null }, token,
                        joinId = joinId.takeIf { token == null },
                    )
                    send(hello)
                    // A code join waits only when the host says so (HostMsg.Waiting).
                    if (hello.roomSecret == null && token == null) _status.value = ClientStatus.WaitingForHost
                    for (frame in incoming) {
                        // A frame that does not decrypt was changed, replayed or reordered: treat it as a drop.
                        val text = (frame as? Frame.Binary)?.let { secure.open(it.data) } ?: break
                        val msg = NearbyProtocol.decodeHost(text) ?: continue
                        if (msg is HostMsg.Welcome) {
                            trace("joiner: welcome, seat ${msg.lobby.yourSeat}")
                            val seat = SavedSeat(host, port, secure.hostFingerprint, gameId, name, msg.reconnectToken)
                            seatLock.withLock {
                                currentCoroutineContext().ensureActive() // a cancelled client saves nothing
                                seatStore.save(seat)
                                savedToken = msg.reconnectToken
                            }
                            // Only after the save: if it throws, the next retry still uses the token that is on disk.
                            token = msg.reconnectToken
                            droppedAt = null
                            saved = seat
                            welcomed = true // only now: a move or ready makes the host drop the old token
                        }
                        end = handle(msg)
                        if (end != null) break
                    }
                    watchdog.cancel()
                } }
            } catch (e: Exception) {
                // Our own cancellation goes on up; a socket error or a cut session is a drop, handled below.
                currentCoroutineContext().ensureActive()
                trace("joiner: attempt failed ${e::class.simpleName}: ${e.message}")
            } finally {
                link = null
                welcomed = false
            }
            if (leaving) end = EndReason.Left
            if (end == null) {
                val giveUp = if (token == null) {
                    started.elapsedNow() > firstConnectFor
                } else {
                    val at = droppedAt ?: TimeSource.Monotonic.markNow().also {
                        droppedAt = it
                        pausedAtDrop = pausedTotal()
                        saved?.let { seat -> saveIfMine(seat.copy(droppedAtMs = wallClock())) } // the kept seat's age
                    }
                    pausedSince == null && retried(at) > retryFor
                }
                if (giveUp) end = EndReason.Unreachable
            }
            end?.let {
                trace("joiner: ended $it")
                // The seat is gone for good after a refusal, a kick or a leave. After Unreachable the host may still hold it
                // (a bot plays it), so the token stays for a later resume (ceo, 16 Nov 2026). A join without a seat keeps it.
                if (token != null && it != EndReason.Unreachable) clearIfMine()
                _status.value = ClientStatus.Ended(it)
                http.close()
                return@launch
            }
            _status.value = if (token == null) ClientStatus.Connecting else ClientStatus.Reconnecting
            delay(1_000)
        }
    }.also { job -> job.invokeOnCompletion { http.close() } } // also when the app cancels it (see the class KDoc)

    /** Writes [seat] only while the store still holds the seat this client saved (see the class KDoc). */
    private suspend fun saveIfMine(seat: SavedSeat) = seatLock.withLock {
        currentCoroutineContext().ensureActive()
        if (savedToken != null && seatStore.load()?.token == savedToken) seatStore.save(seat)
    }

    private suspend fun clearIfMine() = seatLock.withLock {
        currentCoroutineContext().ensureActive() // a cancelled client keeps its seat (see the class KDoc)
        if (savedToken != null && seatStore.load()?.token == savedToken) seatStore.clear()
    }

    /**
     * Runs one connect attempt, and cancels it when [left] runs out before [done] (the key exchange is through). A port
     * that takes the TCP connect and then never answers the upgrade would otherwise hold the joiner on Connecting or
     * Reconnecting for good (night run 8 Nov, host_kill: still Reconnecting 33 s after [RETRY_FOR]). After the key
     * exchange the WebSocket pings watch the link, so a long match is never cut.
     */
    private suspend fun bounded(left: Duration?, done: () -> Boolean, attempt: suspend () -> Unit) = coroutineScope {
        val job = launch { attempt() }
        attemptJob = job
        if (left != null) {
            val watchdog = launch {
                delay(left)
                if (!done()) {
                    trace("joiner: attempt out of time")
                    job.cancel()
                }
            }
            job.invokeOnCompletion { watchdog.cancel() }
        }
    }

    /**
     * The joiner's app went to the background or its screen turned off: the reconnect retry time stops counting, so the
     * joiner does not give up while the OS may be holding its network (ceo, 16 Nov 2026). It keeps trying meanwhile.
     */
    public fun pause() {
        if (pausedSince == null) pausedSince = TimeSource.Monotonic.markNow()
    }

    /** The joiner's app is back in front: the retry time counts again from where it stopped. Not [resume] with a seat. */
    public fun resume() {
        pausedSince?.let { pausedBefore += it.elapsedNow() }
        pausedSince = null
    }

    /** All the time spent paused so far, including a pause that is still on. */
    private fun pausedTotal(): Duration = pausedBefore + (pausedSince?.elapsedNow() ?: Duration.ZERO)

    /**
     * Sending returns false while the connection is down or not yet welcomed (the message is dropped, never thrown).
     * After the reconnect the host sends the current view again, so the player can simply try again.
     */
    public suspend fun ready(ready: Boolean): Boolean = send(ClientMsg.Ready(ready))

    /**
     * [seenSeq] is the seq of the view the player picked the move on ([SeqView.seq]). The default, the newest view
     * received, is right only when nothing can arrive between drawing and the tap.
     */
    public suspend fun move(move: M, seenSeq: Int = lastSeq): Boolean =
        send(ClientMsg.Move(NearbyProtocol.json.encodeToJsonElement(moveSerializer, move), seenSeq))

    /**
     * Gives the seat up: tells the host and closes the link; the client then ends [EndReason.Left] and clears its saved
     * seat. Known limit: while the link is down (Reconnecting), the host cannot be told, and a reconnect already under
     * way can still take the seat back. To leave for sure while offline, call this and then cancel the Job that [join],
     * [requestJoin] or [resume] returned; the host frees the seat after its reconnect window, and the app clears its
     * saved seat itself.
     */
    public suspend fun leave() {
        leaving = true
        send(ClientMsg.Leave)
        link?.socket?.run {
            flush()
            close()
        }
    }

    /** Test hook: cut the socket as if Wi-Fi dropped, without telling the host. */
    internal suspend fun dropForTest() {
        link?.socket?.cancel()
    }

    private suspend fun send(msg: ClientMsg): Boolean = sendLock.withLock {
        val l = link ?: return false
        if (!welcomed && (msg is ClientMsg.Ready || msg is ClientMsg.Move)) return false
        try {
            l.socket.send(Frame.Binary(true, l.secure.seal(NearbyProtocol.encode(msg))))
            true
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private fun handle(msg: HostMsg): EndReason? {
        when (msg) {
            is HostMsg.Welcome -> {
                _lobby.value = msg.lobby
                _status.value = ClientStatus.Connected
            }
            HostMsg.Waiting -> _status.value = ClientStatus.WaitingForHost
            is HostMsg.LobbyUpdate -> _lobby.value = msg.lobby
            is HostMsg.View -> if (msg.seq > lastSeq) {
                lastSeq = msg.seq
                val v = NearbyProtocol.json.decodeFromJsonElement(viewSerializer, msg.view)
                _seqView.value = SeqView(msg.seq, v)
                _view.value = v
                _turnTimer.value = TurnTimer.from(msg.turn)
            }
            is HostMsg.MoveRejected -> _rejections.tryEmit(msg.message)
            is HostMsg.Refused -> return EndReason.Refused(msg.reason, msg.message)
            HostMsg.Kicked -> return EndReason.Kicked
            is HostMsg.RoomClosed -> return EndReason.HostLeft
        }
        return null
    }

    private companion object {
        val RETRY_FOR = 120.seconds
        val KEPT_SEAT_FOR = 10.minutes
        val CONNECT_FOR = 5.seconds
        val HANDSHAKE_FOR = 5.seconds
        /** How long a session that has started to close may stay open before the attempt ends (silent link). */
        val CLOSE_GRACE = 2.seconds
        const val VERSION_MESSAGE = "This room uses a different version of nearby play. Update the app on both phones."
    }
}
