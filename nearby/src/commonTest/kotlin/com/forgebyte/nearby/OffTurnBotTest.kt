package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Off-turn bot moves (ceo, 2026-11-03): the room asks the game's own bot order once per applied move, plays the
 * answer botDelayMs later, and any move landing first replaces the plan. Seats: 0 host, 1 joiner "a", 2 bot.
 */
class OffTurnBotTest {
    private class Table(val h: Harness<SnapState, SnapMove, SnapView>) {
        val room get() = h.room
        val state get() = room.state!!
        var aSeq = 0 // seq of the last view joiner "a" received

        fun track(out: List<RoomOutput>) = out.also { o ->
            o.to("a").filterIsInstance<HostMsg.View>().lastOrNull()?.let { aSeq = it.seq }
        }

        /** The host plays an even card: the snap window opens and seat 1 ("a") is next. */
        fun hostOpensSnap() = track(room.hostMove(SnapMove.Play(state.hands[0].first(SnapGame::even))))

        fun aSnaps(seenSeq: Int? = aSeq) = track(
            room.onMessage("a", ClientMsg.Move(NearbyProtocol.json.encodeToJsonElement(SnapMove.serializer(), SnapMove.Snap()), seenSeq)),
        )

        fun at(ms: Long) = track(h.also { it.now = ms }.room.tick())
    }

    /** A table whose deal fits the test: the host holds an even card, and [extra] holds too. */
    private fun table(
        botDelayMs: Long = 1_000,
        turnTimeoutMs: Long = 0,
        jump: Boolean = false,
        extra: (SnapState) -> Boolean = { true },
    ): Table {
        for (seed in 1L..500L) {
            val h = Harness(SnapGame(jump = jump), Harness.config(botDelayMs = botDelayMs, turnTimeoutMs = turnTimeoutMs))
            h.hello("a"); h.room.onMessage("a", ClientMsg.Ready(true)); h.room.addBot()
            val t = Table(h)
            t.track(h.room.start(seed))
            if (t.state.hands[0].any(SnapGame::even) && extra(t.state)) return t
        }
        error("no seed fits")
    }

    @Test
    fun givenSnapWindowOpens_whenBotDelayPasses_thenBotSnapsOffTurnAndTheTurnStays() {
        val t = table()
        t.hostOpensSnap()
        assertEquals(1_000L, t.room.nextWakeAt, "the bot planned its catch")
        assertEquals(emptyList(), t.at(999))
        t.at(1_000)
        assertEquals(listOf(0, 0, 1), t.state.snaps)
        assertEquals(1, t.state.turn, "a catch does not move the turn")
    }

    @Test
    fun givenBotPlannedACatch_whenHumanCatchesInsideTheWindow_thenHumanWinsAndThePlanIsGone() {
        val t = table()
        t.hostOpensSnap()
        t.h.now = 500
        t.aSnaps()
        assertEquals(listOf(0, 1, 0), t.state.snaps)
        assertNull(t.room.nextWakeAt, "nothing left for the bot: plan replaced, not kept")
        t.at(1_000)
        assertEquals(listOf(0, 1, 0), t.state.snaps, "the old plan never fires")
    }

    @Test
    fun givenBotCaughtFirst_whenJoinerCatchArrivesLate_thenTooLateAndAFreshView() {
        val t = table()
        t.hostOpensSnap()
        val seenByA = t.aSeq
        t.h.now = 1_000
        val out = t.aSnaps(seenSeq = seenByA) // tick and late move in the same room call: the bot was due first
        assertEquals(listOf(0, 0, 1), t.state.snaps)
        val toA = out.to("a")
        assertEquals("Too late, another player already did that.", assertIs<HostMsg.MoveRejected>(toA.first { it is HostMsg.MoveRejected }).message)
        assertTrue(toA.last() is HostMsg.View, "the joiner gets the table as it is now")
    }

    @Test
    fun givenTwoHumansCatchAtOnce_whenBothArrive_thenFirstWinsAndTheJoinerSeesTooLate() {
        val t = table()
        t.hostOpensSnap()
        val seenByA = t.aSeq
        t.h.now = 200
        t.room.hostMove(SnapMove.Snap(), seenSeq = seenByA) // arrives first
        val out = t.aSnaps(seenSeq = seenByA)
        assertEquals(listOf(1, 0, 0), t.state.snaps)
        assertEquals("Too late, another player already did that.", (out.to("a").first() as HostMsg.MoveRejected).message)
        assertIs<HostMsg.View>(out.to("a")[1])
    }

    @Test
    fun givenIllegalMoveOnTheCurrentView_whenSent_thenTheGamesOwnMessageNotTooLate() {
        val t = table()
        val out = t.aSnaps() // no window open, and a saw the latest view
        assertEquals("Nothing to snap.", (out.to("a").single() as HostMsg.MoveRejected).message)
    }

