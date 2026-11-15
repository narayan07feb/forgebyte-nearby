package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host's own Home or screen-off must not put its joiners to bots (ceo, 16 Nov 2026): while the host is paused the
 * seat-away clock stops, and on resume each away seat gets a fresh full window. Test IDs PR-1 to PR-12 (PR body).
 */
class HostPauseTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room
    private val w = Harness.WINDOW

    private fun startWithA() {
        h.hello("a"); room.onMessage("a", ClientMsg.Ready(true)); room.start(seed = 3)
    }

    private fun seatKind(out: List<RoomOutput>) = out.lastLobby(HOST_CONN).seats[1].kind

    @Test // PR-1
    fun givenHostPaused_whenAwayThirtySixtyOrThreeHundredSeconds_thenNoBotAfterResumeAndReturn() {
        for (away in listOf(30_000L, 60_000L, 300_000L)) {
            val h = Harness(FakeGame())
            h.hello("a"); h.room.onMessage("a", ClientMsg.Ready(true)); h.room.start(seed = 3)
            h.room.pause()
            h.room.onDisconnect("a") // the host's app is in the background: its links go
            h.now += away
            h.room.tick()
            assertEquals(SeatKind.PLAYER, h.room.hostLobby.seats[1].kind, "bot while the host was away $away ms")
            h.room.resume()
            h.now += w - 1
            h.room.tick()
            val back = h.hello("a2", token = "tok1")
            assertEquals(SeatKind.PLAYER, back.lastLobby(HOST_CONN).seats[1].kind, "away $away ms")
        }
    }

    @Test // PR-2
    fun givenHostInFront_whenJoinerAwayPastTheWindow_thenBotAsBefore() {
        startWithA()
        room.onDisconnect("a")
        h.now = w
        assertEquals(SeatKind.BOT, seatKind(room.tick()))
    }

    @Test // PR-3
    fun givenHostPausedAndResumed_whenJoinerNeverReturns_thenBotOneWindowAfterResume() {
        startWithA()
        room.pause()
        room.onDisconnect("a")
        h.now = 5 * w
        room.tick()
        room.resume()
        h.now += w - 1
        room.tick()
        assertEquals(SeatKind.PLAYER, room.hostLobby.seats[1].kind, "bot before a full window after the resume")
        h.now += 1
        assertEquals(SeatKind.BOT, seatKind(room.tick()))
    }

    @Test // PR-4
    fun givenJoinerAwayBeforePause_whenResumed_thenFreshFullWindow() {
        startWithA()
        room.onDisconnect("a")
        h.now = w - 1_000 // most of the window used
        room.pause()
        h.now += 10_000
        room.tick()
        room.resume()
        h.now += w - 1
        room.tick()
        assertEquals(SeatKind.PLAYER, room.hostLobby.seats[1].kind, "the rest of the old window was used, not a fresh one")
    }

    @Test // PR-5
    fun givenHostPaused_whenJoinerDrops_thenWindowStartsAtResume() {
        startWithA()
        room.pause()
        h.now = 1_000
        room.onDisconnect("a")
        h.now = 100_000
        room.resume()
        h.now = 100_000 + w - 1
        room.tick()
        assertEquals(SeatKind.PLAYER, room.hostLobby.seats[1].kind)
        h.now = 100_000 + w
        assertEquals(SeatKind.BOT, seatKind(room.tick()))
    }

    @Test // PR-6
    fun givenHostPaused_whenJoinerComesBack_thenSeatBack() {
        startWithA()
        room.pause()
        room.onDisconnect("a")
        h.now = 3 * w
        val back = h.hello("a2", token = "tok1")
        assertEquals(SeatKind.PLAYER, back.welcome("a2").lobby.seats[1].kind)
        assertTrue(back.welcome("a2").lobby.seats[1].connected)
    }

    @Test // PR-7
    fun givenPausedTwice_whenResumed_thenOneFreshWindow() {
        startWithA()
        room.onDisconnect("a")
        room.pause()
        h.now = 2 * w
        room.pause()
        h.now = 4 * w
        room.resume()
        assertFalse(room.isPaused)
        h.now = 5 * w - 1
        room.tick()
        assertEquals(SeatKind.PLAYER, room.hostLobby.seats[1].kind)
        h.now = 5 * w
        assertEquals(SeatKind.BOT, seatKind(room.tick()))
    }

    @Test // PR-7
    fun givenNotPaused_whenResumed_thenNothingChanges() {
        startWithA()
        room.onDisconnect("a")
        h.now = w - 1
        room.resume() // no pause before: the window keeps its start
        h.now = w
        assertEquals(SeatKind.BOT, seatKind(room.tick()))
    }

    @Test // PR-8
    fun givenHostPaused_whenJoinerAway_thenNextWakeLeavesOutItsWindow() {
        startWithA()
        room.onDisconnect("a")
        assertEquals(w, room.nextWakeAt)
        room.pause()
        assertEquals(null, room.nextWakeAt)
        h.now = 7_000
        room.resume()
        assertEquals(7_000 + w, room.nextWakeAt)
    }

    @Test // PR-9 (ceo 16 Nov 03:3x, P6: join requests wait too while the host is paused)
    fun givenHostPaused_whenAJoinRequestWaitsPastItsTimeout_thenStillWaitingAndTheRestRunsAfterResume() {
        val h = Harness(FakeGame(), Harness.config(approveCodeJoins = true))
        h.request("a")
        h.now = 5_000
        h.room.pause()
        h.now = 100_000
        assertTrue(h.room.tick().to("a").isEmpty(), "the request timed out while the host was paused")
        h.room.resume()
        h.now = 100_000 + Harness.REQUEST_TIMEOUT - 5_000 - 1
        assertTrue(h.room.tick().to("a").isEmpty(), "the request got less than the time it had left")
        h.now += 1
        assertEquals(RefuseReason.REQUEST_TIMED_OUT, (h.room.tick().to("a").single() as HostMsg.Refused).reason)
    }

    @Test // PR-10
    fun givenLobby_whenHostPausedPastTheWindow_thenSeatKept() {
        h.hello("a")
        room.pause()
        room.onDisconnect("a")
        h.now = 3 * w
        room.tick()
        assertEquals(2, room.seatCount)
        room.resume()
        h.now += w
        room.tick()
        assertEquals(1, room.seatCount, "the lobby seat is freed one window after the resume, as without a pause")
    }

    @Test // PR-12
    fun givenGameOver_whenPausedAndResumed_thenNothingChanges() {
        startWithA()
        var turn = 0
        while (room.phase == Phase.PLAYING) {
            val s = room.state!!
            if (turn == 0) room.hostMove(FakeMove(s.hands[0].first())) else room.onMessage("a", move(s.hands[1].first()))
            turn = 1 - turn
        }
        room.onDisconnect("a")
        room.pause()
        h.now = 3 * w
        room.tick()
        room.resume()
        h.now += w
        room.tick()
        assertEquals(SeatKind.PLAYER, room.hostLobby.seats[1].kind)
    }

    @Test // PR-13: the turn deadline stops while the host is paused, and keeps the time it had left
    fun givenTurnClockRunning_whenHostPausedPastTheDeadline_thenNoAutoMoveAndTheTimeLeftIsKept() {
        val h = Harness(FakeGame(), Harness.config(turnTimeoutMs = 10_000))
        h.hello("a"); h.room.onMessage("a", ClientMsg.Ready(true)); h.room.start(seed = 3) // seat 0 moves first
        val before = h.room.state
        h.now = 4_000
        h.room.pause()
        h.now = 50_000
        h.room.tick()
        assertEquals(before, h.room.state, "the timer played for the host while it was paused")
        h.room.resume()
        h.now = 55_999
        h.room.tick()
        assertEquals(before, h.room.state, "the deadline did not keep its 6 s left")
        h.now = 56_000
        h.room.tick()
        assertEquals(1, h.room.state!!.turn, "the timer played once the time left ran out after the resume")
    }

    @Test // PR-14: a planned bot move waits while the host is paused
    fun givenBotMovePlanned_whenHostPaused_thenNoBotMoveUntilTheDelayRunsAfterResume() {
        val h = Harness(FakeGame(), Harness.config(botDelayMs = 1_000))
        h.room.addBot(); h.room.start(seed = 3)
        h.room.hostMove(FakeMove(h.room.state!!.hands[0].first())) // the bot's turn, planned 1 s later
        h.room.pause()
        h.now = 60_000
        h.room.tick()
        assertEquals(1, h.room.state!!.turn, "the bot moved while the host was paused")
        h.room.resume()
        h.now = 60_999
        h.room.tick()
        assertEquals(1, h.room.state!!.turn)
        h.now = 61_000
        h.room.tick()
        assertEquals(0, h.room.state!!.turn, "the bot did not move after its delay ran out")
    }

    @Test // PR-15: joiners see the host away while it is paused
    fun givenGame_whenHostPausesAndResumes_thenJoinersSeeTheHostAwayThenBack() {
        startWithA()
        assertFalse(room.pause().lastLobby("a").seats[0].connected, "the joiner did not see the host away")
        assertTrue(room.resume().lastLobby("a").seats[0].connected)
    }

    @Test // PR-16: while paused nothing is due, so the transport does not wake for the game clock
    fun givenTurnClockRunning_whenHostPaused_thenNextWakeIsNull() {
        val h = Harness(FakeGame(), Harness.config(turnTimeoutMs = 10_000))
        h.hello("a"); h.room.onMessage("a", ClientMsg.Ready(true)); h.room.start(seed = 3)
        assertEquals(10_000L, h.room.nextWakeAt)
        h.room.pause()
        assertEquals(null, h.room.nextWakeAt)
        h.now = 3_000
        h.room.resume()
        assertEquals(13_000L, h.room.nextWakeAt)
    }
}
