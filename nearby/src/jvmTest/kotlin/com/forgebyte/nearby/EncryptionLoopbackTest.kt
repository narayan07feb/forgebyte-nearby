package com.forgebyte.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Owner, 2026-11-03: every frame after the key exchange is end-to-end encrypted between joiner and host. */
class EncryptionLoopbackTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(), scope)

    @AfterTest
    fun tearDown() = runBlocking {
        host.leave()
        scope.cancel()
    }

    /**
     * A TCP relay in front of the host that keeps a copy of every byte, both ways, like a sniffer on the Wi-Fi.
     * Joiner-to-host WebSocket frames are masked (RFC 6455), which hides nothing from a sniffer since the mask key
     * is in the frame, so [captured] unmasks them like a sniffer would.
     */
    private class Tap(target: Int) {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        private val fromHost = ByteArrayOutputStream()
        private val fromJoiners = mutableListOf<ByteArrayOutputStream>()
        val captured: String
            get() = synchronized(this) {
                (listOf(fromHost.toByteArray()) + fromJoiners.map { unmask(it.toByteArray()) })
                    .joinToString("\n") { it.toString(Charsets.ISO_8859_1) }
            }

        init {
            thread(isDaemon = true) {
                while (true) {
                    val a = runCatching { server.accept() }.getOrNull() ?: break
                    val b = Socket("127.0.0.1", target)
                    val joinerStream = ByteArrayOutputStream().also { synchronized(this) { fromJoiners += it } }
                    pump(a, b, joinerStream)
                    pump(b, a, fromHost)
                }
            }
        }

        private fun pump(from: Socket, to: Socket, seen: ByteArrayOutputStream) = thread(isDaemon = true) {
            val buf = ByteArray(4096)
            runCatching {
                while (true) {
                    val n = from.getInputStream().read(buf)
                    if (n < 0) break
                    synchronized(this) { seen.write(buf, 0, n) }
                    to.getOutputStream().write(buf, 0, n)
                }
            }
            runCatching { to.shutdownOutput() }
        }

        /** The HTTP upgrade request as is, then each frame's payload with its mask removed. */
        private fun unmask(raw: ByteArray): ByteArray {
            val text = raw.toString(Charsets.ISO_8859_1)
            var i = text.indexOf("\r\n\r\n").takeIf { it >= 0 }?.plus(4) ?: return raw
            val out = ByteArrayOutputStream().apply { write(raw, 0, i) }
            fun u(k: Int) = raw[k].toInt() and 0xff
            while (i + 2 <= raw.size) {
                var len = (u(i + 1) and 0x7f).toLong()
                val masked = u(i + 1) and 0x80 != 0
                var p = i + 2
                if (len == 126L) { len = ((u(p) shl 8) or u(p + 1)).toLong(); p += 2 }
                else if (len == 127L) { len = (0 until 8).fold(0L) { acc, k -> (acc shl 8) or u(p + k).toLong() }; p += 8 }
                val mask = if (masked) raw.copyOfRange(p, p + 4).also { p += 4 } else null
                if (p + len > raw.size) break
                for (k in 0 until len.toInt()) out.write(raw[p + k].toInt() xor (mask?.get(k % 4)?.toInt() ?: 0))
                out.write('\n'.code)
                i = p + len.toInt()
            }
            return out.toByteArray()
        }
    }

    private fun client() = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)

    @Test
    fun givenFullMatchThroughASniffer_whenBytesRead_thenNoCardNameTokenOrCodeInTheClear() = runBlocking<Unit> {
        withTimeout(60.seconds) {
            val tap = Tap(host.open())
            val names = listOf("AshaVerma", "RaviKumar")
            val clients = names.map { name ->
                client().also {
                    it.join(JoinCode("127.0.0.1", tap.port, Harness.SECRET, host.fingerprint!!), name)
                    assertEquals(ClientStatus.Connected, it.status.first { s -> s == ClientStatus.Connected || s is ClientStatus.Ended })
                }
            }
            clients.forEach { it.ready(true) }
            host.canStart.first { it }
            clients.forEach { c ->
                scope.launch {
                    combine(c.view.filterNotNull(), c.status) { v, s -> v to s }.collect { (v, s) ->
                        if (s == ClientStatus.Connected && v.turn == c.lobby.value!!.yourSeat && v.hand.isNotEmpty()) c.move(FakeMove(v.hand.first()))
                    }
                }
            }
            scope.launch {
                host.view.filterNotNull().collect { v -> if (v.turn == 0 && v.hand.isNotEmpty()) host.move(FakeMove(v.hand.first())) }
            }
            host.startGame(seed = 5)
            host.lobby.first { it.phase == Phase.OVER }

            val wire = tap.captured
            assertTrue("\"commit\"" in wire && "\"hostKey\"" in wire, "the capture really saw both directions")
            // Plaintext JSON always quotes a card ("C6Z"); a bare 3-letter match turns up in random ciphertext about
            // once in 50 runs, the quoted 5-byte form about once in 3 million.
            val cards = (0 until 3 * 6).map { "\"C${it}Z\"" }
            val leaks = cards.filter { it in wire } + names.filter { it in wire } +
                listOfNotNull(Harness.SECRET.takeIf { it in wire }, "Host".takeIf { "\"Host\"" in wire }) +
                Regex("[0-9a-f]{32}").findAll(wire).map { "token-like ${it.value}" }.toList() +
                listOf("\"view\"", "\"hello\"", "\"welcome\"", "reconnectToken").filter { it in wire }
            assertEquals(emptyList(), leaks)
        }
    }

    /** A hand-rolled joiner that speaks the real key exchange, so a test can misbehave after it. */
    private suspend fun rawJoiner(port: Int, block: suspend (secure: SecureSession, send: suspend (ByteArray) -> Unit, receive: suspend () -> Frame?) -> Unit) {
        HttpClient(CIO) { install(WebSockets) }.use { http ->
            http.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                val shake = joinerHandshake(
                    host.fingerprint,
                    receive = { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() },
                    send = { send(Frame.Text(it)) },
                )
                val secure = assertIs<Handshake.Ok>(shake).session
                block(secure, { send(Frame.Binary(true, it)) }, { incoming.receiveCatching().getOrNull() })
            }
        }
    }

    /** True only when the host itself closes the socket within 5 s; silence is not a close. */
    private suspend fun hostClosesWithin(receive: suspend () -> Frame?): Boolean = withTimeoutOrNull(5.seconds) {
        while (true) {
            val frame = receive()
            if (frame == null || frame is Frame.Close) break
        }
        true
    } ?: false

    private fun hello(secret: String? = Harness.SECRET) = NearbyProtocol.encode(
        ClientMsg.Hello(PROTOCOL_VERSION, "fake", Harness.GAME_VERSION, "Raw", secret),
    )

    @Test
    fun givenSeatedJoiner_whenItReplaysAnEarlierFrame_thenHostClosesTheConnection() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open()
            rawJoiner(port) { secure, send, receive ->
                val frame = secure.seal(hello())
                send(frame)
                val welcome = (receive() as Frame.Binary).let { NearbyProtocol.decodeHost(secure.open(it.data)!!) }
                assertIs<HostMsg.Welcome>(welcome)
                send(frame) // the same bytes again
                assertTrue(hostClosesWithin(receive), "the host must drop a connection that replays a frame")
            }
            host.lobby.first { it.seats.size == 2 && !it.seats[1].connected }
        }
    }

    @Test
    fun givenChangedFirstFrame_whenHostReadsIt_thenClosedWithoutSeatOrRequest() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open()
            rawJoiner(port) { secure, send, receive ->
                val frame = secure.seal(hello(secret = null))
                frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x40).toByte()
                send(frame)
                assertTrue(hostClosesWithin(receive), "the host must drop a connection that sends a changed frame")
            }
            assertEquals(1, host.lobby.value.seats.size)
            assertEquals(emptyList(), host.joinRequests.value)
        }
    }

    @Test
    fun givenCodeWithAnotherRoomsFingerprint_whenJoining_thenEndsHostKeyMismatchAndHostSeesNothing() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val timeline = java.util.Collections.synchronizedList(mutableListOf<String>())
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            traceSink = { timeline += "${t0.elapsedNow().inWholeMilliseconds} $it" }
            val port = host.open()
            val c = client()
            c.join(JoinCode("127.0.0.1", port, Harness.SECRET, "0123456789abcdef"), "Mallory")
            assertEquals(ClientStatus.Ended(EndReason.HostKeyMismatch), c.status.first { it is ClientStatus.Ended }, "timeline $timeline")
            traceSink = null
            assertEquals(1, host.lobby.value.seats.size)
            assertEquals(emptyList(), host.joinRequests.value)
            assertNull(c.checkCode.value, "nothing was agreed")
        }
    }

    @Test
    fun givenJoinRequest_whenBothScreensLook_thenCheckCodesMatch() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open()
            val c = client()
            c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, "127.0.0.1", port), "Asha")
            c.status.first { it == ClientStatus.WaitingForHost }
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertTrue(Regex("\\d{6}").matches(request.checkCode))
            assertEquals(request.checkCode, c.checkCode.value)
        }
    }
}
