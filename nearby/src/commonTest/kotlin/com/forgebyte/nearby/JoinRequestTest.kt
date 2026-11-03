package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Owner, 2026-11-03: a room found nearby is joined by request and the host accepts or declines each one. */
class JoinRequestTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        return assertIs<HostMsg.Refused>(out.to(conn).single()).reason
    }

    @Test
    fun givenRequest_whenSent_thenHostSeesItAndRequesterWaitsWithoutSeat() {
        val out = h.request("a", name = "  Asha ")

        assertEquals(emptyList(), out, "nothing is sent until the host answers")
        assertEquals(listOf(JoinRequest(1, "Asha")), room.joinRequests)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRequest_whenHostAccepts_thenWelcomedAndSeated() {
        h.request("a", name = "Asha")
        val out = room.acceptJoin(1)

        val welcome = out.welcome("a")
        assertEquals(1, welcome.lobby.yourSeat)
        assertEquals("Asha", welcome.lobby.seats[1].name)
        assertEquals(2, out.lastLobby(HOST_CONN).seats.size)
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenRequest_whenHostDeclines_thenRefusedDeclinedAndNoSeat() {
        h.request("a")
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a"))
        assertEquals(emptyList(), room.joinRequests)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRequest_whenHostNeverAnswers_thenTimedOutAtTheLimit() {
        h.request("a")
        h.now = Harness.REQUEST_TIMEOUT - 1
        assertEquals(emptyList(), room.tick())
        assertEquals(1, room.joinRequests.size)

        h.now = Harness.REQUEST_TIMEOUT
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(room.tick(), "a"))
        assertEquals(emptyList(), room.joinRequests)
        assertEquals(emptyList(), room.acceptJoin(1), "a timed-out request cannot be accepted")
    }

    @Test
    fun givenRequestFlood_whenOverTheCap_thenExtraRequestsRefusedAndListStaysCapped() {
        repeat(4) { h.request("r$it") }
        assertEquals(RefuseReason.TOO_MANY_REQUESTS, refusal(h.request("r4"), "r4"))
        assertEquals(4, room.joinRequests.size)
    }

    @Test
    fun givenPendingConnection_whenItSendsHelloAgainOrOtherMessages_thenIgnored() {
        h.request("a")
        assertEquals(emptyList(), h.request("a"))
        assertEquals(emptyList(), room.onMessage("a", ClientMsg.Ready(true)))
        assertEquals(emptyList(), room.onMessage("a", move("C0Z")))
        assertEquals(1, room.joinRequests.size)
    }

    @Test
    fun givenPendingRequester_whenDisconnects_thenRequestGone() {
        h.request("a")
        room.onDisconnect("a")
        assertEquals(emptyList(), room.joinRequests)
        assertEquals(emptyList(), room.acceptJoin(1))
    }

    @Test
    fun givenRequestsPending_whenHostLeaves_thenEachRequesterToldAndClosed() {
        h.request("a"); h.request("b")
        val out = room.hostLeave()
        listOf("a", "b").forEach {
            assertEquals(listOf<HostMsg>(HostMsg.RoomClosed(CloseReason.HOST_LEFT)), out.to(it))
            assertTrue(out.closed(it))
        }
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenRequestsPending_whenGameStarts_thenRequestersRefusedGameStarted() {
        room.addBot()
        h.request("a")
        assertEquals(RefuseReason.GAME_STARTED, refusal(room.start(1), "a"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenRoomFilledWhileWaiting_whenHostAccepts_thenRefusedRoomFull() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.request("a")
        small.hello("b") // code join takes the last seat
        val out = small.room.acceptJoin(1)
        assertTrue(out.closed("a"))
        assertEquals(RefuseReason.ROOM_FULL, (out.to("a").single() as HostMsg.Refused).reason)
    }

    @Test
    fun givenFullRoom_whenRequest_thenRefusedAtOnce() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.hello("b")
        assertEquals(RefuseReason.ROOM_FULL, refusal(small.request("a"), "a"))
    }

    @Test
    fun givenOlderApp_whenRequests_thenRefusedBeforeReachingHost() {
        assertEquals(RefuseReason.GAME_VERSION_MISMATCH, refusal(h.request("a", gameVersion = 1), "a"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenCodeJoin_whenJoins_thenSeatedWithoutAnyRequest() {
        h.hello("a")
        assertEquals(emptyList(), room.joinRequests)
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenSeatJoinedByRequest_whenDropsAndReconnectsWithTokenOnly_thenSameSeat() {
        h.request("a", name = "Asha")
        val token = room.acceptJoin(1).welcome("a").reconnectToken
        room.onDisconnect("a")
        val out = h.hello("a2", token = token, secret = null)
        assertEquals("Asha", out.welcome("a2").lobby.seats[1].name)
        assertEquals(emptyList(), room.joinRequests, "a reconnect is not a new request")
    }

    @Test
    fun givenUnknownRequestId_whenAnswered_thenNothing() {
        assertEquals(emptyList(), room.acceptJoin(42))
        assertEquals(emptyList(), room.declineJoin(42))
    }
}
