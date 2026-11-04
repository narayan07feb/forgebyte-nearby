package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Night run 2026-11-04 (ceo): a player picks a move on view N, view N+1 arrives before the tap (here the turn timer
 * played for them). Sent with the seq of the view it was picked on, the move gets "Too late", not the game's error.
 */
class SeenSeqTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(turnTimeoutMs = 1_000), scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    private suspend fun started(): NearbyClient<FakeMove, FakeView> {
        val port = host.open(bindHost = LOOPBACK)
        val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
        c.join(JoinCode(LOOPBACK, port, Harness.SECRET, host.fingerprint!!), "Asha")
        assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })
        c.ready(true)
        host.canStart.first { it }
        host.startGame(seed = 5) // seat 0, the host, moves first
        return c
    }

    @Test
    fun givenJoinerPickedOnViewN_whenTimerPlaysBeforeTheTap_thenMoveWithSeenSeqNGetsTooLate() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = started()
            host.move(FakeMove(host.seqView.first { it?.view?.turn == 0 }!!.view.hand.first()))
            val picked = c.seqView.first { it?.view?.turn == 1 }!! // what the joiner's screen shows
            val newer = c.seqView.first { it!!.seq > picked.seq }!! // the timer played for the joiner
            assertEquals(0, newer.view.turn)
            val rejected = async(start = CoroutineStart.UNDISPATCHED) { c.rejections.first() }
            c.move(FakeMove(picked.view.hand.first()), seenSeq = picked.seq)
            assertEquals("Too late, another player already did that.", rejected.await())
            assertEquals(newer.seq, c.seqView.value!!.seq, "the joiner holds the current table")
        }
    }

    @Test
    fun givenHostPickedOnViewN_whenTimerPlaysBeforeTheTap_thenMoveWithSeenSeqNGetsTooLate() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            started()
            val picked = host.seqView.first { it?.view?.turn == 0 }!!
            val newer = host.seqView.first { it!!.seq > picked.seq }!! // the timer played for the host
            assertEquals(1, newer.view.turn)
            val rejected = async(start = CoroutineStart.UNDISPATCHED) { host.rejections.first() }
            host.move(FakeMove(picked.view.hand.first()), seenSeq = picked.seq)
            assertEquals("Too late, another player already did that.", rejected.await())
        }
    }

    @Test
    fun givenNoSeenSeq_whenMoveOnTheNewestView_thenTheGamesOwnMessage() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = started()
            c.seqView.first { it != null } // the joiner has the newest view
            val rejected = async(start = CoroutineStart.UNDISPATCHED) { c.rejections.first() }
            c.move(FakeMove("C999Z")) // the default seq is the newest view: a plain bad move
            assertEquals("Not your turn.", rejected.await())
        }
    }
}
