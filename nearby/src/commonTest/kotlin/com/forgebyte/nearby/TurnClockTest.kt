package com.forgebyte.nearby

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Owner, 2026-11-03: bots play one move at a time about 1 s apart; a turn times out after 30 s (host plays the bot move). */
class TurnClockTest {
    private fun List<RoomOutput>.turn(conn: String) = to(conn).filterIsInstance<HostMsg.View>().last().turn

    // Turn timer

    private val timed = Harness(FakeGame(), Harness.config(turnTimeoutMs = 10_000))

    private fun startTimed(): List<RoomOutput> {
        timed.hello("a"); timed.room.onMessage("a", ClientMsg.Ready(true))
        return timed.room.start(seed = 3) // seat 0 (host) moves first
    }

    @Test
    fun givenTimer_whenGameStarts_thenEveryViewCarriesWhoseTurnAndTheFullTime() {
        val out = startTimed()
        assertEquals(TurnInfo(seat = 0, endsInMs = 10_000), out.turn("a"))
        assertEquals(TurnInfo(0, 10_000), out.turn(HOST_CONN))
        assertEquals(10_000L, timed.room.nextWakeAt)
    }

    @Test
    fun givenTimer_whenOneMsBeforeTheDeadline_thenNothingHappens() {
        startTimed()
        val before = timed.room.state
        timed.now = 9_999
        assertEquals(emptyList(), timed.room.tick())
        assertEquals(before, timed.room.state)
    }

    @Test
    fun givenTimer_whenDeadlineReached_thenHostPlaysTheSeatsBotMoveAndNextClockStarts() {
        startTimed()
        val hostHand = timed.room.state!!.hands[0]
        timed.now = 10_000
        val out = timed.room.tick()
        val after = timed.room.state!!
        assertEquals(1, after.turn, "seat 0's bot move was played")
        assertEquals(hostHand.drop(1), after.hands[0], "exactly the bot's choice (first card) left the host's hand")
        assertEquals(TurnInfo(seat = 1, endsInMs = 10_000), out.turn("a"))
        assertEquals(20_000L, timed.room.nextWakeAt)
    }

    @Test
    fun givenMoveOneMsBeforeTheDeadline_whenPlayed_thenAccepted() {
        startTimed()
        timed.now = 9_999
        val out = timed.room.hostMove(FakeMove(timed.room.state!!.hands[0].first()))
        assertEquals(1, timed.room.state!!.turn)
        assertEquals(TurnInfo(1, 10_000), out.turn("a"), "the next turn gets the full time")
    }

    @Test
    fun givenMoveArrivingExactlyAtTheDeadline_whenPlayed_thenTimerWentFirstAndMoveIsRefused() {
        startTimed()
        timed.hostMove(timed.room.state!!.hands[0].first()) // now seat 1 ("a") has 10 s from t=0
        timed.now = 10_000
        val card = timed.room.state!!.hands[1].first()
        val out = timed.room.onMessage("a", move(card))
        assertEquals("Not your turn.", assertIs<HostMsg.MoveRejected>(out.to("a").last()).message)
        assertEquals(0, timed.room.state!!.turn, "the timer played seat 1's move before the late one arrived")
    }

    @Test
    fun givenCountdownRunning_whenSeatReconnects_thenItGetsTheTimeThatIsLeft() {
        startTimed()
        timed.hostMove(timed.room.state!!.hands[0].first())
        timed.room.onDisconnect("a")
        timed.now = 4_000
        val out = timed.hello("a2", token = "tok1")
        assertEquals(TurnInfo(seat = 1, endsInMs = 6_000), out.turn("a2"))
    }

    @Test
    fun givenDroppedSeatsTurn_whenDeadlineComesBeforeTheReconnectWindow_thenTimerStillPlaysForIt() {
        startTimed()
        timed.hostMove(timed.room.state!!.hands[0].first())
        timed.room.onDisconnect("a")
        timed.now = 10_000 // window is 30 s
        timed.room.tick()
        assertEquals(0, timed.room.state!!.turn)
        assertEquals(SeatKind.PLAYER, timed.room.hostLobby.seats[1].kind, "still a player, only that turn was played")
    }

    @Test
    fun givenNoTimer_whenLongTimePasses_thenNoTurnInfoAndNothingPlayed() {
        val h = Harness(FakeGame())
        h.hello("a"); h.room.onMessage("a", ClientMsg.Ready(true))
        val out = h.room.start(seed = 3)
        assertNull(out.turn("a"))
        h.now = 10_000_000
        assertEquals(emptyList(), h.room.tick())
        assertNull(h.room.nextWakeAt)
    }

