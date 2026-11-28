package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        job.cancel()
        delay(1_000)
        assertEquals(listOf("Host", "Asha"), host.lobby.value.seats.map { it.name }, "no Leave reached the host")
        assertEquals(false, host.lobby.value.seats[1].connected, "the host saw the link drop")
        assertEquals(saved, store.load(), "the seat stays for resume()")
        assertTrue(withTimeoutOrNull(5.seconds) { c.http.coroutineContext[kotlinx.coroutines.Job]!!.join() } != null, "the HTTP client is closed")
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
        c.leave()
        gate.closed = false // the host is back: a client that ignores leave() reconnects now
        val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended || it == ClientStatus.Connected } }
        assertEquals(ClientStatus.Ended(EndReason.Left), end)
        assertEquals(null, store.load(), "a left seat is not kept")
    }

    @Test
    fun givenANewerSeatInTheStore_whenThisClientIsKicked_thenItDoesNotClearTheNewerSeat() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val c = client(store)
        c.joinedVia(host.open(bindHost = LOOPBACK))
        store.save(otherSeat) // another client saved its seat since
        host.kick(1)
        withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } } ?: error("not ended: ${c.status.value}")
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
    fun givenAnExpiredSavedSeat_whenAnotherSeatIsInTheStore_thenResumeDoesNotClearIt() {
        val store = InMemorySeatStore()
        store.save(otherSeat)
        val c = client(store)
        c.wallClock = { 100 * 60_000L }
        val old = otherSeat.copy(token = "old-token", droppedAtMs = 0)
        c.resume(old)
        assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.value)
        assertEquals(otherSeat, store.load(), "the expired seat is not the one in the store")
    }
}
