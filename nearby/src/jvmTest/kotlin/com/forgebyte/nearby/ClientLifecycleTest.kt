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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        scope.cancel() // first: leave() cannot be cut by a timeout, so nothing of a failed test may still hold it up
        host.leave()
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
            runCatching {
                val buf = ByteArray(4096)
                while (true) {
                    val n = from.getInputStream().read(buf)
                    if (n < 0) break
                    to.getOutputStream().write(buf, 0, n)
                }
            }
            runCatching { to.close() }
            runCatching { from.close() }
        }

        fun close() {
            runCatching { server.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }
    }

    private fun client(store: SeatStore, on: CoroutineScope = scope) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), on, seatStore = store)

    /** An app's store whose next [load] (or [save]) waits, after [entered], until [release]: a slow disk. */
    private class SlowStore : SeatStore {
        val inner = InMemorySeatStore()
        @Volatile var pauseLoad = false
        @Volatile var pauseSave = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private fun pause() { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        override fun load(): SavedSeat? { if (pauseLoad) { pauseLoad = false; pause() }; return inner.load() }
        override fun save(seat: SavedSeat) { if (pauseSave) { pauseSave = false; pause() }; inner.save(seat) }
        override fun clear() = inner.clear()
        fun awaitEntered() = assertTrue(entered.await(10, TimeUnit.SECONDS), "the store call started")
    }

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
    fun givenAKickedClient_whenTheAppCancelsJustBeforeTheClear_thenTheSavedSeatStays() = runBlocking<Unit> {
        val store = InMemorySeatStore()
        val c = client(store)
        val job = c.joinedVia(host.open(bindHost = LOOPBACK))
        val saved = store.load() ?: error("no seat saved on the Welcome")
        // The last line before the clear: a cancel here finds the seat lock free, so only the check under it stops the clear.
        traceSink = { if (it.startsWith("joiner: ended")) job.cancel() }
        try {
            host.kick(1)
            assertTrue(withTimeoutOrNull(10.seconds) { job.join() } != null, "the join Job ended")
        } finally {
            traceSink = null
        }
        assertEquals(saved, store.load(), "a cancelled client keeps its seat")
    }

    @Test
    fun givenAKickedClient_whenTheAppCancelsWhileTheStoreLoads_thenTheSavedSeatStays() = runBlocking<Unit> {
        val store = SlowStore()
        val job = client(store).joinedVia(host.open(bindHost = LOOPBACK))
        val saved = store.load() ?: error("no seat saved on the Welcome")
        store.pauseLoad = true // the clear's ownership check
        host.kick(1)
        store.awaitEntered()
        job.cancel()
        store.release.countDown()
        assertTrue(withTimeoutOrNull(10.seconds) { job.join() } != null, "the join Job ended")
        assertEquals(saved, store.load(), "a cancel during the check writes nothing")
    }

    @Test
    fun givenALinkDrop_whenTheAppCancelsWhileTheStoreLoads_thenNoDropStampIsWritten() = runBlocking<Unit> {
        val store = SlowStore()
        val c = client(store)
        val job = c.joinedVia(host.open(bindHost = LOOPBACK))
        val saved = store.load() ?: error("no seat saved on the Welcome")
        store.pauseLoad = true // the drop stamp's ownership check
        c.dropForTest()
        store.awaitEntered()
        job.cancel()
        store.release.countDown()
        assertTrue(withTimeoutOrNull(10.seconds) { job.join() } != null, "the join Job ended")
        assertEquals(saved, store.load(), "a cancel during the check writes nothing")
    }

    @Test
    fun givenAnExpiredSavedSeat_whenTheScopeIsCancelledWhileTheStoreLoads_thenTheSeatStays() = runBlocking<Unit> {
        val expired = otherSeat.copy(droppedAtMs = 0)
        val store = SlowStore().apply { inner.save(expired); pauseLoad = true }
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = client(store, own)
        c.wallClock = { 100 * 60_000L }
        thread(isDaemon = true) { store.entered.await(10, TimeUnit.SECONDS); own.cancel(); store.release.countDown() }
        c.resume(expired).join() // the clear runs at once on this thread and waits in load()
        assertEquals(expired, store.load(), "a cancel during the check writes nothing")
    }

    @Test
    fun givenTheWelcomeSaveIsSlow_whenTheAppSendsReady_thenItIsRefusedUntilTheSaveIsDone() = runBlocking<Unit> {
        val store = SlowStore().apply { pauseSave = true }
        val c = client(store)
        c.join(JoinCode(LOOPBACK, host.open(bindHost = LOOPBACK), Harness.SECRET, host.fingerprint!!), "Asha")
        store.awaitEntered()
        assertFalse(c.ready(true), "not welcomed while the rotated token is not on disk")
        store.release.countDown()
        assertEquals(ClientStatus.Connected, withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Connected } })
    }

    @Test
    fun givenTheWelcomeSaveFails_whenTheClientRetries_thenTheTokenOnDiskStillResumes() = runBlocking<Unit> {
        // Saves of a rotated token fail; a drop stamp (same token) still works.
        val store = object : SeatStore {
            val inner = InMemorySeatStore()
            @Volatile var failNewTokens = false
            val failed = java.util.concurrent.atomic.AtomicInteger()
            override fun load() = inner.load()
            override fun save(seat: SavedSeat) {
                if (failNewTokens && seat.token != inner.load()?.token) { failed.incrementAndGet(); error("disk full") }
                inner.save(seat)
            }
            override fun clear() = inner.clear()
        }
        val c = client(store)
        val job = c.joinedVia(host.open(bindHost = LOOPBACK))
        val onDisk = store.load() ?: error("no seat saved on the Welcome")
        store.failNewTokens = true
        c.dropForTest()
        // Two failed Welcome saves: the first reconnect and its retry, which must still present the token on disk.
        assertTrue(withTimeoutOrNull(15.seconds) { while (store.failed.get() < 2) delay(50) } != null, "two Welcomes came")
        job.cancelAndJoin()
        val stored = store.load() ?: error("the seat was cleared")
        assertEquals(onDisk.token, stored.token, "no rotated token was saved, only the drop stamp")
        store.failNewTokens = false
        val again = client(store)
        again.resume(stored)
        val st = withTimeoutOrNull(10.seconds) { again.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended } }
        assertEquals(ClientStatus.Connected, st, "the token on disk still holds the seat")
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
    fun givenTheStoreHoldsTheSameSeatWithANewerDrop_whenAnOlderCopyIsResumedExpired_thenTheStoreKeepsIt() = runBlocking<Unit> {
        val newer = otherSeat.copy(droppedAtMs = 99 * 60_000L)
        val store = InMemorySeatStore().apply { save(newer) }
        val c = client(store)
        c.wallClock = { 100 * 60_000L }
        c.resume(otherSeat.copy(droppedAtMs = 0)).join() // same token, an older drop: this copy expired
        assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.value)
        assertEquals(newer, store.load(), "only the exact expired record is cleared")
    }

    @Test
    fun givenNoLocalAddress_whenAShortCodeJoinEnds_thenTheClientIsClosed() = runBlocking<Unit> {
        val c = client(InMemorySeatStore())
        c.myAddresses = { emptyList() }
        c.requestJoin(ShortCode.of("192.0.2.7", 4000)!!, "Asha").join() // a documentation address, never reached
        assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.value)
        assertTrue(withTimeoutOrNull(5.seconds) { c.httpClosed.join() } != null, "the HTTP client is closed")
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
