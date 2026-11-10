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
    fun givenSeatThatNeverSentAMessage_whenHostTriesToStart_thenNotStartableAndTheRetryReclaimsInTheLobby() {
        // Ready needs a message, and any message ends the join id: so a join-id reclaim only ever happens in the lobby.
        h.hello("a", joinId = "J1")
        room.onDisconnect("a")
        assertEquals(false, room.canStart)
        h.hello("a2", joinId = "J1")
        room.onMessage("a2", ClientMsg.Ready(true))
        assertEquals(true, room.canStart)
        assertEquals(2, room.seatCount)
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

    // ceo security finding, 2026-11-03: a join id must never bring back a seat that was kicked or left, and it is a
    // credential only until the Welcome has arrived (the seat's first message, or a token reconnect).

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        assertTrue(out.to(conn).none { it is HostMsg.Welcome || it is HostMsg.View }, "no Welcome, no view: ${out.to(conn)}")
        return (out.to(conn).single() as HostMsg.Refused).reason
    }

    private fun startWithA() {
        h.hello("a", joinId = "J1"); room.onMessage("a", ClientMsg.Ready(true))
        room.start(seed = 3)
    }

    @Test
    fun givenPlayerKickedMidGame_whenTheirJoinIdComesBack_thenRefusedAndNoViewOfTheBotSeat() {
        startWithA()
        room.kick(1)
        assertEquals(SeatKind.BOT, room.hostLobby.seats[1].kind)
        assertEquals(RefuseReason.GAME_STARTED, refusal(h.hello("a2", joinId = "J1"), "a2"))
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a3", token = "tok1"), "a3"), "nor with the token")
    }

    @Test
    fun givenPlayerLeftMidGame_whenTheirJoinIdComesBack_thenRefused() {
        startWithA()
        room.onMessage("a", ClientMsg.Leave)
        assertEquals(RefuseReason.GAME_STARTED, refusal(h.hello("a2", joinId = "J1"), "a2"))
    }

    @Test
    fun givenPlayerKickedInTheLobbyBeforeAnyMessage_whenTheirJoinIdComesBack_thenItReclaimsNothing() {
        h.hello("a", name = "Asha", joinId = "J1") // join id still live: no message yet
        room.kick(1)
        val out = h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(emptyList(), out, "just a new request for the host to decide on: no seat, no Welcome")
        assertEquals(1, room.seatCount)
        assertEquals(1, room.joinRequests.size)
    }

    @Test
    fun givenSeatHasSentAMessage_whenItsJoinIdIsUsed_thenItIsJustANewJoin() {
        h.hello("a", name = "Asha", joinId = "J1")
        room.onMessage("a", ClientMsg.Ready(true)) // proves the Welcome arrived
        room.onDisconnect("a")
        assertEquals(emptyList(), h.request("a2", name = "Asha", joinId = "J1"), "not a reclaim: a new request")
        assertEquals(1, room.joinRequests.size)
        assertEquals(false, room.hostLobby.seats[1].connected, "the old seat is not handed to the join id")
    }

    @Test
    fun givenKickedRightAfterAReconnect_whenTheHeldTokenComesBack_thenRefused() {
        startWithA()
        room.onDisconnect("a")
        h.hello("a2", token = "tok1")
        room.kick(1)
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a3", token = "tok1"), "a3"))
    }

    @Test
    fun givenSilentReconnectThenBotTakeover_whenKickedInTheGame_thenNeitherTokenComesBack() {
        startWithA()
        room.onDisconnect("a")
        h.hello("a2", token = "tok1") // tok2 goes out; the joiner stays silent, so tok1 is still held
        room.onDisconnect("a2")
        h.now = Harness.WINDOW
        room.tick() // a bot takes the seat over; both tokens could still bring the player back
        room.kick(1)
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a3", token = "tok1"), "a3"))
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a4", token = "tok2"), "a4"))
        assertEquals(SeatKind.BOT, room.hostLobby.seats[1].kind)
    }

    @Test
    fun givenTokenReconnect_whenTheJoinIdIsUsedAfterwards_thenItIsJustANewJoin() {
        h.hello("a", joinId = "J1")
        room.onDisconnect("a")
        h.hello("a2", token = "tok1") // proves the Welcome arrived
        room.onDisconnect("a2")
        assertEquals(emptyList(), h.request("a3", joinId = "J1"))
        assertEquals(1, room.joinRequests.size)
    }
}
