package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host answers a join request with the id and the check code it saw. A retry of the same join (same join id) that
 * moves the request to a new connection gives it a new id and a new code, so an answer for what the host saw before
 * never seats or declines the new connection, even when the two codes are equal.
 */
class AnswerChecksCodeTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        return assertIs<HostMsg.Refused>(out.to(conn).single()).reason
    }

    /** Request 1 from "a" (code-a), then a retry from "a2" with the same join id: it is now request 2 with code-a2. */
    private fun retriedRequest() {
        h.request("a", name = "Asha", joinId = "J1")
        h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), room.joinRequests)
    }

    @Test
    fun givenRetryMovedTheRequest_whenHostAcceptsWhatItSawBefore_thenNobodyIsSeatedAndTheNewRequestStays() {
        retriedRequest()
        assertEquals(emptyList(), room.acceptRequest(1, "code-a"), "nothing is sent to the new connection")
        assertFalse(room.answered)
        assertEquals(1, room.seatCount)
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), room.joinRequests, "the host sees the new request")
        assertEquals(emptyList(), room.acceptRequest(1, "code-a2"), "the old id with the new code does nothing either")
        assertEquals(1, room.acceptRequest(2, "code-a2").welcome("a2").lobby.yourSeat, "the new request seats it")
    }

    @Test
    fun givenRetryMovedTheRequest_whenHostDeclinesWhatItSawBefore_thenNothingIsSentAndTheNewRequestStays() {
        retriedRequest()
        assertEquals(emptyList(), room.declineRequest(1, "code-a"))
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), room.joinRequests)
        assertEquals(RefuseReason.DECLINED, refusal(room.declineRequest(2, "code-a2"), "a2"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenEveryConnectionShowsTheSameCode_whenARetryMovesTheRequest_thenTheOldAnswerStillDoesNothing() {
        val same = Harness(FakeGame(), checkCodeOf = { "" }) // a Room used without a secure channel
        same.request("a", name = "Asha", joinId = "J1")
        same.request("a2", name = "Asha", joinId = "J1")
        assertEquals(emptyList(), same.room.acceptRequest(1, ""), "the id tells the two connections apart")
        assertEquals(1, same.room.seatCount)
        assertEquals(1, same.room.acceptRequest(2, "").welcome("a2").lobby.yourSeat)
    }

    @Test
    fun givenOldSocketDiedFirst_whenTheRetryArrives_thenItIsANewRequestAndTheOldPairDoesNothing() {
        h.request("a", name = "Asha", joinId = "J1")
        room.onDisconnect("a")
        h.request("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), room.joinRequests)
        assertEquals(emptyList(), room.acceptRequest(1, "code-a"))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRetryMovedTheRequest_whenTheOldSocketClosesLate_thenTheNewRequestStays() {
        retriedRequest()
        assertEquals(emptyList(), room.onDisconnect("a"))
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), room.joinRequests)
    }

    @Test
    fun givenApprovingRoom_whenACodeJoinRetriesBeforeTheAnswer_thenTheOldAnswerDoesNothing() {
        val approving = Harness(FakeGame(), Harness.config(approveCodeJoins = true))
        approving.hello("a", name = "Asha", joinId = "J1")
        approving.hello("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), approving.room.joinRequests)
        assertEquals(emptyList(), approving.room.acceptRequest(1, "code-a"))
        assertEquals(1, approving.room.seatCount)
    }

    @Test
    fun givenRequest_whenHostAnswersWithACodeItNeverShowed_thenNothingHappens() {
        h.request("a", name = "Asha")
        assertEquals(emptyList(), room.acceptRequest(1, "000000"))
        assertEquals(emptyList(), room.declineRequest(1, "000000"))
        assertEquals(1, room.seatCount)
        assertEquals(listOf(1), room.joinRequests.map { it.id })
    }

    @Test
    fun givenRequestTimedOut_whenHostAnswersItWithoutATickFirst_thenItIsRefusedAsTimedOutAndNotAnswered() {
        h.request("a", name = "Asha")
        h.now = Harness.REQUEST_TIMEOUT
        val out = room.acceptRequest(1, "code-a")
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(out, "a"), "the expiry in the same call refuses it")
        assertFalse(room.answered)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRoomFilledMeanwhile_whenHostAccepts_thenTheRequestIsAnsweredAsFull() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.request("a", name = "Asha")
        small.hello("b", name = "Bo") // the full code takes the last seat at once
        assertEquals(RefuseReason.ROOM_FULL, refusal(small.room.acceptRequest(1, "code-a"), "a"))
        assertTrue(small.room.answered, "the call took the request off the list")
    }

    @Test
    fun givenGameStartedOrRoomClosed_whenHostAnswers_thenNothingIsAnswered() {
        h.request("a", name = "Asha")
        room.addBot()
        room.start(seed = 1)
        assertEquals(Phase.PLAYING, room.phase)
        assertEquals(emptyList(), room.acceptRequest(1, "code-a"))
        assertFalse(room.answered, "starting refused the request already")

        val closed = Harness(FakeGame())
        closed.request("a", name = "Asha")
        closed.room.hostLeave()
        assertEquals(emptyList(), closed.room.acceptRequest(1, "code-a"))
        assertFalse(closed.room.answered)
    }

    @Test
    fun givenRequest_whenAnswered_thenAnsweredIsTrueOnlyForTheCallThatTookIt() {
        h.request("a", name = "Asha")
        room.acceptRequest(1, "code-b")
        assertFalse(room.answered, "a wrong code answers nothing")
        room.acceptRequest(1, "code-a")
        assertTrue(room.answered)
        room.tick()
        assertFalse(room.answered, "the next room call starts again from false")
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRequest_whenTheDeprecatedAcceptIsCalled_thenItStillSeatsById() {
        h.request("a", name = "Asha")
        assertEquals(1, room.acceptJoin(1).welcome("a").lobby.yourSeat)
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRetryMovedTheRequest_whenTheDeprecatedAcceptUsesTheOldId_thenNobodyIsSeated() {
        retriedRequest()
        assertEquals(emptyList(), room.acceptJoin(1))
        assertEquals(1, room.seatCount)
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRequest_whenTheDeprecatedDeclineIsCalled_thenItStillDeclinesById() {
        h.request("a", name = "Asha")
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a"))
        assertTrue(room.joinRequests.isEmpty())
    }
}
