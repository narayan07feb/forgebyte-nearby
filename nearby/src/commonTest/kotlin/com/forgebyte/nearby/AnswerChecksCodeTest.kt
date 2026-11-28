package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host answers a join request with the id and the check code it compared. A retry of the same join (same join id)
 * moves the request to a new connection with a new code under the same id; an answer with the old code must do
 * nothing, so the host never seats a connection whose code it did not see.
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
    fun givenRetryMovedTheRequest_whenHostAcceptsWithTheOldCode_thenNobodyIsSeatedAndTheRequestStays() {
        retriedRequest()
        assertEquals(emptyList(), room.acceptJoin(1, "code-a"), "nothing is sent to the new connection")
        assertEquals(1, room.seatCount)
        assertEquals(listOf(JoinRequest(1, "Asha", "code-a2")), room.joinRequests, "the host sees the new code")
        assertEquals(1, room.acceptJoin(1, "code-a2").welcome("a2").lobby.yourSeat, "the new code seats it")
    }

    @Test
    fun givenRetryMovedTheRequest_whenHostDeclinesWithTheOldCode_thenNothingIsSentAndTheRequestStays() {
        retriedRequest()
        assertEquals(emptyList(), room.declineJoin(1, "code-a"))
        assertEquals(listOf(JoinRequest(1, "Asha", "code-a2")), room.joinRequests)
        assertEquals(RefuseReason.DECLINED, refusal(room.declineJoin(1, "code-a2"), "a2"))
        assertEquals(emptyList(), room.joinRequests)
    }

    @Test
    fun givenRequest_whenHostAnswersWithACodeItNeverShowed_thenNothingHappens() {
        h.request("a", name = "Asha")
        assertEquals(emptyList(), room.acceptJoin(1, "000000"))
        assertEquals(emptyList(), room.declineJoin(1, "000000"))
        assertEquals(1, room.seatCount)
        assertEquals(listOf(1), room.joinRequests.map { it.id })
    }

    @Test
    fun givenRequest_whenAnswered_thenAnsweredIsTrueOnlyForTheCallThatTookIt() {
        h.request("a", name = "Asha")
        room.acceptJoin(1, "code-b")
        assertEquals(false, room.answered, "a wrong code answers nothing")
        room.acceptJoin(1, "code-a")
        assertEquals(true, room.answered)
        room.tick()
        assertEquals(false, room.answered, "the next room call starts again from false")
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