    @Test
    fun givenHumansClockRunning_whenBotCatchesOffTurn_thenTheClockIsNotReset() {
        val t = table(turnTimeoutMs = 5_000)
        t.hostOpensSnap() // seat 1's clock: deadline 5 000
        val out = t.at(1_000) // bot catches
        assertEquals(listOf(0, 0, 1), t.state.snaps)
        assertEquals(TurnInfo(seat = 1, endsInMs = 4_000), out.to("a").filterIsInstance<HostMsg.View>().last().turn)
    }

    @Test
    fun givenBotJumpsIn_whenItTakesTheTurn_thenTheNextHumanGetsAFreshClock() {
        val t = table(turnTimeoutMs = 5_000, jump = true)
        t.hostOpensSnap()
        val jump = t.at(1_000)
        assertEquals(2, t.state.turn, "the jump-in took the turn")
        assertNull(jump.to("a").filterIsInstance<HostMsg.View>().last().turn, "a bot's turn has no clock")
        val after = t.at(2_000) // the bot plays its own turn, back to the host
        assertEquals(0, t.state.turn)
        assertEquals(TurnInfo(seat = 0, endsInMs = 5_000), after.to("a").filterIsInstance<HostMsg.View>().last().turn)
    }

    @Test
    fun givenBotPlanPending_whenTheTurnTimerPlaysForAHuman_thenThePlanIsReplaced() {
        // Seat 1's clock (500) runs out before the bot's planned catch (1 000); its auto-move is an odd card, so the
        // window closes and the old catch must not fire.
        val t = table(botDelayMs = 1_000, turnTimeoutMs = 500) { s -> !SnapGame.even(s.hands[1].first()) }
        t.hostOpensSnap()
        assertEquals(500L, t.room.nextWakeAt)
        t.at(500)
        assertEquals(2, t.state.turn, "the timer played seat 1's card")
        assertNotEquals(1_000L, t.room.nextWakeAt, "the catch planned before the auto-move is gone")
        assertEquals(listOf(0, 0, 0), t.state.snaps)
    }

    @Test
    fun givenSeatTakenOverAfterTheWindow_whenPlayerReturns_thenSeatIsTheirsAndTheBotPlanForItIsDropped() {
        val t = table(botDelayMs = 1_000) { s -> !SnapGame.even(s.hands[0].first()) }
        t.room.hostMove(SnapMove.Play(t.state.hands[0].first())) // odd: no window; seat 1 to play
        t.room.onDisconnect("a")
        val window = Harness.WINDOW
        t.at(window) // taken over: seat 1 is a bot now and plans its turn
        assertEquals(SeatKind.BOT, t.room.hostLobby.seats[1].kind)
        assertEquals(window + 1_000, t.room.nextWakeAt, "botSeats includes the taken-over seat")

        t.h.now = window + 500
        val back = t.h.hello("a2", token = "tok1")
        assertEquals(SeatKind.PLAYER, back.welcome("a2").lobby.seats[1].kind)
        assertNull(t.room.nextWakeAt, "the plan for the returned seat is dropped")
        val turnBefore = t.state.turn
        t.at(window + 1_000)
        assertEquals(turnBefore, t.state.turn, "no bot move for the returned player")
    }

    @Test
    fun givenBotsThatNeverStop_whenInstantMode_thenCapHitTracedAndTheRoomCarriesOn() {
        val lines = mutableListOf<String>()
        traceSink = { lines += it }
        try {
            val h = Harness(EndlessGame(), Harness.config(botDelayMs = 0))
            h.room.addBot()
            val out = h.room.start(seed = 1)
            assertEquals(10_000, h.room.state)
            assertEquals(Phase.PLAYING, h.room.phase)
            assertTrue(out.to(HOST_CONN).any { it is HostMsg.View }, "the host still gets the table")
            assertTrue(lines.any { "10000 moves in a row" in it }, "traced: $lines")
        } finally {
            traceSink = null
        }
    }

    /** A bot that always wants to move: the instant-mode loop must stop at its cap. */
    private class EndlessGame : NearbyGame<Int, Int, Int> {
        override val gameId = "fake"
        override val moveSerializer = kotlinx.serialization.serializer<Int>()
        override val viewSerializer = kotlinx.serialization.serializer<Int>()
        override fun newGame(seats: Int, seed: Long) = 0
        override fun apply(state: Int, seat: Int, move: Int) = Result.success(state + 1)
        override fun viewFor(state: Int, seat: Int) = state
        override fun botMove(state: Int, seat: Int) = if (seat == 1) 1 else null
        override fun isOver(state: Int) = false
    }
}
