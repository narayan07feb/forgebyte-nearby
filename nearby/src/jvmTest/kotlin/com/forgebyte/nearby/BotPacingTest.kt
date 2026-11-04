package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Night run 2026-11-04: bot pacing was NOT COVERED on a device. TurnClockTest checks the room with a fake clock; this
 * checks the host's real ticker: after the host moves, each bot plays one move botDelayMs after the one before.
 */
class BotPacingTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config(botDelayMs = DELAY), scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    @Test
    fun givenTwoBotsAndRealTicker_whenHostMoves_thenEachBotMovesOneDelayApart() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            host.open(bindHost = LOOPBACK)
            host.addBot(); host.addBot()
            host.canStart.first { it }
            host.startGame(seed = 9)
            val mine = host.seqView.first { it?.view?.turn == 0 }!!

            val t0 = TimeSource.Monotonic.markNow()
            host.move(FakeMove(mine.view.hand.first()), seenSeq = mine.seq)
            val turns = mutableListOf<Pair<Int, Long>>() // (turn, ms after the host's move)
            host.seqView.first { sv ->
                val turn = sv!!.view.turn
                if (turn != turns.lastOrNull()?.first && sv.seq > mine.seq) turns += turn to t0.elapsedNow().inWholeMilliseconds
                turn == 0 && turns.isNotEmpty()
            }

            println("bot pacing (turn, ms after the host move): $turns")
            assertEquals(listOf(1, 2, 0), turns.map { it.first }, "bot 1, then bot 2, then back to the host: $turns")
            assertTrue(turns[0].second < DELAY / 2, "bot 1's turn shows at once, before its move: $turns")
            val gaps = turns.zipWithNext { a, b -> b.second - a.second }
            gaps.forEach { assertTrue(it in DELAY - 5..DELAY + SLACK, "each bot move $DELAY ms apart (got $gaps; $turns)") }
        }
    }

    private companion object {
        const val DELAY = 400L
        const val SLACK = 400L // scheduler and Mac load (load 25 seen); the bound (800 ms) still fails a 981 ms late move
    }
}
