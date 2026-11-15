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

    /**
     * A2a (ceo 16 Nov 03:3x): a joiner that gives up as Unreachable keeps its seat token, so the game can rejoin when it
     * comes back to the front; the host, still holding the seat, gives it back (device run: the Lava's token was cleared
     * after 120 s in the background and the player saw 'Room closed' although the host kept the seat).
     */
    @Test
    fun givenSeatedJoiner_whenItGivesUpUnreachable_thenTheStoreKeepsTheSeatAndAResumeGetsItBack() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val store = InMemorySeatStore()
            val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, seatStore = store)
                .also { it.retryFor = 3.seconds }
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
            proxy.silent = true
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            val kept = store.load()
            assertTrue(kept != null, "the seat token was cleared when the joiner gave up")

            proxy.silent = false // the host is reachable again (the app came back to the front)
            val again = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, seatStore = store)
            again.resume(kept)
            assertEquals(ClientStatus.Connected, again.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
            host.lobby.first { it.seats.size == 2 && it.seats[1].connected }
        }
    }

    /** A2b: while the joiner app is paused (background) its retry clock does not run, so it does not give up. */
    @Test
    fun givenPausedJoiner_whenTheHostIsGoneLongerThanTheRetryTime_thenItWaitsAndReconnectsAfterResume() = runBlocking<Unit> {
        withTimeout(40.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val c = client().also { it.retryFor = 3.seconds }
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
            c.pause()
            proxy.silent = true
            c.status.first { it == ClientStatus.Reconnecting }
            kotlinx.coroutines.delay(7_000) // more than twice the retry time
            assertEquals(ClientStatus.Reconnecting, c.status.value, "the joiner gave up while it was paused")
            proxy.silent = false
            c.resume()
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
        }
    }

    /** A2c: after a pause the retry clock runs again from the resume, so a host that never returns still ends it. */
    @Test
    fun givenPausedJoiner_whenResumedAndTheHostNeverReturns_thenUnreachableOneRetryTimeAfterResume() = runBlocking<Unit> {
        withTimeout(40.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val c = client().also { it.retryFor = 3.seconds }
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
            c.pause()
            proxy.silent = true
            c.status.first { it == ClientStatus.Reconnecting }
            kotlinx.coroutines.delay(5_000)
            c.resume()
            val t0 = TimeSource.Monotonic.markNow()
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertInWindow(t0.elapsedNow(), 3.seconds)
        }
    }

    private fun keeping(store: SeatStore, wall: () -> Long) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, seatStore = store)
            .also { it.wallClock = wall }

    /** KS-1 (ceo 16 Nov 04:3x, B): a seat kept after a drop more than 10 min ago is not tried: Unreachable at once. */
    @Test
    fun givenSeatDroppedMoreThanTenMinutesAgo_whenResumed_thenUnreachableAtOnceAndTheStoreCleared() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val store = InMemorySeatStore()
            val old = SavedSeat("127.0.0.1", proxy.port, host.fingerprint!!, "fake", "Asha", "tok1", droppedAtMs = 1_000)
            store.save(old)
            val c = keeping(store) { 1_000 + 10 * 60_000 + 1 }
            val t0 = TimeSource.Monotonic.markNow()
            c.resume(old)
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertTrue(t0.elapsedNow() < 1.seconds, "an expired seat was still tried for ${t0.elapsedNow()}")
            assertEquals(null, store.load(), "the expired seat was not cleared")
            assertEquals(0, proxy.accepted.get(), "the expired seat's token went to the network")
        }
    }

    /** KS-2: a seat dropped 9 min ago is still tried, and the host gives it back. */
    @Test
    fun givenSeatDroppedNineMinutesAgo_whenResumed_thenTheHostGivesItBack() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val store = InMemorySeatStore()
            val first = keeping(store) { 0L }
            first.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            first.status.first { it == ClientStatus.Connected }
            val seat = store.load()!!.copy(droppedAtMs = 1_000)
            val again = keeping(store) { 1_000 + 9 * 60_000 }
            again.resume(seat)
            assertEquals(ClientStatus.Connected, again.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
        }
    }

    /** KS-3: a drop writes its time into the store; the next welcome clears it again (seated: null). */
    @Test
    fun givenSeatedJoiner_whenTheLinkDropsAndComesBack_thenTheStoreHasTheDropTimeThenNull() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            proxy.target = host.open(bindHost = LOOPBACK)
            val store = InMemorySeatStore()
            val c = keeping(store) { 42_000L }
            c.join(JoinCode("127.0.0.1", proxy.port, Harness.SECRET, host.fingerprint!!), "Asha")
            c.status.first { it == ClientStatus.Connected }
            assertEquals(null, store.load()!!.droppedAtMs)
            proxy.silent = true
            c.status.first { it == ClientStatus.Reconnecting }
            assertEquals(42_000L, store.load()!!.droppedAtMs, "the drop time was not saved")
            proxy.silent = false
            c.status.first { it == ClientStatus.Connected }
            assertEquals(null, store.load()!!.droppedAtMs, "a welcome did not clear the drop time")
        }
    }

    /** KS-4: a seat saved by an older version (no drop time) reads as seated, and resume tries it as before. */
    @Test
    fun givenOldSavedTextWithoutTheDropTime_whenParsed_thenItReadsAsSeated() {
        val old = """{"host":"127.0.0.1","port":1,"fingerprint":"ab","gameId":"fake","name":"Asha","token":"tok1"}"""
        assertEquals(null, SavedSeat.parse(old)!!.droppedAtMs)
    }

    /** Ends at the limit: not before it (it kept trying), and not long after (no attempt held it). */
    private fun assertInWindow(took: Duration, limit: Duration) =
        assertTrue(took >= limit - 1.seconds && took < limit + 3.seconds, "ended after $took, limit $limit")

    /** Forwards to [target]; [silent] cuts every open connection and leaves new ones unanswered. */
    private class Proxy {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val open = Collections.synchronizedList(mutableListOf<Socket>())
        val port: Int = server.localPort
        /** Connections the proxy accepted, so a test can prove nothing reached the network. */
        val accepted = java.util.concurrent.atomic.AtomicInteger()
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
                    accepted.incrementAndGet()
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
