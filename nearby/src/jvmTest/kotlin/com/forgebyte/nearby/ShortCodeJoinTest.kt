package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A typed short code finds the host on the joiner's network and always goes through the host's approval. */
class ShortCodeJoinTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(), scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        host.leave()
        scope.cancel()
    }

    /** A joiner whose own address is [myAddress]: on loopback 127.0.0.x, the host 127.0.0.1 is "on the same Wi-Fi". */
    private fun client(myAddress: String?) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope,
            firstConnectFor = 2.seconds).also { it.myAddresses = { listOfNotNull(myAddress) } }

    @Test
    fun givenShortCode_whenHostAccepts_thenSeatedAfterTheSameCheckCodeOnBothSides() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open(bindHost = LOOPBACK)
            val c = client("127.0.0.9")
            c.requestJoin(ShortCode.parse(ShortCode(1, port).toString())!!, "Asha")
            c.status.first { it == ClientStatus.WaitingForHost }
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertEquals("Asha", request.name)
            assertEquals(request.checkCode, c.checkCode.value)
            host.acceptJoin(request.id)
            c.status.first { it == ClientStatus.Connected }
            assertEquals(1, c.lobby.value!!.yourSeat)
            host.lobby.first { it.seats.size == 2 }
        }
    }

    @Test
    fun givenShortCode_whenHostDoesNotAnswer_thenNoSeatAndDeclineEndsTheJoin() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open(bindHost = LOOPBACK)
            val c = client("127.0.0.9")
            c.requestJoin(ShortCode(1, port), "Guess")
            c.status.first { it == ClientStatus.WaitingForHost }
            delay(1_000) // the code holds no secret: nothing seats the joiner but the host
            assertEquals(ClientStatus.WaitingForHost, c.status.value)
            assertNull(c.lobby.value)
            assertEquals(1, host.lobby.value.seats.size)
            host.declineJoin(host.joinRequests.value.single().id)
            val end = c.status.first { it is ClientStatus.Ended } as ClientStatus.Ended
            assertEquals(RefuseReason.DECLINED, (end.reason as EndReason.Refused).reason)
            assertEquals(1, host.lobby.value.seats.size)
        }
    }

    @Test
    fun givenNoWifiAddress_whenShortCodeJoin_thenEndsUnreachableAtOnce() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client(myAddress = null)
            val started = TimeSource.Monotonic.markNow()
            c.requestJoin(ShortCode(1, 40123), "Offline")
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertTrue(started.elapsedNow() < 1.seconds, "no retries without an address")
        }
    }

    @Test
    fun givenOtherNetwork_whenShortCodeJoin_thenTriesThereAndEndsUnreachable() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open(bindHost = LOOPBACK)
            val c = client("127.0.1.9") // looks for 127.0.1.1, where no room listens
            c.requestJoin(ShortCode(1, port), "Away")
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
            assertTrue(host.joinRequests.value.isEmpty())
        }
    }

    @Test
    fun givenOpenRoom_whenShortCodesAsked_thenOnePerUsableLocalAddressWithTheRoomsPort() = runBlocking<Unit> {
        val port = host.open(bindHost = LOOPBACK)
        val codes = host.shortCodes(port)
        assertEquals(localAddresses().mapNotNull { ShortCode.of(it, port) }, codes)
        codes.forEach { assertEquals(it, ShortCode.parse(it.toString())) }
    }
}
