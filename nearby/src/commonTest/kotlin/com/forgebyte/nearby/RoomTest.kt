package com.forgebyte.nearby

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTest {
    private val h = Harness(FakeGame())
    private val room get() = h.room

    private fun refusal(out: List<RoomOutput>, conn: String): RefuseReason {
        assertTrue(out.closed(conn), "a refused connection is closed")
        return assertIs<HostMsg.Refused>(out.to(conn).single()).reason
    }

    // Join

    @Test
    fun givenLobby_whenPlayerJoins_thenWelcomedWithSeatAndHostSeesNewSeat() {
        val out = h.hello("a", name = "Asha")

        val welcome = out.welcome("a")
        assertEquals("tok1", welcome.reconnectToken)
        assertEquals(1, welcome.lobby.yourSeat)
        assertEquals(listOf("Host", "Asha"), welcome.lobby.seats.map { it.name })
        assertEquals(SeatInfo("Asha", SeatKind.PLAYER, ready = false, connected = true), welcome.lobby.seats[1])
        assertEquals(0, out.lastLobby(HOST_CONN).yourSeat)
        assertEquals(2, out.lastLobby(HOST_CONN).seats.size)
        assertTrue(out.to("a").none { it is HostMsg.LobbyUpdate }, "the joiner gets its lobby in the welcome only")
    }

    @Test
    fun givenSecondPlayer_whenJoins_thenEarlierPlayerGetsLobbyWithOwnSeat() {
        h.hello("a")
        val out = h.hello("b")

        assertEquals(2, out.welcome("b").lobby.yourSeat)
        assertEquals(1, out.lastLobby("a").yourSeat)
        assertEquals(3, out.lastLobby("a").seats.size)
    }

    @Test
    fun givenSeatedConnection_whenHelloAgain_thenIgnored() {
        h.hello("a")
        assertEquals(emptyList(), h.hello("a"))
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenNoHello_whenOtherMessage_thenIgnored() {
        assertEquals(emptyList(), room.onMessage("x", ClientMsg.Ready(true)))
        assertEquals(emptyList(), room.onMessage("x", move("C0Z")))
        assertEquals(emptyList(), room.onMessage("x", ClientMsg.Leave))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenUntidyName_whenJoins_thenNameCleanedAndBlankGetsDefault() {
        val long = h.hello("a", name = "  Ravi\u0007 Kumar Singh Chauhan Jr  ").welcome("a").lobby.seats[1].name
        assertEquals("Ravi Kumar Singh Cha", long)
        assertEquals("Player 3", h.hello("b", name = "   ").welcome("b").lobby.seats[2].name)
    }

    @Test
    fun givenFullRoom_whenAnotherJoins_thenRefusedRoomFull() {
        val small = Harness(FakeGame(), Harness.config(maxSeats = 2))
        small.hello("a")

        assertEquals(RefuseReason.ROOM_FULL, refusal(small.hello("b"), "b"))
        assertEquals(2, small.room.seatCount)
    }

    @Test
    fun givenRoomFullOfBots_whenAddBot_thenNothingAdded() {
        repeat(5) { room.addBot() }
        assertEquals(4, room.seatCount)
        assertEquals(RefuseReason.ROOM_FULL, refusal(h.hello("a"), "a"))
    }

    // Version and secret refusal

    @Test
    fun givenOtherProtocolVersion_whenJoins_thenRefusedProtocolMismatch() {
        assertEquals(RefuseReason.PROTOCOL_MISMATCH, refusal(h.hello("a", protocol = PROTOCOL_VERSION + 1), "a"))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenOtherGameVersion_whenJoins_thenRefusedWithBothVersionsInMessage() {
        val out = h.hello("a", gameVersion = 6)
        assertEquals(RefuseReason.GAME_VERSION_MISMATCH, refusal(out, "a"))
        val text = (out.to("a").single() as HostMsg.Refused).message
        assertTrue("7" in text && "6" in text, text)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenOtherGame_whenJoins_thenRefusedWrongGame() {
        assertEquals(RefuseReason.WRONG_GAME, refusal(h.hello("a", gameId = "uno"), "a"))
    }

    @Test
    fun givenWrongSecret_whenJoins_thenRefusedWrongSecret() {
        assertEquals(RefuseReason.WRONG_SECRET, refusal(h.hello("a", secret = "s3creT"), "a"))
        assertEquals(RefuseReason.WRONG_SECRET, refusal(h.hello("b", secret = ""), "b"))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenTokenWithWrongCode_whenReconnects_thenTokenDecidesAndSeatComesBack() {
        val token = h.hello("a").welcome("a").reconnectToken
        room.onDisconnect("a")
        assertEquals(1, h.hello("a2", token = token, secret = "nope").welcome("a2").lobby.yourSeat)
    }

    // Ready and start

    @Test
    fun givenJoinerNotReady_whenStart_thenNothingHappens() {
        h.hello("a")
        assertFalse(room.canStart)
        assertEquals(emptyList(), room.start(seed = 1))
        assertEquals(Phase.LOBBY, room.phase)
    }

    @Test
    fun givenHostAlone_whenStart_thenNeedsMinSeats() {
        assertFalse(room.canStart)
        room.addBot()
        assertTrue(room.canStart)
    }

    @Test
    fun givenReady_whenUnready_thenCannotStart() {
        h.hello("a")
        val out = room.onMessage("a", ClientMsg.Ready(true))
        assertTrue(out.lastLobby(HOST_CONN).seats[1].ready)
        assertTrue(room.canStart)
        room.onMessage("a", ClientMsg.Ready(false))
        assertFalse(room.canStart)
    }

    @Test
    fun givenEveryoneReady_whenStart_thenEachSeatGetsOwnViewAndPlayingLobby() {
        h.hello("a"); room.onMessage("a", ClientMsg.Ready(true))
        val out = room.start(seed = 42)

        assertEquals(Phase.PLAYING, room.phase)
        val state = room.state!!
        assertEquals(state.hands[0], out.views(HOST_CONN).single().hand)
        assertEquals(state.hands[1], out.views("a").single().hand)
        assertEquals(1, (out.to("a").filterIsInstance<HostMsg.View>().single()).seq)
        assertEquals(Phase.PLAYING, out.lastLobby("a").phase)
    }

    @Test
    fun givenStarted_whenReadyChanges_thenIgnored() {
        h.hello("a"); room.onMessage("a", ClientMsg.Ready(true)); room.start(1)
        assertEquals(emptyList(), room.onMessage("a", ClientMsg.Ready(false)))
    }

    @Test
    fun givenStarted_whenNewPlayerJoins_thenRefusedGameStarted() {
        room.addBot(); room.start(1)
        assertEquals(RefuseReason.GAME_STARTED, refusal(h.hello("late"), "late"))
    }

    @Test
    fun givenStarted_whenStartAgain_thenNothingHappens() {
        room.addBot(); room.start(1)
        assertEquals(emptyList(), room.start(2))
    }

    // Moves

    private fun startWithA(): FakeState {
        h.hello("a"); room.onMessage("a", ClientMsg.Ready(true)); room.start(seed = 3)
        return room.state!!
    }

    @Test
    fun givenNotYourTurn_whenMove_thenRejectedAndStateUnchanged() {
        val before = startWithA()
        val out = room.onMessage("a", move(before.hands[1].first()))

        assertEquals("Not your turn.", assertIs<HostMsg.MoveRejected>(out.to("a").single()).message)
        assertEquals(before, room.state)
    }

    @Test
    fun givenCardNotHeld_whenHostMoves_thenRejectedToHost() {
        val before = startWithA()
        val out = room.hostMove(FakeMove(before.hands[1].first()))
        assertIs<HostMsg.MoveRejected>(out.to(HOST_CONN).single())
        assertEquals(before, room.state)
    }

    @Test
    fun givenGarbageMove_whenSent_thenRejectedUnknownMove() {
        startWithA()
        val out = room.onMessage("a", jsonMove(JsonPrimitive(5)))
        assertEquals("Unknown move.", assertIs<HostMsg.MoveRejected>(out.to("a").single()).message)
    }

    @Test
    fun givenLobby_whenMove_thenRejectedNoGame() {
        h.hello("a")
        assertIs<HostMsg.MoveRejected>(room.onMessage("a", move("C0Z")).to("a").single())
    }

    @Test
    fun givenLegalMoves_whenPlayedToEnd_thenViewsSeqGrowAndPhaseOver() {
        startWithA()
        val seqs = mutableListOf<Int>()
        var turn = 0
        while (room.phase == Phase.PLAYING) {
            val s = room.state!!
            val out = if (turn == 0) room.hostMove(FakeMove(s.hands[0].first()))
            else room.onMessage("a", move(s.hands[1].first()))
            seqs += out.to("a").filterIsInstance<HostMsg.View>().single().seq
            turn = 1 - turn
        }
        assertEquals(Phase.OVER, room.phase)
        assertEquals((2 until 2 + seqs.size).toList(), seqs, "start sent seq 1, then one view per move")
        assertTrue(room.state!!.hands.any { it.isEmpty() })
        assertIs<HostMsg.MoveRejected>(room.hostMove(FakeMove("C0Z")).to(HOST_CONN).single())
    }

    @Test
    fun givenBotSeats_whenHumanMoves_thenBotsPlayTheirTurnsInTheSameCall() {
        room.addBot(); room.addBot()
        room.start(seed = 9)
        val s = room.state!!
        val out = room.hostMove(FakeMove(s.hands[0].first()))

        val after = room.state!!
        assertEquals(0, after.turn, "both bots played and it is the host's turn again")
        assertEquals(3, after.pile.size)
        assertEquals(1, out.views(HOST_CONN).size, "one view per change, not per bot move")
    }

    @Test
    fun givenOnlyBotsLeft_whenStarted_thenBotsFinishTheGame() {
        h.hello("a"); room.onMessage("a", ClientMsg.Ready(true)); room.addBot()
        room.start(seed = 5)
        room.kick(1)
        // Host still has to play; host plays until done while the bots keep up.
        while (room.phase == Phase.PLAYING) room.hostMove(FakeMove(room.state!!.hands[0].first()))
        assertEquals(Phase.OVER, room.phase)
    }

    // Leave and kick

    @Test
    fun givenLobby_whenPlayerLeaves_thenSeatRemovedAndOthersRenumbered() {
        h.hello("a"); h.hello("b")
        val out = room.onMessage("a", ClientMsg.Leave)

        assertTrue(out.closed("a"))
        assertEquals(2, room.seatCount)
        assertEquals(1, out.lastLobby("b").yourSeat)
        assertEquals(listOf("Host", "b"), out.lastLobby(HOST_CONN).seats.map { it.name })
    }

    @Test
    fun givenLobby_whenHostKicks_thenKickedClosedAndTokenDead() {
        val token = h.hello("a").welcome("a").reconnectToken
        val out = room.kick(1)

        assertEquals(listOf<HostMsg>(HostMsg.Kicked), out.to("a"))
        assertTrue(out.closed("a"))
        assertEquals(1, room.seatCount)
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a2", token = token), "a2"))
    }

    @Test
    fun givenBadSeat_whenKick_thenNothing() {
        h.hello("a")
        assertEquals(emptyList(), room.kick(0))
        assertEquals(emptyList(), room.kick(2))
        assertEquals(emptyList(), room.kick(-1))
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenLobbyBot_whenKicked_thenRemoved() {
        room.addBot()
        room.kick(1)
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenGame_whenPlayerLeaves_thenBotTakesSeatAndPlaysItsTurn() {
        startWithA()
        room.hostMove(FakeMove(room.state!!.hands[0].first()))
        val out = room.onMessage("a", ClientMsg.Leave)

        assertTrue(out.closed("a"))
        assertEquals(SeatKind.BOT, out.lastLobby(HOST_CONN).seats[1].kind)
        assertEquals(0, room.state!!.turn, "the new bot played seat 1's turn")
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenGame_whenHostKicks_thenKickedAndBotTakesSeat() {
        startWithA()
        val out = room.kick(1)
        assertEquals(listOf<HostMsg>(HostMsg.Kicked), out.to("a"))
        assertEquals(SeatKind.BOT, out.lastLobby(HOST_CONN).seats[1].kind)
    }

    // Reconnect

    @Test
    fun givenLobbyDrop_whenBackInsideWindow_thenSameSeatAndNewToken() {
        val token = h.hello("a", name = "Asha").welcome("a").reconnectToken
        h.hello("b")
        val drop = room.onDisconnect("a")
        assertFalse(drop.lastLobby(HOST_CONN).seats[1].connected)

        h.now = Harness.WINDOW - 1
        val out = h.hello("a2", token = token)
        val welcome = out.welcome("a2")
        assertEquals(1, welcome.lobby.yourSeat)
        assertEquals("Asha", welcome.lobby.seats[1].name)
        assertTrue(welcome.lobby.seats[1].connected)
        assertEquals("tok3", welcome.reconnectToken)
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a3", token = token), "a3"), "old token is dead")
    }

    @Test
    fun givenLobbyDrop_whenWindowPasses_thenTickFreesSeat() {
        h.hello("a")
        room.onDisconnect("a")
        h.now = Harness.WINDOW - 1
        assertEquals(emptyList(), room.tick())
        h.now = Harness.WINDOW
        val out = room.tick()
        assertEquals(1, room.seatCount)
        assertEquals(1, out.lastLobby(HOST_CONN).seats.size)
    }

    @Test
    fun givenLobbyDrop_whenBackAfterWindowWithoutTick_thenRefusedExpired() {
        val token = h.hello("a").welcome("a").reconnectToken
        room.onDisconnect("a")
        h.now = Harness.WINDOW + 5
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a2", token = token), "a2"))
        assertEquals(1, room.seatCount)
    }

    @Test
    fun givenDroppedJoiner_whenHostStarts_thenCannotStartUntilBack() {
        val token = h.hello("a").welcome("a").reconnectToken
        room.onMessage("a", ClientMsg.Ready(true))
        room.onDisconnect("a")
        assertFalse(room.canStart)
        h.hello("a2", token = token)
        assertTrue(room.canStart)
    }

    @Test
    fun givenGameDrop_whenBackInsideWindow_thenGetsCurrentViewAndGameWaited() {
        val start = startWithA()
        val token = "tok1"
        room.hostMove(FakeMove(start.hands[0].first()))
        room.onDisconnect("a")
        h.now = Harness.WINDOW - 1
        room.tick()
        assertEquals(1, room.state!!.turn, "inside the window no bot plays the dropped seat")

        val out = h.hello("a2", token = token)
        assertEquals(room.state!!.hands[1], out.views("a2").single().hand)
        assertEquals(SeatKind.PLAYER, out.welcome("a2").lobby.seats[1].kind)
        val next = room.onMessage("a2", move(room.state!!.hands[1].first()))
        assertEquals(0, next.views("a2").single().turn)
    }

    @Test
    fun givenGameDrop_whenWindowPasses_thenBotTakesOverAndTokenDead() {
        val start = startWithA()
        room.hostMove(FakeMove(start.hands[0].first()))
        room.onDisconnect("a")
        h.now = Harness.WINDOW
        val out = room.tick()

        assertEquals(SeatKind.BOT, out.lastLobby(HOST_CONN).seats[1].kind)
        assertEquals(0, room.state!!.turn)
        assertEquals(RefuseReason.RECONNECT_EXPIRED, refusal(h.hello("a2", token = "tok1"), "a2"))
    }

    @Test
    fun givenOldSocketStillOpen_whenSameSeatReconnects_thenOldSocketClosed() {
        h.hello("a")
        val out = h.hello("a2", token = "tok1")
        assertTrue(out.closed("a"))
        assertEquals(1, out.welcome("a2").lobby.yourSeat)
        assertEquals(emptyList(), room.onDisconnect("a"), "the old socket closing later changes nothing")
        assertEquals(2, room.seatCount)
    }

    @Test
    fun givenUnknownConnection_whenDisconnects_thenNothing() {
        assertEquals(emptyList(), room.onDisconnect("ghost"))
    }

    // Host leaves

    @Test
    fun givenPlayersAndGame_whenHostLeaves_thenEveryoneToldAndClosed() {
        startWithA()
        val out = room.hostLeave()

        assertEquals(Phase.CLOSED, room.phase)
        assertEquals(listOf<HostMsg>(HostMsg.RoomClosed(CloseReason.HOST_LEFT)), out.to("a"))
        assertTrue(out.closed("a"))
        assertFalse(out.closed(HOST_CONN))
        assertEquals(emptyList(), h.hello("late"))
        assertEquals(emptyList(), room.onMessage("a", move("C0Z")))
        assertEquals(emptyList(), room.tick())
    }

    @Test
    fun givenLobby_whenHostLeaves_thenDroppedSeatsAreNotSentAnything() {
        h.hello("a"); h.hello("b")
        room.onDisconnect("b")
        val out = room.hostLeave()
        assertTrue(out.closed("a"))
        assertNull(out.filterIsInstance<RoomOutput.Send>().firstOrNull { it.conn == "b" })
    }
}
