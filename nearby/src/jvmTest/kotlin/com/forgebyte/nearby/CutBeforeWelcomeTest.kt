package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * ceo, 2026-11-03: on flaky Wi-Fi a joiner's socket can die after the host seated it but before the Welcome arrives.
 * The joiner retries without a token. Does it end with one seat and a view after start, or with an orphan seat?
 */
class CutBeforeWelcomeTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(), scope)

    @AfterTest
    fun tearDown() = runBlocking {
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    /**
     * A loopback relay in front of the host. On its first connection it passes everything to the host, and from the
     * host only the HTTP upgrade and the key exchange reply; when the host's first encrypted frame (the Welcome)
     * arrives it cuts both sockets, as if Wi-Fi dropped right then. Later connections pass untouched.
     */
    private class CutRelay(target: Int) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val connections = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val joiner = runCatching { server.accept() }.getOrNull() ?: break
                    val toHost = Socket(InetAddress.getLoopbackAddress(), target)
                    val first = connections.getAndIncrement() == 0
                    pump(joiner.getInputStream(), toHost, joiner) // joiner -> host, untouched
                    if (first) cutAtFirstBinary(toHost.getInputStream(), joiner, toHost) else pump(toHost.getInputStream(), joiner, toHost)
                }
            }
        }

        private fun pump(from: InputStream, to: Socket, other: Socket) = thread(isDaemon = true) {
            val buf = ByteArray(4096)
            runCatching {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.getOutputStream().write(buf, 0, n)
                }
            }
            runCatching { to.close() }
            runCatching { other.close() }
        }

        /** Host -> joiner: the HTTP header and text frames go through; the first binary frame closes both sockets. */
        private fun cutAtFirstBinary(from: InputStream, joiner: Socket, host: Socket) = thread(isDaemon = true) {
            runCatching {
                val out = joiner.getOutputStream()
                val header = ByteArrayOutputStream()
                while (!header.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) header.write(from.read())
                out.write(header.toByteArray())
                while (true) {
                    val b0 = from.read()
                    val b1 = from.read()
                    var len = (b1 and 0x7f).toLong()
                    val ext = ByteArrayOutputStream()
                    if (len == 126L) { val x = from.readNBytes(2); ext.write(x); len = ((x[0].toInt() and 0xff) shl 8 or (x[1].toInt() and 0xff)).toLong() }
                    if (len == 127L) { val x = from.readNBytes(8); ext.write(x); len = x.fold(0L) { a, v -> (a shl 8) or (v.toLong() and 0xff) } }
                    val payload = from.readNBytes(len.toInt())
                    if (b0 and 0x0f == 0x2) break // binary: the encrypted Welcome; drop it and cut
                    out.write(b0); out.write(b1); out.write(ext.toByteArray()); out.write(payload)
                }
            }
            runCatching { joiner.close() }
            runCatching { host.close() }
        }
    }

    private suspend fun cutThenRetry(request: Boolean) {
        val port = host.open(bindHost = LOOPBACK)
        val relay = CutRelay(port)
        val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
        if (request) {
            c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, relay.port), "Asha")
            // Accept each request as it comes; with the cut, the first answer is lost with its socket.
            scope.launch { host.joinRequests.collect { list -> list.forEach { host.acceptJoin(it.id) } } }
        } else {
            c.join(JoinCode(LOOPBACK, relay.port, Harness.SECRET, host.fingerprint!!), "Asha")
        }
        val status = withTimeoutOrNull(20.seconds) { c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended } }
        assertEquals(ClientStatus.Connected, status, "joined after the retry")
        assertEquals(true, relay.connections.get() >= 2, "the first socket was cut and the joiner retried")
        c.ready(true)
        val lobby = withTimeoutOrNull(10.seconds) { host.lobby.first { l -> l.seats.size == 2 && l.seats[1].ready } }
        assertEquals(listOf("Host", "Asha"), (lobby ?: host.lobby.value).seats.map { it.name }, "one seat for Asha, no orphan: ${host.lobby.value}")
        withTimeoutOrNull(10.seconds) { host.canStart.first { it } } ?: error("never startable: ${host.lobby.value}")
        host.startGame(seed = 1)
        withTimeoutOrNull(10.seconds) { c.view.first { it != null } } ?: error("no view after start")
    }

    // Fails today (evidence, 2026-11-03): lobby [Host, Asha (offline), Asha]; the start waits for the reconnect
    // window. Turned on by the join-id fix.
    @org.junit.Ignore("orphan seat confirmed; enabled by the join-id fix")
    @Test
    fun givenCodeJoin_whenSocketDiesAfterSeatingBeforeWelcome_thenRetryEndsWithOneSeatAndAView() = runBlocking<Unit> {
        cutThenRetry(request = false)
    }

    @org.junit.Ignore("orphan seat confirmed; enabled by the join-id fix")
    @Test
    fun givenRequestJoin_whenSocketDiesAfterAcceptBeforeWelcome_thenRetryEndsWithOneSeatAndAView() = runBlocking<Unit> {
        cutThenRetry(request = true)
    }
}