    @Test
    fun givenGameOver_whenChecked_thenClockStopped() {
        startTimed()
        while (timed.room.phase == Phase.PLAYING) {
            val s = timed.room.state!!
            if (s.turn == 0) timed.room.hostMove(FakeMove(s.hands[0].first())) else timed.room.onMessage("a", move(s.hands[1].first()))
        }
        assertNull(timed.room.nextWakeAt)
        timed.now = 1_000_000
        assertEquals(emptyList(), timed.room.tick())
    }

    // Bot pacing

    private val paced = Harness(FakeGame(), Harness.config(botDelayMs = 1_000))

    @Test
    fun givenPacedBots_whenHostMoves_thenBotsPlayOneAtATimeInTurnOrderEachSecond() {
        paced.room.addBot(); paced.room.addBot()
        paced.room.start(seed = 9)
        paced.room.hostMove(FakeMove(paced.room.state!!.hands[0].first()))
        assertEquals(1, paced.room.state!!.turn, "no bot moved inside the host's call")
        assertEquals(1_000L, paced.room.nextWakeAt)

        paced.now = 999
        assertEquals(emptyList(), paced.room.tick())
        paced.now = 1_000
        val first = paced.room.tick()
        assertEquals(2, paced.room.state!!.turn, "bot 1 moved")
        assertEquals(1, first.views(HOST_CONN).size, "one view per bot move")
        assertEquals(2_000L, paced.room.nextWakeAt)

        paced.now = 2_000
        paced.room.tick()
        assertEquals(0, paced.room.state!!.turn, "bot 2 moved, back to the host")
        assertEquals(3, paced.room.state!!.pile.size)
        assertNull(paced.room.nextWakeAt, "the host's own turn has no timer here")
    }

    @Test
    fun givenPacedBots_whenSeatTakenOverMidGame_thenItsBotMoveIsPacedToo() {
        paced.hello("a"); paced.room.onMessage("a", ClientMsg.Ready(true))
        paced.room.start(seed = 3)
        paced.room.hostMove(FakeMove(paced.room.state!!.hands[0].first()))
        paced.room.onMessage("a", ClientMsg.Leave) // seat 1 becomes a bot on its own turn
        assertEquals(1, paced.room.state!!.turn)
        paced.now = 1_000
        paced.room.tick()
        assertEquals(0, paced.room.state!!.turn)
    }

    @Test
    fun givenPacingAndTimer_whenBotsThenHuman_thenBotsPacedAndHumanGetsTheTimer() {
        val both = Harness(FakeGame(), Harness.config(botDelayMs = 500, turnTimeoutMs = 5_000))
        both.room.addBot()
        both.room.start(seed = 1)
        both.room.hostMove(FakeMove(both.room.state!!.hands[0].first()))
        both.now = 500
        val out = both.room.tick()
        assertEquals(0, both.room.state!!.turn)
        assertEquals(TurnInfo(0, 5_000), out.turn(HOST_CONN))
        assertEquals(5_500L, both.room.nextWakeAt)
    }

    // Wire compatibility with v0.2.0

    @Test
    fun givenViewFromAV020Host_whenDecoded_thenNoTurn() {
        val old = """{"t":"view","seq":3,"view":{"x":1}}"""
        assertEquals(HostMsg.View(3, kotlinx.serialization.json.JsonObject(mapOf("x" to JsonPrimitive(1))), null), NearbyProtocol.decodeHost(old))
    }

    @Test
    fun givenViewWithTurn_whenReadByAV020Client_thenItsKnownFieldsStillDecode() {
        // A v0.2.0 client has View(seq, view) only and ignores unknown keys.
        @kotlinx.serialization.Serializable
        data class OldView(val seq: Int, val view: kotlinx.serialization.json.JsonElement)
        val text = NearbyProtocol.encode(HostMsg.View(4, JsonPrimitive(7), TurnInfo(1, 2_500)))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals(OldView(4, JsonPrimitive(7)), json.decodeFromJsonElement(OldView.serializer(), kotlinx.serialization.json.JsonObject(obj - "t")))
    }
}

private fun Harness<FakeState, FakeMove, FakeView>.hostMove(card: String) = room.hostMove(FakeMove(card))
