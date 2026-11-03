package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals

/** ceo note, 2026-11-03: finding whose turn it is should not run the bot for every seat after every change. */
class CurrentSeatTest {
    /** FakeGame that knows whose turn it is and counts how often the (expensive) bot runs. */
    private class TurnAwareGame(val overrideSeat: Boolean) : FakeGame(cardsEach = 5) {
        var botRuns = 0
        override fun botMove(state: FakeState, seat: Int): FakeMove? = super.botMove(state, seat).also { botRuns++ }
        override fun currentSeat(state: FakeState, seatCount: Int): Int? =
            if (overrideSeat) state.turn.takeIf { !isOver(state) } else super.currentSeat(state, seatCount)
    }

    private fun playHostMoves(game: TurnAwareGame): Int {
        val h = Harness(game, Harness.config(maxSeats = 4, botDelayMs = 1_000, turnTimeoutMs = 30_000))
        repeat(3) { h.room.addBot() }
        h.room.start(seed = 2)
        game.botRuns = 0
        repeat(3) { round ->
            h.room.hostMove(FakeMove(h.room.state!!.hands[0].first()))
            for (bot in 1..3) {
                h.now += 1_000
                h.room.tick()
            }
            assertEquals(0, h.room.state!!.turn, "round $round: all three bots played")
        }
        return game.botRuns
    }

    @Test
    fun givenGameThatKnowsWhoseTurn_whenPlaying_thenTheBotRunsOnlyForMovesItMakes() {
        assertEquals(9, playHostMoves(TurnAwareGame(overrideSeat = true)), "3 rounds x 3 bot moves, nothing else")
    }

    @Test
    fun givenGameWithoutTheOverride_whenPlaying_thenDefaultStillWorksButRunsTheBotPerSeat() {
        val runs = playHostMoves(TurnAwareGame(overrideSeat = false))
        assertEquals(true, runs > 9, "the default asks every seat: $runs runs")
    }

    @Test
    fun givenOverrideSayingNobody_whenScheduled_thenNoClockAndNoBot() {
        val game = object : FakeGame() {
            override fun currentSeat(state: FakeState, seatCount: Int): Int? = null
        }
        val h = Harness(game, Harness.config(botDelayMs = 1_000, turnTimeoutMs = 5_000))
        h.room.addBot()
        h.room.start(seed = 1)
        assertEquals(null, h.room.nextWakeAt)
    }

    @Test
    fun givenOverrideOutOfRange_whenScheduled_thenIgnored() {
        val game = object : FakeGame() {
            override fun currentSeat(state: FakeState, seatCount: Int): Int? = 99
        }
        val h = Harness(game, Harness.config(botDelayMs = 1_000, turnTimeoutMs = 5_000))
        h.room.addBot()
        h.room.start(seed = 1)
        assertEquals(null, h.room.nextWakeAt)
    }
}
