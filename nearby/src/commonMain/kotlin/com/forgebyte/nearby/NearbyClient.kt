package com.forgebyte.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
        install(WebSockets) {
            pingIntervalMillis = 5_000
            maxFrameSize = 256L * 1024
        }
    }
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var leaving = false
    private var lastSeq = 0

    private val _status = MutableStateFlow<ClientStatus>(ClientStatus.Connecting)
    public val status: StateFlow<ClientStatus> = _status.asStateFlow()

    private val _lobby = MutableStateFlow<Lobby?>(null)
    public val lobby: StateFlow<Lobby?> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<V?>(null)
    public val view: StateFlow<V?> = _view.asStateFlow()

    private val _rejections = MutableSharedFlow<String>(extraBufferCapacity = 8)
    public val rejections: SharedFlow<String> = _rejections.asSharedFlow()

    /** Joins with the room code from the QR or typed code: no host approval needed. */
    public fun join(code: JoinCode, name: String): Job = connect(code.host, code.port, code.secret, name)

    /** Asks a room found nearby to let us in; [status] is [ClientStatus.WaitingForHost] until the host answers. */
    public fun requestJoin(room: RoomAd, name: String): Job = connect(room.host, room.port, secret = null, name)

    /**
     * Connects and keeps the seat: after a drop it reconnects with the seat's token every second, until the host
     * answers or [RETRY_FOR] passes. The host decides whether the seat is still there.
     */
    private fun connect(host: String, port: Int, secret: String?, name: String): Job = scope.launch {
        val started = TimeSource.Monotonic.markNow()
        var token: String? = null
        var droppedAt: TimeSource.Monotonic.ValueTimeMark? = null
        while (true) {
            var end: EndReason? = null
            try {
                http.webSocket(host = host, port = port, path = WS_PATH) {
                    session = this
                    // A reconnect proves the seat with its token alone, so a seat that joined by request comes back too.
                    val hello = ClientMsg.Hello(PROTOCOL_VERSION, gameId, gameVersion, name, secret.takeIf { token == null }, token)
                    send(Frame.Text(NearbyProtocol.encode(hello)))
                    if (hello.roomSecret == null && token == null) _status.value = ClientStatus.WaitingForHost
                    for (frame in incoming) {
                        val msg = (frame as? Frame.Text)?.let { NearbyProtocol.decodeHost(it.readText()) } ?: continue
                        if (msg is HostMsg.Welcome) {
                            token = msg.reconnectToken
                            droppedAt = null
                        }
                        end = handle(msg)
                        if (end != null) break
                    }
                }
            } catch (_: Exception) {
                // Our own cancellation goes on up; a socket error or a cut session is a drop, handled below.
                currentCoroutineContext().ensureActive()
            } finally {
                session = null
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

    public suspend fun move(move: M): Boolean =
        send(ClientMsg.Move(NearbyProtocol.json.encodeToJsonElement(moveSerializer, move)))

    public suspend fun leave() {
        leaving = true
        send(ClientMsg.Leave)
        session?.run {
            flush()
            close()
        }
    }

    /** Test hook: cut the socket as if Wi-Fi dropped, without telling the host. */
    internal suspend fun dropForTest() {
        session?.cancel()
    }

    private suspend fun send(msg: ClientMsg): Boolean {
        val s = session ?: return false
        return try {
            s.send(Frame.Text(NearbyProtocol.encode(msg)))
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
                _view.value = NearbyProtocol.json.decodeFromJsonElement(viewSerializer, msg.view)
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
    }
}
