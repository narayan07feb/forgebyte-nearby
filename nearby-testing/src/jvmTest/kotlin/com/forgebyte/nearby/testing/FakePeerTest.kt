package com.forgebyte.nearby.testing

import com.forgebyte.nearby.NearbyHost
import com.forgebyte.nearby.RoomConfig
import com.forgebyte.nearby.SeatKind
import com.forgebyte.nearby.demo.DemoGame
import com.forgebyte.nearby.demo.DemoMove
import com.forgebyte.nearby.demo.DemoView
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The fake peer and the fake host play a whole room over loopback with only public library calls. */
class FakePeerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // No turn timer: if a script did not play, the host's timer bot would finish the game and hide it.
    private val config = RoomConfig("Test room", "Host", DemoGame.VERSION, roomSecret = "s3cret", maxSeats = 2, turnTimeoutMs = 0)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun peer() = FakePeer(DemoGame.ID, DemoGame.VERSION, DemoMove.serializer(), DemoView.serializer(), scope,
        moveDelay = 10.milliseconds, script = ::firstCard)

    @Test
    fun givenFakeHostAndFakePeer_whenBothPlayByScript_thenTheGameIsPlayedToTheEnd() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val fake = FakeHost(DemoGame(), config, scope, moveDelay = 10.milliseconds, script = ::firstCard)
            val ad = fake.open()
            assertEquals("127.0.0.1", ad.host)
            assertTrue(ad.canJoin(DemoGame.VERSION))
            peer().join(fake.host, ad.port, autoAccept = false) // FakeHost accepts every request itself
            val end = fake.host.view.first { it != null && it.isOver() }!!
            assertEquals(13, end.pile.size) // seat 0 plays its 7th card on move 13: both scripts played every turn
            val seats = fake.host.lobby.value.seats
            assertEquals(listOf("Host" to SeatKind.HOST, "Test player" to SeatKind.PLAYER), seats.map { it.name to it.kind })
            fake.leave()
        }
    }

    @Test
    fun givenNoAutoAccept_whenPeerJoins_thenItWaitsForTheHostsApproval() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val host = NearbyHost(DemoGame(), config, scope)
            val port = host.open(bindHost = "127.0.0.1")
            val p = peer()
            p.join(host, port, autoAccept = false)
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertEquals("Test player", request.name)
            assertEquals(p.client.checkCode.value, request.checkCode)
            delay(300)
            assertEquals(1, host.lobby.value.seats.size) // nothing seats it but the host
            host.acceptJoin(request.id)
            host.canStart.first { it } // seated and ready by itself
            host.leave()
        }
    }

    @Test
    fun givenAutoAccept_whenHostLeaves_thenThePeersJobEnds() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val host = NearbyHost(DemoGame(), config, scope)
            val port = host.open(bindHost = "127.0.0.1")
            val job = peer().join(host, port)
            host.lobby.first { it.seats.size == 2 }
            host.leave()
            job.join() // no coroutine left running after the room is gone
        }
    }

    private companion object {
        fun DemoView.isOver() = hand.isEmpty() || otherCounts.any { it == 0 }
        fun DemoView.mySeat() = otherCounts.indexOf(-1)
        fun firstCard(v: DemoView): DemoMove? = if (v.turn == v.mySeat() && !v.isOver()) DemoMove(v.hand.first()) else null
    }
}
