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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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

/** A joiner's side of a room. Call [join] once; [status], [lobby] and [view] follow the room from then on. */
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
    private val sendLock = Mutex() // seal and send in one step, so frame counters reach the host in order
    @Volatile private var leaving = false
    @Volatile private var lastSeq = 0

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

    /** Joins with the room code from the QR or typed code: no host approval needed. */
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
            ?: return scope.launch { _status.value = ClientStatus.Ended(EndReason.Unreachable); http.close() }
        return connect(host, code.port, secret = null, fingerprint = null, name)
    }

    /** This device's site-local IPv4 addresses; tests point it at loopback. */
    internal var myAddresses: () -> List<String> = ::localAddresses

    /**
     * Connects and keeps the seat: after a drop it reconnects with the seat's token every second, until the host
     * answers or [RETRY_FOR] passes. The host decides whether the seat is still there.
     */
    private fun connect(host: String, port: Int, secret: String?, fingerprint: String?, name: String): Job = scope.launch {
        val started = TimeSource.Monotonic.markNow()
        val joinId = secureRandomBytes(16).toHex() // one per join, kept across retries until the Welcome
        var pinned = fingerprint // after the first join, every reconnect must reach the same room key
        var token: String? = null
        var droppedAt: TimeSource.Monotonic.ValueTimeMark? = null
        while (true) {
            var end: EndReason? = null
            try {
                trace("joiner: connecting to $host:$port")
                http.webSocket(host = host, port = port, path = WS_PATH) {
                    trace("joiner: socket open")
                    val shake = withTimeoutOrNull(HANDSHAKE_FOR) {
                        joinerHandshake(
                            pinned,
                            receive = { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() },
                            send = { send(Frame.Text(it)) },
                        )
                    } ?: Handshake.Failed
                    trace("joiner: key exchange $shake")
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
                    pinned = secure.hostFingerprint
                    _checkCode.value = secure.checkCode
                    link = Link(this, secure)
                    // A reconnect proves the seat with its token alone, so a seat that joined by request comes back too.
                    val hello = ClientMsg.Hello(
                        PROTOCOL_VERSION, gameId, gameVersion, name, secret.takeIf { token == null }, token,
                        joinId = joinId.takeIf { token == null },
                    )
                    send(hello)
                    if (hello.roomSecret == null && token == null) _status.value = ClientStatus.WaitingForHost
                    for (frame in incoming) {
                        // A frame that does not decrypt was changed, replayed or reordered: treat it as a drop.
                        val text = (frame as? Frame.Binary)?.let { secure.open(it.data) } ?: break
                        val msg = NearbyProtocol.decodeHost(text) ?: continue
                        if (msg is HostMsg.Welcome) {
                            trace("joiner: welcome, seat ${msg.lobby.yourSeat}")
                            token = msg.reconnectToken
                            droppedAt = null
                        }
                        end = handle(msg)
                        if (end != null) break
                    }
                }
            } catch (e: Exception) {
                // Our own cancellation goes on up; a socket error or a cut session is a drop, handled below.
                currentCoroutineContext().ensureActive()
                trace("joiner: attempt failed ${e::class.simpleName}: ${e.message}")
            } finally {
                link = null
            }
            if (leaving) end = EndReason.Left
            if (end == null) {
                val giveUp = if (token == null) {
                    started.elapsedNow() > firstConnectFor
                } else {
                    (droppedAt ?: TimeSource.Monotonic.markNow().also { droppedAt = it }).elapsedNow() > RETRY_FOR
                }
                if (giveUp) end = EndReason.Unreachable
            }
            end?.let {
                trace("joiner: ended $it")
                _status.value = ClientStatus.Ended(it)
                http.close()
                return@launch
            }
            _status.value = if (token == null) ClientStatus.Connecting else ClientStatus.Reconnecting
            delay(1_000)
        }
    }

    /**
     * Sending returns false while the connection is down (the message is dropped, never thrown). After the
     * reconnect the host sends the current view again, so the player can simply try again.
     */
    public suspend fun ready(ready: Boolean): Boolean = send(ClientMsg.Ready(ready))

    /**
     * [seenSeq] is the seq of the view the player picked the move on ([SeqView.seq]). The default, the newest view
     * received, is right only when nothing can arrive between drawing and the tap.
     */
    public suspend fun move(move: M, seenSeq: Int = lastSeq): Boolean =
        send(ClientMsg.Move(NearbyProtocol.json.encodeToJsonElement(moveSerializer, move), seenSeq))

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
        val CONNECT_FOR = 5.seconds
        val HANDSHAKE_FOR = 5.seconds
        const val VERSION_MESSAGE = "This room uses a different version of nearby play. Update the app on both phones."
    }
}
