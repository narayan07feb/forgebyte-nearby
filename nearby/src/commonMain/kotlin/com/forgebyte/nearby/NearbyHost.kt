package com.forgebyte.nearby

import com.appstractive.dnssd.publishService
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The host phone: runs a [Room] behind a Ktor WebSocket server and plays seat 0 itself. Joiners connect with
 * [NearbyClient]. Every room call runs under one lock, so the room sees one event at a time.
 */
public class NearbyHost<S, M, V>(
    private val game: NearbyGame<S, M, V>,
    private val config: RoomConfig,
    private val scope: CoroutineScope,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val checkCodes = mutableMapOf<String, String>() // read by the room under the lock
    private val room = Room(game, config, clock = ::now, checkCodeOf = { checkCodes[it] ?: "" })
    private val lock = Mutex()
    private val outboxes = mutableMapOf<String, Channel<String>>()
    private var hostKey: HostKey? = null
    private var hostSeq = 0 // seq of the host's own latest view, sent with its moves
    internal val fingerprint: String? get() = hostKey?.fingerprint
    private val writers = mutableSetOf<Job>()
    private var nextConn = 0
    private val jobs = mutableListOf<Job>()
    private var stopServer: (suspend () -> Unit)? = null

    private val _lobby = MutableStateFlow(room.hostLobby)
    public val lobby: StateFlow<Lobby> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<V?>(null)
    public val view: StateFlow<V?> = _view.asStateFlow()

    private val _canStart = MutableStateFlow(false)
    public val canStart: StateFlow<Boolean> = _canStart.asStateFlow()

    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    /** People asking to join; answer each with [acceptJoin] or [declineJoin]. */
    public val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    private val _turnTimer = MutableStateFlow<TurnTimer?>(null)
    /** The running turn clock (any seat's), for the host's countdown; null while bots play or with no timer. */
    public val turnTimer: StateFlow<TurnTimer?> = _turnTimer.asStateFlow()

    private val _rejections = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Why the host's own move was refused. */
    public val rejections: SharedFlow<String> = _rejections.asSharedFlow()

    /** Starts the server on [port] (0 picks a free one) and returns the port it listens on. */
    public suspend fun open(port: Int = 0): Int {
        hostKey = HostKey.create() // new for this room, kept only in memory
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = PING.inWholeMilliseconds
                timeoutMillis = PING.inWholeMilliseconds * 3 // a phone that went quiet this long is treated as dropped
                maxFrameSize = MAX_FRAME
            }
            routing { webSocket(WS_PATH) { serve(this) } }
        }
        server.startSuspend(wait = false)
        stopServer = { server.stopSuspend(500, 1_000) }
        // Wake exactly when the room next has something due (a paced bot move, a turn deadline), at least every second.
        jobs += scope.launch {
            while (isActive) {
                val wait = lock.withLock { room.nextWakeAt?.minus(now()) } ?: 1_000
                delay(wait.coerceIn(5, 1_000))
                act { tick() }
            }
        }
        return server.engine.resolvedConnectors().first().port
    }

    /** What a joiner types or scans when discovery does not work on this network. Call after [open]. */
    public fun joinCodes(port: Int): List<JoinCode> {
        val fingerprint = checkNotNull(hostKey) { "open() first" }.fingerprint
        return localAddresses().map { JoinCode(it, port, config.roomSecret, fingerprint) }
    }

    /**
     * Advertises the room on the local network until the calling coroutine is cancelled, updating the free-seat
     * count as it changes. A joiner who taps it sends a join request; see [joinRequests].
     */
    public suspend fun advertise(port: Int) {
        ensureDiscoveryReady()
        lobby.map { if (it.phase == Phase.LOBBY) config.maxSeats - it.seats.size else 0 }.distinctUntilChanged()
            .collectLatest { free ->
                publishService(SERVICE_TYPE, config.roomName.take(MAX_ROOM_NAME)) {
                    this.port = port
                    txt = roomTxt(game.gameId, config, free)
                }
            }
    }

    public suspend fun acceptJoin(id: Int): Unit = act { acceptJoin(id) }
    public suspend fun declineJoin(id: Int): Unit = act { declineJoin(id) }
    public suspend fun addBot(): Unit = act { addBot() }
    public suspend fun kick(seat: Int): Unit = act { kick(seat) }
    public suspend fun startGame(seed: Long = Random.nextLong()): Unit = act { start(seed) }
    public suspend fun move(move: M): Unit = act { hostMove(move, seenSeq = hostSeq) }

    /** Ends the room for everyone, then stops the server. */
    public suspend fun leave() {
        act { hostLeave() }
        jobs.forEach { it.cancel() }
        // Let every socket send "room closed" before the server goes, or joiners would take it for a drop.
        val pending = lock.withLock { writers.toList() }
        withTimeoutOrNull(FLUSH) { pending.joinAll() }
        stopServer?.invoke()
    }

    private suspend fun serve(session: DefaultWebSocketServerSession) {
        val key = hostKey ?: return
        trace("host: socket open")
        // Key exchange first; nothing about the room is sent or read before the channel is encrypted.
        val secure = withTimeoutOrNull(HELLO_TIMEOUT) {
            hostHandshake(
                key,
                receive = { (session.incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() },
                send = { session.send(Frame.Text(it)) },
            )
        } ?: return trace("host: key exchange failed or timed out")
        trace("host: key exchange ok")
        val outbox = Channel<String>(OUTBOX)
        val conn = lock.withLock {
            "c${nextConn++}".also {
                outboxes[it] = outbox
                checkCodes[it] = secure.checkCode
            }
        }
        // The writer seals and sends what the room queued, then closes the socket once the room closed the outbox.
        val writer = session.launch {
            for (text in outbox) session.send(Frame.Binary(true, secure.seal(text)))
            session.flush() // else Ktor can drop the last frames (Refused, Kicked, RoomClosed) when the handler ends
            session.close()
        }
        lock.withLock { writers += writer }
        try {
            val first = withTimeoutOrNull(HELLO_TIMEOUT) { session.incoming.receiveCatching().getOrNull() }
            val hello = (first as? Frame.Binary)?.let { secure.open(it.data) }?.let(NearbyProtocol::decodeClient)
            if (hello !is ClientMsg.Hello) return
            act { onMessage(conn, hello) }
            for (frame in session.incoming) {
                // A frame that does not decrypt was changed, replayed or reordered: drop the connection.
                val text = (frame as? Frame.Binary)?.let { secure.open(it.data) } ?: return
                val msg = NearbyProtocol.decodeClient(text) ?: continue
                act { onMessage(conn, msg) }
            }
        } finally {
            withContext(NonCancellable) {
                act { onDisconnect(conn) }
                lock.withLock {
                    outboxes.remove(conn)
                    checkCodes.remove(conn)
                }
                outbox.close()
                writer.join()
                lock.withLock { writers -= writer }
            }
        }
    }

    private fun now() = started.elapsedNow().inWholeMilliseconds

    private suspend fun act(call: Room<S, M, V>.() -> List<RoomOutput>): Unit = lock.withLock {
        for (out in room.call()) when (out) {
            is RoomOutput.Send -> if (out.conn == HOST_CONN) {
                showOnHost(out.msg)
            } else {
                // A joiner that cannot keep up is dropped; it comes back through the reconnect window.
                outboxes[out.conn]?.let { if (it.trySend(NearbyProtocol.encode(out.msg)).isFailure) it.close() }
            }
            is RoomOutput.Close -> outboxes.remove(out.conn)?.close()
        }
        _canStart.value = room.canStart
        _joinRequests.value = room.joinRequests
    }

    private fun showOnHost(msg: HostMsg) {
        when (msg) {
            is HostMsg.LobbyUpdate -> _lobby.value = msg.lobby
            is HostMsg.View -> {
                hostSeq = msg.seq
                _view.value = NearbyProtocol.json.decodeFromJsonElement(game.viewSerializer, msg.view)
                _turnTimer.value = TurnTimer.from(msg.turn)
            }
            is HostMsg.MoveRejected -> _rejections.tryEmit(msg.message)
            else -> Unit
        }
    }

    private companion object {
        val PING = 5.seconds
        val HELLO_TIMEOUT = 10.seconds
        val FLUSH = 1.seconds
        const val MAX_FRAME = 64L * 1024
        const val OUTBOX = 64
    }
}
