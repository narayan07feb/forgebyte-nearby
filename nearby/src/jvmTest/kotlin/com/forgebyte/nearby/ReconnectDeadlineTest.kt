package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A joiner never stays on Connecting or Reconnecting past its time (night run 8 Nov, host_kill LM run 2: the joiner
 * was still Reconnecting 33 s after the 120 s retry time). The host here sits behind a TCP proxy; "silent" mode cuts
 * every connection and then accepts new ones without ever answering, like a port that takes the TCP connect and
 * then hangs before the WebSocket upgrade.
 */
class ReconnectDeadlineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(), scope)
    private val proxy = Proxy()

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        proxy.close()
        host.leave()
        scope.cancel()
    }

    private fun client(firstConnectFor: Duration = 10.seconds) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, firstConnectFor)

    @Test
    fun givenPortThatNeverAnswersTheUpgrade_whenJoining_thenUnreachableAfterTheFirstConnectTime() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            proxy.silent = true
            val c = client(firstConnectFor = 2.seconds)
            val t0 = TimeSource.Monotonic.markNow()
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, "0123456789abcdef"), "Asha")
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertInWindow(t0.elapsedNow(), 2.seconds)
        }
    }

    @Test
    fun givenSeatedJoiner_whenHostPortHangsAfterADrop_thenUnreachableAfterTheRetryTime() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val c = client().also { it.retryFor = 3.seconds }
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })

            proxy.silent = true // the host is gone, its port now hangs
            c.status.first { it == ClientStatus.Reconnecting }
            val t0 = TimeSource.Monotonic.markNow()
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertInWindow(t0.elapsedNow(), 3.seconds)
        }
    }

    @Test
    fun givenSavedSeat_whenResumingToAPortThatHangs_thenUnreachableAfterTheRetryTime() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            proxy.silent = true // the host was killed with the app; its address now hangs
            val c = client().also { it.retryFor = 3.seconds }
            val t0 = TimeSource.Monotonic.markNow()
            c.resume(SavedSeat("127.0.0.1", proxy.port, "0123456789abcdef", "fake", "Asha", "tok1"))
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertInWindow(t0.elapsedNow(), 3.seconds)
        }
    }

    /** Ends at the limit: not before it (it kept trying), and not long after (no attempt held it). */
    private fun assertInWindow(took: Duration, limit: Duration) =
        assertTrue(took >= limit - 1.seconds && took < limit + 3.seconds, "ended after $took, limit $limit")

    /** Forwards to [target]; [silent] cuts every open connection and leaves new ones unanswered. */
    private class Proxy {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val open = Collections.synchronizedList(mutableListOf<Socket>())
        val port: Int = server.localPort
        @Volatile var target = 0
        @Volatile var silent = false
            set(value) {
                field = value
                if (value) synchronized(open) { open.forEach { runCatching { it.close() } }; open.clear() }
            }

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val inbound = runCatching { server.accept() }.getOrNull() ?: break
                    if (silent) { held += inbound; continue } // accepted, never read or answered
                    val outbound = runCatching { Socket(InetAddress.getLoopbackAddress(), target) }.getOrNull()
                        ?: run { inbound.close(); null } ?: continue
                    open += inbound; open += outbound
                    pump(inbound, outbound); pump(outbound, inbound)
                }
            }
        }

        private val held = Collections.synchronizedList(mutableListOf<Socket>())

        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { from.close() }; runCatching { to.close() }
        }

        fun close() {
            server.close()
            (open.toList() + held.toList()).forEach { runCatching { it.close() } }
        }
    }
}
