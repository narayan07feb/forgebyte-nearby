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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** PLAN test 3: one host and three clients over localhost WebSockets. */
class LoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(), scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        host.leave()
        scope.cancel()
    }

    private fun client(gameVersion: Int = Harness.GAME_VERSION) =
        NearbyClient("fake", gameVersion, FakeMove.serializer(), FakeView.serializer(), scope)

    private suspend fun joined(port: Int, n: Int) = (1..n).map { i ->
        client().also {
            it.join(JoinCode("127.0.0.1", port, Harness.SECRET), "P$i")
            val s = it.status.first { s -> s == ClientStatus.Connected || s is ClientStatus.Ended }
            assertEquals(ClientStatus.Connected, s, "P$i")
        }
    }

    @Test
    fun givenHostAndThreeClients_whenFullMatchWithADrop_thenEveryoneEndsOnTheHostsState() = runBlocking<Unit> {
        withTimeout(60.seconds) {
            val port = host.open()
            val clients = joined(port, 3)
            host.lobby.first { it.seats.size == 4 }
            clients.forEach { it.ready(true) }
            host.canStart.first { it }

            // Every seat plays its first card when it is its turn; a reconnect re-checks the turn.
            clients.forEach { c ->
                scope.launch {
                    combine(c.view.filterNotNull(), c.status) { v, s -> v to s }.collect { (v, s) ->
                        if (s == ClientStatus.Connected && v.turn == c.lobby.value!!.yourSeat && v.hand.isNotEmpty()) {
                            c.move(FakeMove(v.hand.first()))
                        }
                    }
                }
            }
            scope.launch {
                host.view.filterNotNull().collect { v ->
                    if (v.turn == 0 && v.hand.isNotEmpty()) host.move(FakeMove(v.hand.first()))
                }
            }
            val seat1Statuses = mutableListOf<ClientStatus>()
            scope.launch { clients[0].status.collect { seat1Statuses += it } }
            scope.launch { // Wi-Fi drop on seat 1 mid-game
                host.view.filterNotNull().first { it.pile.size >= 5 }
                clients[0].dropForTest()
            }
            host.startGame(seed = 11)

            host.lobby.first { it.phase == Phase.OVER }
            assertTrue(ClientStatus.Reconnecting in seat1Statuses, "seat 1 really dropped and came back: $seat1Statuses")
            val final = host.view.value!!
            clients.forEach { c ->
                val v = c.view.first { it?.pile == final.pile }!!
                assertEquals(final.turn, v.turn)
                assertEquals(final.otherCounts[c.lobby.value!!.yourSeat], v.hand.size)
                assertEquals(SeatKind.PLAYER, c.lobby.first { it?.phase == Phase.OVER }!!.seats[c.lobby.value!!.yourSeat].kind)
            }
        }
    }

    @Test
    fun givenClients_whenHostLeaves_thenEveryClientEndsHostLeft() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val clients = joined(host.open(), 2)
            host.leave()
            clients.forEach {
                assertEquals(ClientStatus.Ended(EndReason.HostLeft), it.status.first { s -> s is ClientStatus.Ended })
            }
        }
    }

    @Test
    fun givenOlderApp_whenJoins_thenEndsRefusedWithVersionMismatch() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open()
            val old = client(gameVersion = Harness.GAME_VERSION - 1)
            old.join(JoinCode("127.0.0.1", port, Harness.SECRET), "Old")
            val end = old.status.first { it is ClientStatus.Ended } as ClientStatus.Ended
            assertEquals(RefuseReason.GAME_VERSION_MISMATCH, (end.reason as EndReason.Refused).reason)
            assertEquals(1, host.lobby.value.seats.size)
        }
    }

    @Test
    fun givenKick_whenHostKicksClient_thenClientEndsKicked() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val (c) = joined(host.open(), 1)
            host.kick(1)
            assertEquals(ClientStatus.Ended(EndReason.Kicked), c.status.first { it is ClientStatus.Ended })
            host.lobby.first { it.seats.size == 1 }
        }
    }

    private fun ad(port: Int) = RoomAd("k", "Den", "fake", PROTOCOL_VERSION, Harness.GAME_VERSION, 3, "127.0.0.1", port)

    @Test
    fun givenFoundRoom_whenHostAcceptsRequest_thenClientSeated() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client()
            c.requestJoin(ad(host.open()), "Asha")
            c.status.first { it == ClientStatus.WaitingForHost }
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertEquals("Asha", request.name)
            host.acceptJoin(request.id)
            c.status.first { it == ClientStatus.Connected }
            assertEquals(1, c.lobby.value!!.yourSeat)
            host.lobby.first { it.seats.size == 2 }
        }
    }

    @Test
    fun givenFoundRoom_whenHostDeclines_thenClientEndsDeclined() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client()
            c.requestJoin(ad(host.open()), "Asha")
            host.declineJoin(host.joinRequests.first { it.isNotEmpty() }.single().id)
            val end = c.status.first { it is ClientStatus.Ended } as ClientStatus.Ended
            assertEquals(RefuseReason.DECLINED, (end.reason as EndReason.Refused).reason)
            assertEquals(1, host.lobby.value.seats.size)
        }
    }

    @Test
    fun givenNoHost_whenJoins_thenEndsUnreachable() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client()
            c.join(JoinCode("127.0.0.1", 1, Harness.SECRET), "Lost")
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), c.status.first { it is ClientStatus.Ended })
        }
    }

    @Test
    fun givenJoinCode_whenParsed_thenRoundTripsAndJunkIsNull() {
        val code = JoinCode("192.168.1.7", 40123, "abc")
        assertEquals(code, JoinCode.parse(code.toString()))
        listOf("", "1.2.3.4", "1.2.3.4:0:x", "1.2.3.4:99999:x", ":5:x", "1.2.3.4:5:").forEach {
            assertEquals(null, JoinCode.parse(it), it)
        }
    }
}
