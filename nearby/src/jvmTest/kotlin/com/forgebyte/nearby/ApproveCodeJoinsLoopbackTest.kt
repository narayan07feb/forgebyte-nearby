package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A full-code join to a room with approveCodeJoins waits for the host, with the same check code on both phones. */
class ApproveCodeJoinsLoopbackTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(approveCodeJoins = true), scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        host.leave()
        scope.cancel()
    }

    private fun client() = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)

    @Test
    fun givenApprovingRoom_whenJoinWithFullCode_thenWaitsForHostWithMatchingCheckCodeThenConnected() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open(bindHost = LOOPBACK)
            val c = client()
            c.join(JoinCode("127.0.0.1", port, Harness.SECRET, host.fingerprint!!), "Asha")
            c.status.first { it == ClientStatus.WaitingForHost }
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertEquals(request.checkCode, c.checkCode.value)
            assertEquals(1, host.lobby.value.seats.size, "no seat before the host accepts")

            host.acceptJoin(request.id)
            c.status.first { it == ClientStatus.Connected }
            assertEquals(1, c.lobby.value!!.yourSeat)
        }
    }

    @Test
    fun givenApprovingRoom_whenHostDeclinesAFullCodeJoin_thenJoinerEndsDeclined() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client()
            c.join(JoinCode("127.0.0.1", host.open(bindHost = LOOPBACK), Harness.SECRET, host.fingerprint!!), "Asha")
            host.declineJoin(host.joinRequests.first { it.isNotEmpty() }.single().id)
            val end = c.status.first { it is ClientStatus.Ended } as ClientStatus.Ended
            assertEquals(RefuseReason.DECLINED, (end.reason as EndReason.Refused).reason)
        }
    }
}
