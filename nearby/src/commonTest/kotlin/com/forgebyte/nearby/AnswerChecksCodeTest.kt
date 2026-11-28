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
        assertEquals(emptyList<RoomOutput>() to false, room.answerRequest(1, "code-a", accept = true), "nothing sent, not taken")
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
    fun givenEveryConnectionShowsTheSameCode_whenARetryMovesTheRequest_thenTheOldAcceptOrDeclineStillDoesNothing() {
        val same = Harness(FakeGame(), checkCodeOf = { "" }) // a Room used without a secure channel
        same.request("a", name = "Asha", joinId = "J1")
        same.request("a2", name = "Asha", joinId = "J1")
        assertEquals(emptyList(), same.room.acceptRequest(1, ""), "the id tells the two connections apart")
        assertEquals(emptyList(), same.room.declineRequest(1, ""))
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
    fun givenApprovingRoom_whenACodeJoinRetriesBeforeTheAnswer_thenTheOldAcceptOrDeclineDoesNothing() {
        val approving = Harness(FakeGame(), Harness.config(approveCodeJoins = true))
        approving.hello("a", name = "Asha", joinId = "J1")
        approving.hello("a2", name = "Asha", joinId = "J1")
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a2")), approving.room.joinRequests)
        assertEquals(emptyList(), approving.room.acceptRequest(1, "code-a"))
        assertEquals(emptyList(), approving.room.declineRequest(1, "code-a"))
        assertEquals(listOf(2), approving.room.joinRequests.map { it.id })
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
        val (out, took) = room.answerRequest(1, "code-a", accept = true)
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(out, "a"), "the expiry in the same call refuses it")
        assertFalse(took)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenRoomFilledMeanwhile_whenHostAccepts_thenTheRequestIsAnsweredAsFull() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.request("a", name = "Asha")
        small.hello("b", name = "Bo") // the full code takes the last seat at once
        val (out, took) = small.room.answerRequest(1, "code-a", accept = true)
        assertEquals(RefuseReason.ROOM_FULL, refusal(out, "a"))
        assertTrue(took, "the call took the request off the list")
    }

    @Test
    fun givenGameStartedOrRoomClosed_whenHostAnswers_thenNothingIsAnswered() {
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
    fun givenRequest_whenTheDeprecatedAcceptIsCalled_thenItStillSeatsById() {
        h.request("a", name = "Asha")
        assertEquals(1, room.acceptJoin(1).welcome("a").lobby.yourSeat)
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRetryMovedTheRequest_whenTheDeprecatedAcceptUsesTheOldId_thenItStillSeatsAsBefore() {
        retriedRequest()
        assertEquals(1, room.acceptJoin(1).welcome("a2").lobby.yourSeat, "an id cached before the move still answers")
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRetryMovedTheRequest_whenTheDeprecatedDeclineUsesTheOldId_thenItStillDeclinesAsBefore() {
        retriedRequest()
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a2"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRequestMovedTwice_whenTheDeprecatedAcceptUsesTheMiddleId_thenItStillSeats() {
        retriedRequest()
        h.request("a3", name = "Asha", joinId = "J1")
        assertEquals(listOf(3), room.joinRequests.map { it.id })
        assertEquals(1, room.acceptJoin(2).welcome("a3").lobby.yourSeat, "every id the request had still answers")
    }

    @Test
    fun givenCheckCodeFailsForTheRetry_whenTheRetryArrives_thenTheRetryIsClosedAndTheRequestAndEarlierOutputsStay() {
        val failing = Harness(FakeGame(), checkCodeOf = { if (it == "a2") error("no code") else "code-$it" })
        failing.request("b", name = "Bo")
        failing.now = Harness.REQUEST_TIMEOUT / 2
        failing.request("a", name = "Asha", joinId = "J1")
        failing.now = Harness.REQUEST_TIMEOUT // Bo's request runs out in the same call
        val out = failing.request("a2", name = "Asha", joinId = "J1")
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, refusal(out, "b"), "the earlier timeout is not lost")
        assertTrue(out.closed("a2"), "a connection with no code cannot be shown, so it is closed")
        assertEquals(listOf(JoinRequest(2, "Asha", "code-a")), failing.room.joinRequests)
        assertEquals(1, failing.room.acceptRequest(2, "code-a").welcome("a").lobby.yourSeat, "still the first connection")
    }

    @Test
    fun givenCheckCodeFailsForANewRequest_whenItArrives_thenItIsClosedAndNotListed() {
        val failing = Harness(FakeGame(), checkCodeOf = { error("no code") })
        assertTrue(failing.request("a", name = "Asha").closed("a"))
        assertEquals(emptyList(), failing.room.joinRequests)
    }

    @Test
    fun givenNewGameCallsBackIntoTheRoom_whenTheHostStarts_thenTheGameStillStarts() {
        lateinit var nested: Harness<FakeState, FakeMove, FakeView>
        nested = Harness(object : FakeGame() {
            override fun newGame(seats: Int, seed: Long): FakeState = nested.room.tick().let { super.newGame(seats, seed) }
        })
        nested.room.addBot()
        nested.room.start(seed = 1)
        assertEquals(Phase.PLAYING, nested.room.phase, "a nested room call is allowed, as before")
    }

    @Suppress("DEPRECATION")
    @Test
    fun givenRequest_whenTheDeprecatedDeclineIsCalled_thenItStillDeclinesById() {
        h.request("a", name = "Asha")
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1), "a"))
        assertTrue(room.joinRequests.isEmpty())
    }
}
