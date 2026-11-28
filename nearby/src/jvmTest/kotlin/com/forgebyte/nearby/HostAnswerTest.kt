package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What NearbyHost.acceptRequest and declineRequest return, over loopback, when the room changed under the request. */
class HostAnswerTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = mutableListOf<NearbyHost<FakeState, FakeMove, FakeView>>()

    @AfterTest
    fun tearDown() = runBlocking {
        hosts.forEach { runCatching { withTimeoutOrNull(10.seconds) { it.leave() } } } // a test may have left already
        scope.cancel()
    }

    private fun host(config: RoomConfig = Harness.config()) = NearbyHost(FakeGame(), config, scope).also { hosts += it }

    private fun client() = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)

    private suspend fun NearbyHost<*, *, *>.ask(port: Int, name: String = "Asha"): Pair<NearbyClient<FakeMove, FakeView>, JoinRequest> {
        val c = client()
        c.requestJoin(RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, LOOPBACK, port), name)
        return c to joinRequests.first { r -> r.any { it.name == name } }.single { it.name == name }
    }

    private suspend fun NearbyClient<*, *>.refusal(): RefuseReason? =
        ((withTimeoutOrNull(10.seconds) { status.first { it is ClientStatus.Ended } } as? ClientStatus.Ended)?.reason as? EndReason.Refused)?.reason

    @Test
    fun givenRoomFilledMeanwhile_whenHostAccepts_thenTrueAndTheJoinerIsRefusedAsFull() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val h = host(Harness.config(maxSeats = 2))
            val port = h.open(bindHost = LOOPBACK)
            val (c, request) = h.ask(port)
            val bo = client()
            bo.join(JoinCode(LOOPBACK, port, Harness.SECRET, h.fingerprint!!), "Bo") // the full code takes the last seat
            bo.status.first { it == ClientStatus.Connected }
            assertTrue(h.acceptRequest(request.id, request.checkCode), "the call took the request")
            assertEquals(RefuseReason.ROOM_FULL, c.refusal())
            assertEquals(emptyList(), h.joinRequests.value)
        }
    }

    @Test
    fun givenGameStarted_whenHostAnswersAnOldRequest_thenFalse() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val h = host()
            val port = h.open(bindHost = LOOPBACK)
            val (c, request) = h.ask(port)
            h.addBot()
            h.startGame(seed = 1)
            assertEquals(RefuseReason.GAME_STARTED, c.refusal())
            assertFalse(h.acceptRequest(request.id, request.checkCode))
            assertFalse(h.declineRequest(request.id, request.checkCode))
        }
    }

    @Test
    fun givenRoomClosed_whenHostAnswers_thenFalse() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val h = host()
            val port = h.open(bindHost = LOOPBACK)
            val (_, request) = h.ask(port)
            h.leave()
            assertFalse(h.acceptRequest(request.id, request.checkCode))
            assertFalse(h.declineRequest(request.id, request.checkCode))
        }
    }

    @Test
    fun givenRequestTimedOutByTheRoomClock_whenHostAcceptsLater_thenFalse() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val h = host(Harness.config(requestTimeoutMs = 3_000)) // room for the request to show before it runs out
            val port = h.open(bindHost = LOOPBACK)
            val (c, request) = h.ask(port)
            assertEquals(RefuseReason.REQUEST_TIMED_OUT, c.refusal(), "the room's clock timed it out first")
            assertFalse(h.acceptRequest(request.id, request.checkCode))
            assertEquals(listOf("Host"), h.lobby.value.seats.map { it.name })
        }
    }

    @Test
    fun givenRequest_whenHostDeclinesIt_thenTrueAndTheJoinerIsToldDeclined() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val h = host()
            val port = h.open(bindHost = LOOPBACK)
            val (c, request) = h.ask(port)
            assertTrue(h.declineRequest(request.id, request.checkCode))
            assertEquals(RefuseReason.DECLINED, c.refusal())
        }
    }
}
