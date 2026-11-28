package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How a joiner ends: leaving, being torn down by the app, and who may clear the saved seat. */
class ClientLifecycleTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(), scope)
    private val gates = mutableListOf<Gate>()

    @AfterTest
    fun tearDown() = runBlocking {
        gates.forEach { it.close() }
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    /** Forwards to the host; while [closed] it takes each new connection and closes it at once (host unreachable). */
    private class Gate(target: Int) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        @Volatile var closed = false
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        init {
            thread(isDaemon = true) {
                while (true) {
                    val joiner = runCatching { server.accept() }.getOrNull() ?: break
                    if (closed) { runCatching { joiner.close() }; continue }
                    val toHost = Socket(InetAddress.getLoopbackAddress(), target)
                    sockets += listOf(joiner, toHost)
                    pump(joiner, toHost)
                    pump(toHost, joiner)
                }
            }
        }

        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { to.close() }
            runCatching { from.close() }
        }

        fun close() {
            runCatching { server.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }
    }

    private fun client(store: SeatStore) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, seatStore = store)

    private suspend fun NearbyClient<FakeMove, FakeView>.joinedVia(port: Int): kotlinx.coroutines.Job {
        val job = join(JoinCode(LOOPBACK, port, Harness.SECRET, host.fingerprint!!), "Asha")
        assertEquals(ClientStatus.Connected, withTimeoutOrNull(10.seconds) { status.first { it == ClientStatus.Connected } })
        return job
    }

    private val otherSeat = SavedSeat(LOOPBACK, 1, "fp", "fake", "Newer", "newer-token")

    @Test
    fun givenSeated_whenTheAppCancelsTheJoinJob_thenTheHostKeepsTheSeatAndTheSavedSeatStays() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val c = client(store)
        val job = c.joinedVia(host.open(bindHost = LOOPBACK))
        val saved = store.load() ?: error("no seat saved on the Welcome")
        job.cancelAndJoin()
        val lobby = withTimeoutOrNull(10.seconds) { host.lobby.first { it.seats.size == 2 && !it.seats[1].connected } }
        assertEquals(listOf("Host", "Asha"), lobby?.seats?.map { it.name }, "the host saw the link drop and kept the seat: no Leave")
        assertEquals(saved, store.load(), "the seat stays for resume()")
        assertTrue(withTimeoutOrNull(5.seconds) { c.httpClosed.join() } != null, "the HTTP client is closed")
    }

    @Test
    fun givenASavedSeatTheHostNoLongerHas_whenResumed_thenTheRefusalClearsThatSeat() = runBlocking<Unit> {
        val port = host.open(bindHost = LOOPBACK)
        val gone = SavedSeat(LOOPBACK, port, host.fingerprint!!, "fake", "Asha", "token-the-host-never-gave")
        val store = InMemorySeatStore().apply { save(gone) }
        val c = client(store)
        c.resume(gone)
        val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } } as? ClientStatus.Ended
        assertEquals(RefuseReason.RECONNECT_EXPIRED, (end?.reason as? EndReason.Refused)?.reason, "ended as $end")
        assertEquals(null, store.load(), "the resumed seat was this client's, so the refusal clears it")
    }

    @Test
    fun givenLinkDown_whenThePlayerLeaves_thenTheClientEndsLeftAndDoesNotComeBack() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val gate = Gate(host.open(bindHost = LOOPBACK)).also { gates += it }
        val c = client(store)
        c.joinedVia(gate.port)
        gate.closed = true // the host is out of reach
        c.dropForTest()
        withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Reconnecting } } ?: error("no reconnect: ${c.status.value}")
        val hellos = java.util.concurrent.atomic.AtomicInteger()
        traceSink = { if (it.startsWith("host: ") && it.contains(" hello ")) hellos.incrementAndGet() }
        try {
            c.leave()
            gate.closed = false // the host is back: a client that ignores leave() reconnects now
            val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } }
            assertEquals(ClientStatus.Ended(EndReason.Left), end)
            delay(500)
            assertEquals(0, hellos.get(), "no hello reached the host after leave()")
        } finally {
            traceSink = null
        }
        assertEquals(null, store.load(), "a left seat is not kept")
    }

    @Test
    fun givenWaitingToRetry_whenThePlayerLeaves_thenTheClientEndsAtOnceNotAfterTheRetryWait() = runBlocking<Unit> {
        val gate = Gate(host.open(bindHost = LOOPBACK)).also { gates += it }
        val c = client(InMemorySeatStore())
        c.joinedVia(gate.port)
        gate.closed = true
        val failed = kotlinx.coroutines.CompletableDeferred<Unit>()
        traceSink = { if (it.startsWith("joiner: attempt failed")) failed.complete(Unit) }
        try {
            c.dropForTest()
            withTimeoutOrNull(10.seconds) { failed.await() } ?: error("no failed attempt: ${c.status.value}")
            delay(100) // past the end-of-attempt checks, into the wait
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            c.leave() // now in the 1 s wait before the next try
            val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } }
            assertEquals(ClientStatus.Ended(EndReason.Left), end)
            withTimeoutOrNull(5.seconds) { c.httpClosed.join() } ?: error("the client was never closed")
            assertTrue(t0.elapsedNow() < 500.milliseconds, "closed ${t0.elapsedNow()} after leave(), the retry wait is 1 s")
        } finally {
            traceSink = null
        }
    }

    @Test
    fun givenANewerSeatInTheStore_whenThisClientIsKicked_thenItDoesNotClearTheNewerSeat() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val c = client(store)
        c.joinedVia(host.open(bindHost = LOOPBACK))
        store.save(otherSeat) // another client saved its seat since
        host.kick(1)
        val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } }
        assertEquals(ClientStatus.Ended(EndReason.Kicked), end)
        assertEquals(otherSeat, store.load(), "only the seat this client saved may be cleared")
    }

    @Test
    fun givenANewerSeatInTheStore_whenThisClientsLinkDrops_thenItDoesNotOverwriteTheNewerSeat() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val gate = Gate(host.open(bindHost = LOOPBACK)).also { gates += it }
        val c = client(store)
        c.joinedVia(gate.port)
        store.save(otherSeat)
        gate.closed = true
        c.dropForTest()
        withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Reconnecting } } ?: error("no reconnect: ${c.status.value}")
        assertEquals(otherSeat, store.load(), "the drop stamp goes only on this client's own seat")
    }

    @Test
    fun givenAnExpiredSavedSeat_whenAnotherSeatIsInTheStore_thenResumeDoesNotClearIt() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        store.save(otherSeat)
        val c = client(store)
        c.wallClock = { 100 * 60_000L }
        c.resume(otherSeat.copy(token = "old-token", droppedAtMs = 0)).join()
        assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.value)
        assertEquals(otherSeat, store.load(), "the expired seat is not the one in the store")
    }

    @Test
    fun givenAnExpiredSavedSeat_whenItIsTheOneInTheStore_thenResumeClearsItAndClosesTheClient() = runBlocking<Unit> {
        val expired = otherSeat.copy(droppedAtMs = 0)
        val store = InMemorySeatStore().apply { save(expired) }
        val c = client(store)
        c.wallClock = { 100 * 60_000L }
        c.resume(expired).join()
        assertEquals(null, store.load())
        assertTrue(withTimeoutOrNull(5.seconds) { c.httpClosed.join() } != null, "the HTTP client is closed")
    }
}
