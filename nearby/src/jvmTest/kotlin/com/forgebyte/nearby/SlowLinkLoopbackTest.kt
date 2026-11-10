package com.forgebyte.nearby

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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * PLAN test 3, delayed messages: a full match through a relay that holds every chunk back in both directions, as a
 * slow Wi-Fi does. Slow is not gone: nobody may reconnect, and everyone must end on the host's state.
 */
class SlowLinkLoopbackTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(), scope)

    @AfterTest
    fun tearDown() = runBlocking {
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    /** Passes bytes between joiners and the host, each chunk [delayMs] late and in order, like TCP on a slow link. */
    private class DelayRelay(target: Int, private val delayMs: IntRange) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val connections = AtomicInteger()
        @Volatile private var stalledUntil = 0L

        /** Nothing moves in either direction for [ms], as when Wi-Fi hangs without dropping. */
        fun stall(ms: Long) {
            stalledUntil = System.currentTimeMillis() + ms
        }

        init {
            thread(isDaemon = true) {
                while (true) {
                    val joiner = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    val toHost = Socket(InetAddress.getLoopbackAddress(), target)
                    pump(joiner, toHost)
                    pump(toHost, joiner)
                }
            }
        }

        private fun pump(from: Socket, to: Socket) {
            val chunks = LinkedBlockingQueue<Pair<Long, ByteArray>>()
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                runCatching {
                    while (true) {
                        val n = from.getInputStream().read(buf)
                        if (n < 0) break
                        chunks.put(System.currentTimeMillis() + delayMs.random() to buf.copyOf(n))
                    }
                }
                chunks.put(0L to ByteArray(0))
            }
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val (due, bytes) = chunks.take()
                        if (bytes.isEmpty()) break
                        while (true) {
                            val wait = maxOf(due, stalledUntil) - System.currentTimeMillis()
                            if (wait <= 0) break
                            Thread.sleep(wait)
                        }
                        to.getOutputStream().write(bytes)
                    }
                }
                runCatching { to.close() }
                runCatching { from.close() }
            }
        }
    }

    /** Three joiners and the host play a whole match through [relay]; returns how long the game took, in ms. */
    private suspend fun match(relay: DelayRelay, duringGame: suspend () -> Unit = {}): Long {
        val clients = (1..3).map { i ->
            NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope).also {
                it.join(JoinCode(LOOPBACK, relay.port, Harness.SECRET, host.fingerprint!!), "P$i")
                it.status.first { s -> s == ClientStatus.Connected }
            }
        }
        host.lobby.first { it.seats.size == 4 }
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
        scope.launch { duringGame() }
        val started = TimeSource.Monotonic.markNow()
        host.startGame(seed = 11)
        host.lobby.first { it.phase == Phase.OVER }
        val took = started.elapsedNow().inWholeMilliseconds

        val final = host.view.value!!
        assertEquals(final.pile.size, final.pile.toSet().size, "a card was played twice: ${final.pile}")
        clients.forEach { c ->
            val v = c.view.first { it?.pile == final.pile }!!
            assertEquals(final.turn, v.turn)
            assertEquals(final.otherCounts[c.lobby.value!!.yourSeat], v.hand.size)
        }
        assertEquals(3, relay.connections.get(), "a joiner reconnected on a link that was only slow")
        return took
    }

    @Test
    fun givenADelayedLink_whenFullMatch_thenEveryoneEndsOnTheHostsStateWithNoReconnect() = runBlocking<Unit> {
        withTimeout(60.seconds) {
            val relay = DelayRelay(host.open(bindHost = LOOPBACK), 20..120)
            val took = match(relay)
            // Each joiner move is at least one view and one move across the relay, 20 ms each way, and at least 15 happen.
            assertTrue(took >= 600, "the relay did not slow the game: $took ms")
        }
    }

    @Test
    fun givenALinkThatHangsForSixSeconds_whenFullMatch_thenTheGameCarriesOnWithNoReconnect() = runBlocking<Unit> {
        withTimeout(60.seconds) {
            val relay = DelayRelay(host.open(bindHost = LOOPBACK), 0..5)
            val took = match(relay) {
                host.view.filterNotNull().first { it.pile.size >= 5 }
                relay.stall(6_000)
            }
            assertTrue(took >= 6_000, "the link never hung: $took ms")
        }
    }
}
