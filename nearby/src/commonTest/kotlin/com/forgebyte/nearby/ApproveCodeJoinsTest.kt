package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RoomConfig.approveCodeJoins: a join with the full code waits for the host like a request (ceo, 2026-11-05). */
class ApproveCodeJoinsTest {
    private val h = Harness(FakeGame(), Harness.config(approveCodeJoins = true))
    private val room get() = h.room

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        return assertIs<HostMsg.Refused>(out.to(conn).single()).reason
    }

    @Test
    fun givenApprovingRoom_whenCodeJoinThenHostAccepts_thenWaitsWithoutSeatAndIsSeatedOnAccept() {
        assertEquals(listOf<HostMsg>(HostMsg.Waiting), h.hello("a", name = "Asha").to("a"), "told to wait, not closed")
        assertEquals(listOf(JoinRequest(1, "Asha", "")), room.joinRequests)
        assertEquals(1, room.seatCount)

        assertEquals(1, room.acceptJoin(1).welcome("a").lobby.yourSeat)
        assertEquals(2, room.seatCount)
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenDefaultRoom_whenCodeJoin_thenWelcomedWithoutAWaitMessage() {
        val out = Harness(FakeGame()).hello("a").to("a")
        assertIs<HostMsg.Welcome>(out.first())
        assertTrue(HostMsg.Waiting !in out)
    }

    @Test
    fun givenWaitingCodeJoin_whenItRetriesOnANewSocketWithItsJoinId_thenToldToWaitAgainAndOldSocketClosed() {
        h.hello("a", joinId = "j1")
        val out = h.hello("a2", joinId = "j1")
        assertEquals(listOf<HostMsg>(HostMsg.Waiting), out.to("a2"))
        assertTrue(out.closed("a"))
        assertEquals(1, room.joinRequests.size)
    }

    @Test
    fun givenApprovingRoom_whenHostDeclinesACodeJoin_thenRefusedDeclinedAndNoSeat() {
        h.hello("a")
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a"))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenApprovingRoom_whenWrongCode_thenRefusedAtOnceAndNeverShownToTheHost() {
        assertEquals(RefuseReason.WRONG_SECRET, refusal(h.hello("a", secret = "guess"), "a"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenAcceptedCodeJoin_whenDropsAndReconnectsWithToken_thenSameSeatWithoutANewRequest() {
        h.hello("a", name = "Asha")
        val token = room.acceptJoin(1).welcome("a").reconnectToken
        room.onDisconnect("a")
        assertEquals("Asha", h.hello("a2", token = token).welcome("a2").lobby.seats[1].name)
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenApprovingRoom_whenCodeJoinsUnanswered_thenTimeoutAndRequestCapApply() {
        repeat(4) { h.hello("c$it") }
        assertEquals(RefuseReason.TOO_MANY_REQUESTS, refusal(h.hello("c4"), "c4"))

        h.now = Harness.REQUEST_TIMEOUT
        val out = room.tick()
        repeat(4) { assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(out, "c$it")) }
        assertEquals(emptyList(), room.joinRequests)
    }
}
