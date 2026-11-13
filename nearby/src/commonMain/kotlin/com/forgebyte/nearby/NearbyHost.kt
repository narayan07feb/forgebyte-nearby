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
import kotlin.concurrent.Volatile
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
    @Volatile private var hostSeq = 0 // seq of the host's own latest view, the default for move()
    internal val fingerprint: String? get() = hostKey?.fingerprint
    private val writers = mutableSetOf<Job>()
    private var nextConn = 0
    private val jobs = mutableListOf<Job>()
    private var stopServer: (suspend () -> Unit)? = null
    private val wake = Channel<Unit>(Channel.CONFLATED) // tells the ticker to read the room's next wake time again
    internal var randomBytes: (Int) -> ByteArray = ::secureRandomBytes // replaced only by tests

    private val _lobby = MutableStateFlow(room.hostLobby)
    public val lobby: StateFlow<Lobby> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<V?>(null)
    public val view: StateFlow<V?> = _view.asStateFlow()

    private val _seqView = MutableStateFlow<SeqView<V>?>(null)
    /** [view] with its seq, as one value: draw from this and pass its seq to [move]. */
    public val seqView: StateFlow<SeqView<V>?> = _seqView.asStateFlow()

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

    /**
     * Starts the server on [port] (0 picks a free one) and returns the port it listens on. [bindHost] is the address
     * to listen on: all interfaces by default (joiners on the Wi-Fi); tests use "127.0.0.1" so a port held on loopback
     * by another program on a Mac cannot answer instead of us (seen 2026-11-03: a JetBrains process answered 404).
     */
    public suspend fun open(port: Int = 0, bindHost: String = "0.0.0.0"): Int {
        hostKey = HostKey.create() // new for this room, kept only in memory
        val server = embeddedServer(CIO, port = port, host = bindHost) {
            install(WebSockets) {
                pingPeriodMillis = PING.inWholeMilliseconds
                timeoutMillis = PING.inWholeMilliseconds * 3 // a phone that went quiet this long is treated as dropped
                maxFrameSize = MAX_FRAME
            }
            routing { webSocket(WS_PATH) { serve(this) } }
        }
        server.startSuspend(wait = false)
        stopServer = { server.stopSuspend(500, 1_000) }
        // Wake exactly when the room next has something due (a paced bot move, a turn deadline), at least every second,
        // and early when a room call moved that time sooner (a move with botDelayMs under a second).
        jobs += scope.launch {
            while (isActive) {
                val wait = lock.withLock { room.nextWakeAt?.minus(now()) } ?: 1_000
                withTimeoutOrNull(wait.coerceIn(5, 1_000)) { wake.receive() }
                act { tick() }
                wake.tryReceive() // this tick's own signal; the next wait is read fresh under the lock
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
     * Six-character codes ([ShortCode]) a joiner types to find this room on the same Wi-Fi, one per local address.
     * A short-code join is a join request: it waits in [joinRequests] for [acceptJoin], like a room found nearby.
     */
    public fun shortCodes(port: Int): List<ShortCode> = localAddresses().mapNotNull { ShortCode.of(it, port) }

    /**
     * Advertises the room on the local network until the calling coroutine is cancelled, updating the free-seat
     * count as it changes. A joiner who taps it sends a join request; see [joinRequests].
     */
    public suspend fun advertise(port: Int) {
        ensureDiscoveryReady()
        lobby.map { if (it.phase == Phase.LOBBY) config.maxSeats - it.seats.size else 0 }.distinctUntilChanged()
            .collectLatest { free ->
                publishService(SERVICE_TYPE, serviceName(config)) {
                    this.port = port
                    txt = roomTxt(game.gameId, config, free, localAddresses())
                }
            }
    }

    public suspend fun acceptJoin(id: Int): Unit = act { acceptJoin(id) }
    public suspend fun declineJoin(id: Int): Unit = act { declineJoin(id) }
    public suspend fun addBot(): Unit = act { addBot() }
    public suspend fun kick(seat: Int): Unit = act { kick(seat) }
    /**
     * Deals and starts the match. [seed] shuffles the deal, so it must stay secret: anyone who can guess it can
     * rebuild every hand. The default takes 64 bits from the platform's secure random source. Pass a seed only for
     * tests and replays, or make it with a secure source (`java.security.SecureRandom`), never `kotlin.random.Random`.
     */
    public suspend fun startGame(seed: Long = randomBytes(SEED_BYTES).toSeed()): Unit = act { start(seed) }
    /** [seenSeq]: the seq of the view the move was picked on ([SeqView.seq]); see [NearbyClient.move]. */
    public suspend fun move(move: M, seenSeq: Int = hostSeq): Unit = act { hostMove(move, seenSeq) }

    /** Ends the room for everyone, then stops the server. */
    public suspend fun leave() {
        val t0 = TimeSource.Monotonic.markNow()
        act { hostLeave() }
        jobs.forEach { it.cancel() }
        trace("host: leave: room closed sent (${t0.elapsedNow()})")
        // Let every socket send "room closed" before the server goes, or joiners would take it for a drop.
        val pending = lock.withLock { writers.toList() }
        val flushed = withTimeoutOrNull(FLUSH) { pending.joinAll() } != null
        trace("host: leave: ${pending.size} writers ${if (flushed) "flushed" else "NOT flushed"} (${t0.elapsedNow()})")
        stopServer?.invoke()
        trace("host: leave: server stopped (${t0.elapsedNow()})")
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
            trace("host: $conn hello (token ${hello.reconnectToken != null}, code ${hello.roomSecret != null})")
            act { onMessage(conn, hello) }
            for (frame in session.incoming) {
                // A frame that does not decrypt was changed, replayed or reordered: drop the connection.
                val text = (frame as? Frame.Binary)?.let { secure.open(it.data) } ?: return
                val msg = NearbyProtocol.decodeClient(text) ?: continue
                act { onMessage(conn, msg) }
            }
        } finally {
            withContext(NonCancellable) {
                trace("host: $conn closed")
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
        wake.trySend(Unit)
    }

    private fun showOnHost(msg: HostMsg) {
        when (msg) {
            is HostMsg.LobbyUpdate -> _lobby.value = msg.lobby
            is HostMsg.View -> {
                hostSeq = msg.seq
                val v = NearbyProtocol.json.decodeFromJsonElement(game.viewSerializer, msg.view)
                _seqView.value = SeqView(msg.seq, v)
                _view.value = v
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
