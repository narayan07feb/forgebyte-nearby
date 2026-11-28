package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host answers a join request with the id and the check code it compared. A retry of the same join (same join id)
 * moves the request to a new connection with a new code under the same id; an answer with the old code must take
 * nothing, so the host never seats or declines a connection whose code it did not see.
 */
class AnswerChecksCodeTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        return assertIs<HostMsg.Refused>(out.to(conn).single()).reason
    }

    /** Request 1 from "a" (code-a), then a retry from "a2" with the same join id: request 1 now shows code-a2. */
    private fun retriedRequest() {
        h.request("a", name = "Asha", joinId = "J1")
        h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(1, "Asha", "code-a2")), room.joinRequests)
    }

    @Test
    fun givenRetryMovedTheRequest_whenHostAcceptsWithTheOldCode_thenNothingIsTakenAndTheNewCodeSeatsIt() {
        retriedRequest()
        assertEquals(emptyList<RoomOutput>() to false, room.answerRequest(1, "code-a", accept = true), "nothing sent, not taken")
        assertEquals(1, room.seatCount)
        assertEquals(listOf(JoinRequest(1, "Asha", "code-a2")), room.joinRequests, "the host sees the new code")
        assertEquals(1, room.acceptRequest(1, "code-a2").welcome("a2").lobby.yourSeat)
    }

    @Test
    fun givenRetryMovedTheRequest_whenHostDeclinesWithTheOldCode_thenNothingIsTakenAndTheNewCodeDeclinesIt() {
        retriedRequest()
        assertEquals(emptyList<RoomOutput>() to false, room.answerRequest(1, "code-a", accept = false))
        assertEquals(listOf(JoinRequest(1, "Asha", "code-a2")), room.joinRequests)
        assertEquals(RefuseReason.DECLINED, refusal(room.declineRequest(1, "code-a2"), "a2"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenRetryShowsTheSameCode_whenItArrives_thenTheRequestIsDroppedAndTheNextTryIsANewRequest() {
        val same = Harness(FakeGame(), checkCodeOf = { "123456" }) // two key exchanges that gave the same six digits
        same.request("a", name = "Asha", joinId = "J1")
        val out = same.request("a2", name = "Asha", joinId = "J1")
        assertTrue(out.closed("a2"), "the retry is closed, so the joiner tries again")
        assertEquals(emptyList(), same.room.joinRequests)
        assertEquals(emptyList<RoomOutput>() to false, same.room.answerRequest(1, "123456", accept = true), "the old answer takes nothing")
        same.request("a3", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(2, "Asha", "123456")), same.room.joinRequests, "a new request, a new id")
        assertEquals(emptyList(), same.room.acceptRequest(1, "123456"))
        assertEquals(1, same.room.acceptRequest(2, "123456").welcome("a3").lobby.yourSeat)
    }

    @Test
    fun givenRetriesShowCodesAThenBThenA_whenTheHostAnswersForTheFirstConnection_thenNothingIsTaken() {
        val codes = mapOf("a" to "111111", "a2" to "222222", "a3" to "111111")
        for (accept in listOf(true, false)) {
            val abA = Harness(FakeGame(), checkCodeOf = { codes.getValue(it) })
            abA.request("a", name = "Asha", joinId = "J1")
            abA.request("a2", name = "Asha", joinId = "J1")
            assertEquals(listOf(JoinRequest(1, "Asha", "222222")), abA.room.joinRequests)
            val out = abA.request("a3", name = "Asha", joinId = "J1")
            assertTrue(out.closed("a3"), "a code the request showed before: the retry is closed")
            assertEquals(emptyList(), abA.room.joinRequests)
            assertEquals(emptyList<RoomOutput>() to false, abA.room.answerRequest(1, "111111", accept), "accept=$accept")
            assertEquals(1, abA.room.seatCount)
        }
    }

    @Test
    fun givenRetriesShowCodesABCThenB_whenTheHostAnswersForTheSecondConnection_thenNothingIsTaken() {
        val codes = mapOf("a" to "111111", "a2" to "222222", "a3" to "333333", "a4" to "222222")
        val abcb = Harness(FakeGame(), checkCodeOf = { codes.getValue(it) })
        for (conn in listOf("a", "a2", "a3")) abcb.request(conn, name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(1, "Asha", "333333")), abcb.room.joinRequests, "every new code moves it")
        assertTrue(abcb.request("a4", name = "Asha", joinId = "J1").closed("a4"), "a middle code again: dropped")
        assertEquals(emptyList<RoomOutput>() to false, abcb.room.answerRequest(1, "222222", accept = true))
        assertEquals(1, abcb.room.seatCount)
    }

    @Test
    fun givenEmptyCodes_whenARetryMovesTheRequest_thenItMovesAsBefore() {
        val none = Harness(FakeGame(), checkCodeOf = { "" }) // a Room with no secure channel: no code, no check
        none.request("a", name = "Asha", joinId = "J1")
        none.request("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(1, "Asha", "")), none.room.joinRequests)
        assertEquals(1, none.room.acceptRequest(1, "").welcome("a2").lobby.yourSeat)
    }

    @Test
    fun givenRequest_whenHostAnswersWithACodeItNeverShowed_thenNothingIsTaken() {
        h.request("a", name = "Asha")
        assertEquals(emptyList(), room.acceptRequest(1, "000000"))
        assertEquals(emptyList(), room.declineRequest(1, "000000"))
        assertEquals(1, room.seatCount)
        assertEquals(listOf(1), room.joinRequests.map { it.id })
    }

    @Test
    fun givenRequestTimedOut_whenHostAnswersItWithoutATickFirst_thenItIsRefusedAsTimedOutAndNotTaken() {
        h.request("a", name = "Asha")
        h.now = Harness.REQUEST_TIMEOUT
        val (out, took) = room.answerRequest(1, "code-a", accept = true)
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(out, "a"), "the expiry in the same call refuses it")
        assertFalse(took)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRoomFilledMeanwhile_whenHostAccepts_thenTheRequestIsTakenAndRefusedAsFull() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.request("a", name = "Asha")
        small.hello("b", name = "Bo") // the full code takes the last seat at once
        val (out, took) = small.room.answerRequest(1, "code-a", accept = true)
        assertEquals(RefuseReason.ROOM_FULL, refusal(out, "a"))
        assertTrue(took)
    }

    @Test
    fun givenGameStartedOrRoomClosed_whenHostAnswers_thenNothingIsTaken() {
        h.request("a", name = "Asha")
        room.addBot()
        room.start(seed = 1)
        assertEquals(Phase.PLAYING, room.phase)
        assertEquals(emptyList<RoomOutput>() to false, room.answerRequest(1, "code-a", accept = true), "starting refused it already")

        val closed = Harness(FakeGame())
        closed.request("a", name = "Asha")
        closed.room.hostLeave()
        assertEquals(emptyList<RoomOutput>() to false, closed.room.answerRequest(1, "code-a", accept = true))
    }

    @Test
    fun givenRequest_whenAnswered_thenTookIsTrueOnlyForTheCallThatTookIt() {
        h.request("a", name = "Asha")
        assertFalse(room.answerRequest(1, "code-b", accept = true).second, "a wrong code takes nothing")
        assertTrue(room.answerRequest(1, "code-a", accept = false).second)
        assertFalse(room.answerRequest(1, "code-a", accept = false).second, "it is gone now")
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRetryMovedTheRequest_whenTheDeprecatedAcceptIsCalled_thenItStillSeatsById() {
        retriedRequest()
        assertEquals(1, room.acceptJoin(1).welcome("a2").lobby.yourSeat)
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRequest_whenTheDeprecatedDeclineIsCalled_thenItStillDeclinesById() {
        h.request("a", name = "Asha")
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a"))
        assertTrue(room.joinRequests.isEmpty())
    }
}
