package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ceo, 2026-11-03: a retry whose first socket died before its Welcome gets the same seat or request, never a second. */
class JoinIdTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room

    @Test
    fun givenCodeJoinSeatedButNoWelcome_whenRetryWithSameJoinId_thenSameSeatNoOrphan() {
        h.hello("a", name = "Asha", joinId = "J1")
        room.onDisconnect("a") // the Welcome was lost with the socket
        val out = h.hello("a2", name = "Asha", joinId = "J1")
        assertEquals(1, out.welcome("a2").lobby.yourSeat)
        assertEquals(listOf("Host", "Asha"), room.hostLobby.seats.map { it.name })
        assertTrue(room.hostLobby.seats[1].connected)
    }

    @Test
    fun givenOldSocketNotYetNoticedDead_whenRetryWithSameJoinId_thenOldSocketClosedAndSameSeat() {
        h.hello("a", joinId = "J1")
        val out = h.hello("a2", joinId = "J1")
        assertTrue(out.closed("a"))
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenDifferentJoinIds_whenTwoPlayersJoin_thenTwoSeats() {
        h.hello("a", name = "Asha", joinId = "J1")
        h.hello("b", name = "Asha", joinId = "J2") // same name, another player
        assertEquals(3, room.seatCount)
    }

    @Test
    fun givenTokenAndJoinId_whenReconnecting_thenTheTokenDecides() {
        h.hello("a", joinId = "J1")
        h.hello("b", joinId = "J2")
        room.onDisconnect("b")
        val out = h.hello("b2", token = "tok2", joinId = "J1") // b's token, a's join id
        assertEquals(2, out.welcome("b2").lobby.yourSeat)
    }

    @Test
    fun givenGameStartedBeforeTheRetry_whenRetryWithSameJoinId_thenSeatAndCurrentView() {
        h.hello("a", joinId = "J1"); room.onMessage("a", ClientMsg.Ready(true))
        room.start(seed = 3)
        room.onDisconnect("a")
        val out = h.hello("a2", joinId = "J1")
        assertEquals(1, out.welcome("a2").lobby.yourSeat)
        assertEquals(room.state!!.hands[1], out.views("a2").single().hand)
    }

    @Test
    fun givenRequestWaiting_whenRetryArrivesBeforeTheOldSocketIsNoticedDead_thenOneEntrySameIdAndApprovalReachesTheRetry() {
        h.request("a", name = "Asha", joinId = "J1")
        val out = h.request("a2", name = "Asha", joinId = "J1")
        assertTrue(out.closed("a"), "the old half-open socket is closed")
        assertEquals(listOf(1), room.joinRequests.map { it.id }, "one entry, same id")
        val accepted = room.acceptJoin(1)
        assertEquals(1, accepted.welcome("a2").lobby.yourSeat)
        assertEquals(listOf("Host", "Asha"), room.hostLobby.seats.map { it.name })
    }

    @Test
    fun givenRequestWaiting_whenOldSocketDiesFirstThenRetry_thenStillOneEntry() {
        h.request("a", name = "Asha", joinId = "J1")
        room.onDisconnect("a")
        h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(1, room.joinRequests.size)
        assertEquals(1, room.acceptJoin(room.joinRequests.single().id).welcome("a2").lobby.yourSeat)
    }

    @Test
    fun givenRequestAcceptedButNoWelcome_whenRetryWithSameJoinId_thenSameSeat() {
        h.request("a", name = "Asha", joinId = "J1")
        room.acceptJoin(1)
        room.onDisconnect("a")
        val out = h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(1, out.welcome("a2").lobby.yourSeat)
        assertEquals(2, room.seatCount)
        assertEquals(emptyList(), room.joinRequests)
    }
}
