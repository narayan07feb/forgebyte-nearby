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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    private val relays = mutableListOf<CutRelay>()

    @AfterTest
    fun tearDown() = runBlocking {
        withTimeoutOrNull(10.seconds) { host.leave() }
        relays.forEach { it.close() }
        scope.cancel()
    }

    /**
     * A loopback relay in front of the host. On connection number [cutOn] it passes everything to the host, and from
     * the host only the HTTP upgrade and the key exchange reply; when the host's first encrypted frame (the Welcome)
     * arrives it cuts both sockets, as if Wi-Fi dropped right then. With [hold] it keeps that frame back until
     * [release] instead of cutting. Other connections pass untouched. With [halfOpen] the relay never closes the host
     * side of that connection (see [cutFirstJoinerSide]) until [close]. With [holdRetry] the next connection (the
     * joiner's retry) waits at the relay, nothing passed either way, until [releaseRetry].
     */
    private class CutRelay(
        target: Int,
        private val cutOn: Int = 0,
        private val hold: Boolean = false,
        private val halfOpen: Boolean = false,
        private val holdRetry: Boolean = false,
    ) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val connections = AtomicInteger()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        /** The relay dropped the host's first encrypted frame on connection [cutOn] and cut it. */
        val welcomeCut = CountDownLatch(1)
        /** The host closed connection [cutOn] (a Close frame or the socket's end) before sending it any encrypted frame. */
        val hostClosedFirst = CountDownLatch(1)
        val retryHeld = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        private val firstSockets = mutableListOf<Socket>()
        private val allSockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        /** Cuts the first connection now, as if Wi-Fi dropped (both ends see the socket close). */
        fun cutFirst() = synchronized(firstSockets) { firstSockets.forEach { runCatching { it.close() } } }

        /** Host sockets left open on purpose: the host has not noticed the drop yet (a half-open socket). */
        private val keptOpen = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

        /**
         * Needs [halfOpen]. Cuts the first connection on the joiner's side only. The host's socket stays open, as when
         * Wi-Fi drops and the host has not noticed yet, so the joiner's retry arrives while the host still holds the
         * old connection.
         */
        fun cutFirstJoinerSide() = synchronized(firstSockets) {
            check(halfOpen && firstSockets[1] in keptOpen) { "make the relay with halfOpen = true" }
            runCatching { firstSockets[0].close() }
        }

        private fun close(socket: Socket) {
            if (socket !in keptOpen) runCatching { socket.close() }
        }

        /** Stops the relay: its listener and every socket, the kept host sockets too. */
        fun close() {
            release.countDown() // no relay thread stays parked after a failed test
            releaseRetry.countDown()
            runCatching { server.close() }
            synchronized(allSockets) { allSockets.forEach { runCatching { it.close() } } }
        }

        init {
            thread(isDaemon = true) {
                while (true) {
                    val joiner = runCatching { server.accept() }.getOrNull() ?: break
                    val first = connections.getAndIncrement() == cutOn
                    allSockets += joiner
                    if (!first) {
                        // A held retry reaches the host only on release, so the host's key-exchange limit starts then.
                        thread(isDaemon = true) {
                            if (holdRetry) { retryHeld.countDown(); releaseRetry.await() }
                            val toHost = Socket(InetAddress.getLoopbackAddress(), target).also { allSockets += it }
                            pump(joiner.getInputStream(), toHost, joiner)
                            pump(toHost.getInputStream(), joiner, toHost)
                        }
                        continue
                    }
                    val toHost = Socket(InetAddress.getLoopbackAddress(), target)
                    allSockets += toHost
                    synchronized(firstSockets) {
                        firstSockets += listOf(joiner, toHost)
                        if (halfOpen) keptOpen += toHost // before any pump can close it
                    }
                    pump(joiner.getInputStream(), toHost, joiner) // joiner -> host, untouched
                    cutAtFirstBinary(toHost.getInputStream(), joiner, toHost)
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
            close(to)
            close(other)
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
                    if (b0 < 0) { hostClosedFirst.countDown(); break }
                    val b1 = from.read()
                    var len = (b1 and 0x7f).toLong()
                    val ext = ByteArrayOutputStream()
                    if (len == 126L) { val x = from.readNBytes(2); ext.write(x); len = ((x[0].toInt() and 0xff) shl 8 or (x[1].toInt() and 0xff)).toLong() }
                    if (len == 127L) { val x = from.readNBytes(8); ext.write(x); len = x.fold(0L) { a, v -> (a shl 8) or (v.toLong() and 0xff) } }
                    val payload = from.readNBytes(len.toInt())
                    if (b0 and 0x0f == 0x8) hostClosedFirst.countDown() // the host's WebSocket Close
                    if (b0 and 0x0f == 0x2) { // binary: the encrypted Welcome
                        if (!hold) { welcomeCut.countDown(); break } // drop it and cut
                        if (held.count > 0) {
                            held.countDown()
                            release.await()
                        }
                    }
                    // The joiner side may be cut already; keep reading the host side to see it close.
                    runCatching { out.write(b0); out.write(b1); out.write(ext.toByteArray()); out.write(payload) }
                }
            }
            close(joiner)
            close(host)
        }
    }

    private suspend fun cutThenRetry(request: Boolean) {
        val port = host.open(bindHost = LOOPBACK)
        val relay = CutRelay(port).also { relays += it }
        val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
        if (request) {
            c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, relay.port), "Asha")
            // Accept each request as it comes; with the cut, the first answer is lost with its socket.
            scope.launch { host.joinRequests.collect { list -> list.forEach { host.acceptRequest(it.id, it.checkCode) } } }
        } else {
            c.join(JoinCode(LOOPBACK, relay.port, Harness.SECRET, host.fingerprint!!), "Asha")
        }
        val status = withTimeoutOrNull(20.seconds) { c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended } }
        assertEquals(ClientStatus.Connected, status, "joined after the retry")
        assertEquals(0L, relay.welcomeCut.count, "the relay cut the first Welcome")
        assertEquals(true, relay.connections.get() >= 2, "the joiner retried")
        c.ready(true)
        val lobby = withTimeoutOrNull(10.seconds) { host.lobby.first { l -> l.seats.size == 2 && l.seats[1].ready } }
        assertEquals(listOf("Host", "Asha"), (lobby ?: host.lobby.value).seats.map { it.name }, "one seat for Asha, no orphan: ${host.lobby.value}")
        withTimeoutOrNull(10.seconds) { host.canStart.first { it } } ?: error("never startable: ${host.lobby.value}")
        host.startGame(seed = 1)
        withTimeoutOrNull(10.seconds) { c.view.first { it != null } } ?: error("no view after start")
    }

    // Failed before the join id (evidence, 2026-11-03): lobby [Host, Asha (offline), Asha].
    @Test
    fun givenCodeJoin_whenSocketDiesAfterSeatingBeforeWelcome_thenRetryEndsWithOneSeatAndAView() = runBlocking<Unit> {
        cutThenRetry(request = false)
    }

    @Test
    fun givenRequestJoin_whenFirstSocketDiesWhileHostDecides_thenTheOldPairTakesNothingAndTheRetryIsApproved() = runBlocking<Unit> {
        val port = host.open(bindHost = LOOPBACK)
        val relay = CutRelay(port, holdRetry = true).also { relays += it }
        val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
        c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, relay.port), "Asha")
        val first = withTimeoutOrNull(10.seconds) { host.joinRequests.first { it.isNotEmpty() } }?.single() ?: error("no request")
        relay.cutFirst() // the host is still deciding
        // The host notices the drop before the retry gets through: the relay holds the retry until the list is empty.
        withTimeoutOrNull(10.seconds) { host.joinRequests.first { it.isEmpty() } } ?: error("the drop was not noticed")
        assertTrue(relay.retryHeld.await(15, java.util.concurrent.TimeUnit.SECONDS), "the joiner retried")
        assertFalse(host.acceptRequest(first.id, first.checkCode))
        assertFalse(host.declineRequest(first.id, first.checkCode))
        relay.releaseRetry.countDown()
        val request = withTimeoutOrNull(15.seconds) { host.joinRequests.first { it.isNotEmpty() } }?.single() ?: error("no retry request")
        assertTrue(request.id != first.id, "the retry is a new request")
        assertFalse(host.acceptRequest(first.id, first.checkCode), "the old pair takes nothing with the new request waiting")
        assertFalse(host.declineRequest(first.id, first.checkCode))
        assertEquals(listOf(request), host.joinRequests.value, "the new request survives")
        assertTrue(host.acceptRequest(request.id, request.checkCode))
        val status = withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended } }
        assertEquals(ClientStatus.Connected, status, "the approval reached the retried connection")
        assertEquals(listOf("Host", "Asha"), host.lobby.value.seats.map { it.name })
    }

    /**
     * The host compared the first connection's code, then the joiner's retry moved the request to a new connection
     * (same id, new code) while the host still held the old socket, before the host tapped. The answer for what the host
     * saw must do nothing; the host sees the new request and answers it. [decline]: the same with Decline.
     */
    private suspend fun answerAfterRetry(decline: Boolean, host: NearbyHost<FakeState, FakeMove, FakeView> = this.host) {
        val port = host.open(bindHost = LOOPBACK)
        val relay = CutRelay(port, halfOpen = true).also { relays += it }
        val c = joiner()
        c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, relay.port), "Asha")
        val seen = withTimeoutOrNull(10.seconds) { host.joinRequests.first { it.isNotEmpty() } }?.single() ?: error("no request")
        // Bo waits behind Asha: a moved request keeps its place, a new one would come after Bo.
        joiner().requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, port), "Bo")
        val bo = withTimeoutOrNull(10.seconds) { host.joinRequests.first { it.size == 2 } }?.get(1) ?: error("no second request")
        relay.cutFirstJoinerSide()
        // The retry moves Asha's request in place: same id, the new connection's code. If the six digits came out
        // equal (rare), the room drops the request instead and Asha comes back as a new request: this order cannot
        // be checked then, so the test is skipped. That the code is compared inside the room step is shown by the
        // Room tests and the mutants, not by this order.
        val next = withTimeoutOrNull(15.seconds) {
            host.joinRequests.first { r ->
                r.size == 2 && (r[0].id == seen.id && r[0].checkCode != seen.checkCode || r.none { it.id == seen.id })
            }
        } ?: error("no retry: ${host.joinRequests.value}")
        org.junit.Assume.assumeTrue("the retry showed the same code: dropped, not moved", next[0].id == seen.id)
        val moved = next.first()
        assertEquals(listOf("Asha", "Bo"), host.joinRequests.value.map { it.name }, "the retry moved Asha's request in place")
        withTimeoutOrNull(10.seconds) { c.checkCode.first { it == moved.checkCode } } ?: error("the joiner never showed the new code")
        withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.WaitingForHost } } ?: error("joiner ${c.status.value}")
        assertTrue(relay.hostClosedFirst.await(5, java.util.concurrent.TimeUnit.SECONDS), "the retry moved the request: the host closed the old socket")
        val answered = if (decline) host.declineRequest(seen.id, seen.checkCode) else host.acceptRequest(seen.id, seen.checkCode)
        assertFalse(answered, "the answer for the code the host saw does not reach the new connection")
        // No sleep: the real answer below returns true only if this one took nothing.
        assertEquals(listOf("Host"), host.lobby.value.seats.map { it.name })
        assertEquals(listOf(moved, bo), host.joinRequests.value, "the new request stays for the host to check")
        if (decline) {
            assertTrue(host.declineRequest(moved.id, moved.checkCode))
            val end = withTimeoutOrNull(10.seconds) { c.status.first { it is ClientStatus.Ended } } as? ClientStatus.Ended
            assertEquals(RefuseReason.DECLINED, (end?.reason as? EndReason.Refused)?.reason, "ended as $end")
            assertEquals(listOf("Host"), host.lobby.value.seats.map { it.name })
        } else {
            assertTrue(host.acceptRequest(moved.id, moved.checkCode))
            assertEquals(ClientStatus.Connected, withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Connected } })
            assertEquals(listOf("Host", "Asha"), host.lobby.value.seats.map { it.name })
        }
        assertEquals(listOf(bo), host.joinRequests.value)
    }

    @Test
    fun givenRequestJoin_whenRetryMovesTheRequestBeforeAccept_thenAcceptWithTheOldCodeReturnsFalseAndTheNewRequestIsAccepted() = runBlocking<Unit> {
        answerAfterRetry(decline = false)
    }

    @Test
    fun givenRequestJoin_whenRetryMovesTheRequestBeforeDecline_thenDeclineWithTheOldCodeReturnsFalseAndTheNewRequestIsDeclined() = runBlocking<Unit> {
        answerAfterRetry(decline = true)
    }

    @Test
    fun givenRequestJoin_whenHostAnswersWithACodeTheRequestDoesNotShow_thenNothingHappensAndFalse() = runBlocking<Unit> {
        val port = host.open(bindHost = LOOPBACK)
        val c = joiner()
        c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, port), "Asha")
        val seen = withTimeoutOrNull(10.seconds) { host.joinRequests.first { it.isNotEmpty() } }?.single() ?: error("no request")
        withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.WaitingForHost } } ?: error("joiner ${c.status.value}")
        val other = if (seen.checkCode == "000000") "000001" else "000000"
        assertFalse(host.acceptRequest(seen.id, other))
        assertFalse(host.declineRequest(seen.id, other))
        assertEquals(listOf("Host"), host.lobby.value.seats.map { it.name })
        assertEquals(listOf(seen), host.joinRequests.value)
        assertFalse(host.acceptRequest(seen.id + 100, seen.checkCode), "an id no request has")
        // The real answer works, so none of the wrong ones took the request.
        assertTrue(host.acceptRequest(seen.id, seen.checkCode))
        assertEquals(ClientStatus.Connected, withTimeoutOrNull(10.seconds) { c.status.first { it == ClientStatus.Connected } })
    }

    /**
     * Accept that reaches the room lock before the joiner's retry, forced by holding the retry at the relay: true, the
     * seat goes to the connection the host saw, and the retry then takes that seat back with its join id (no new
     * request). The other order is [answerAfterRetry]. Each order runs [ROUNDS] times on a new room.
     */
    @Test
    fun givenAcceptBeforeTheRetry_whenTheRetryArrives_thenAcceptWasTrueAndTheRetryKeepsThatSeat() = runBlocking<Unit> {
        repeat(ROUNDS) { round ->
            val h = NearbyHost(FakeGame(), Harness.config(), scope)
            try {
                val relay = CutRelay(h.open(bindHost = LOOPBACK), halfOpen = true, holdRetry = true).also { relays += it }
                val c = joiner()
                c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, relay.port), "Asha")
                val seen = withTimeoutOrNull(10.seconds) { h.joinRequests.first { it.isNotEmpty() } }?.single() ?: error("no request")
                relay.cutFirstJoinerSide()
                assertTrue(relay.retryHeld.await(15, java.util.concurrent.TimeUnit.SECONDS), "round $round: the joiner retried")
                assertTrue(h.acceptRequest(seen.id, seen.checkCode), "round $round: the request was still the one the host saw")
                assertEquals(listOf("Host", "Asha"), h.lobby.value.seats.map { it.name }, "round $round")
                relay.releaseRetry.countDown()
                assertEquals(ClientStatus.Connected, withTimeoutOrNull(15.seconds) { c.status.first { it == ClientStatus.Connected } }, "round $round")
                assertEquals(listOf("Host", "Asha"), h.lobby.value.seats.map { it.name }, "round $round: one seat")
                assertEquals(emptyList(), h.joinRequests.value, "round $round: the retry made no new request")
            } finally {
                withTimeoutOrNull(10.seconds) { h.leave() }
            }
        }
    }

    @Test
    fun givenRetryBeforeTheAnswer_repeatedWithAcceptAndDecline_thenEachTimeTheOldAnswerReturnsFalse() = runBlocking<Unit> {
        repeat(ROUNDS) {
            val h = NearbyHost(FakeGame(), Harness.config(), scope)
            try {
                answerAfterRetry(decline = it % 2 == 1, host = h)
            } finally {
                withTimeoutOrNull(10.seconds) { h.leave() }
            }
        }
    }

    private companion object {
        const val ROUNDS = 5
    }

    private fun joiner() = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)

    private suspend fun NearbyClient<FakeMove, FakeView>.joinAndWait(relay: CutRelay) {
        join(JoinCode(LOOPBACK, relay.port, Harness.SECRET, host.fingerprint!!), "Asha")
        assertEquals(ClientStatus.Connected, withTimeoutOrNull(20.seconds) { status.first { it == ClientStatus.Connected } })
    }

    // Failed before the held token (10 Nov): the second retry was told "Your seat is gone".
    @Test
    fun givenReconnect_whenItsWelcomeIsLost_thenALaterRetryKeepsTheSeat() = runBlocking<Unit> {
        val relay = CutRelay(host.open(bindHost = LOOPBACK), cutOn = 1).also { relays += it }
        val c = joiner()
        c.joinAndWait(relay)
        c.dropForTest()
        withTimeoutOrNull(20.seconds) { while (relay.connections.get() < 3 || c.status.value != ClientStatus.Connected) kotlinx.coroutines.delay(20) }
            ?: error("no seat after the lost Welcome: ${c.status.value}, ${relay.connections.get()} connections")
        assertEquals(0L, relay.welcomeCut.count, "the reconnect's Welcome was really cut")
        c.ready(true)
        withTimeoutOrNull(10.seconds) { host.canStart.first { it } } ?: error("never startable: ${host.lobby.value}")
        assertEquals(listOf("Host", "Asha"), host.lobby.value.seats.map { it.name })
        host.startGame(seed = 1)
        withTimeoutOrNull(10.seconds) { c.view.first { it != null } } ?: error("no view after start")
    }

    @Test
    fun givenReconnectNotWelcomedYet_whenThePlayerSetsReady_thenNothingIsSentUntilTheWelcome() = runBlocking<Unit> {
        val relay = CutRelay(host.open(bindHost = LOOPBACK), cutOn = 1, hold = true).also { relays += it }
        val c = joiner()
        c.joinAndWait(relay)
        c.dropForTest()
        withTimeoutOrNull(20.seconds) { while (relay.held.count > 0) kotlinx.coroutines.delay(20) } ?: error("no second Welcome")
        assertFalse(c.ready(true), "sent before the Welcome")
        relay.release.countDown()
        withTimeoutOrNull(20.seconds) { c.status.first { it == ClientStatus.Connected } } ?: error("no Welcome: ${c.status.value}")
        assertTrue(c.ready(true), "the link is up again")
    }

    @Test
    fun givenRequestJoin_whenSocketDiesAfterAcceptBeforeWelcome_thenRetryEndsWithOneSeatAndAView() = runBlocking<Unit> {
        cutThenRetry(request = true)
    }
}
