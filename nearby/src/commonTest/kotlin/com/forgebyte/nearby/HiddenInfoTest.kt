package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays whole matches (host, two joiners, a bot; a drop and reconnect mid-game) and reads every message the host
 * sends. A message to seat N must not hold a card in another seat's hand or another seat's reconnect token.
 */
class HiddenInfoTest {
    private class Match(game: FakeGame, private val seed: Long) {
        val h = Harness(game)
        val leaks = mutableListOf<String>()
        private val seatOfConn = mutableMapOf(HOST_CONN to 0)
        private val tokenOfSeat = mutableMapOf<Int, String>()

        fun check(out: List<RoomOutput>) {
            for (send in out.filterIsInstance<RoomOutput.Send>()) {
                (send.msg as? HostMsg.Welcome)?.let {
                    seatOfConn[send.conn] = it.lobby.yourSeat
                    tokenOfSeat[it.lobby.yourSeat] = it.reconnectToken
                }
            }
            val hands = h.room.state?.hands
            for (send in out.filterIsInstance<RoomOutput.Send>()) {
                val seat = seatOfConn[send.conn] ?: continue // refused before getting a seat
                val text = NearbyProtocol.encode(send.msg)
                hands?.forEachIndexed { other, hand ->
                    if (other != seat) hand.filter { "\"$it\"" in text }
                        .forEach { leaks += "seat $seat got seat $other's card $it in $text" }
                }
                tokenOfSeat.filterKeys { it != seat }.forEach { (other, token) ->
                    if ("\"$token\"" in text) leaks += "seat $seat got seat $other's token in $text"
                }
            }
        }

        fun play(): Phase {
            val room = h.room
            check(h.hello("a")); check(h.hello("b"))
            check(room.addBot())
            check(room.onMessage("a", ClientMsg.Ready(true)))
            check(room.onMessage("b", ClientMsg.Ready(true)))
            check(room.start(seed))
            var connA = "a"
            var moves = 0
            while (room.phase == Phase.PLAYING) {
                val s = room.state!!
                when (s.turn) {
                    0 -> check(room.hostMove(FakeMove(s.hands[0].first())))
                    1 -> {
                        if (moves == 2) { // drop and come back inside the window
                            check(room.onDisconnect(connA))
                            connA = "a-again"
                            check(h.hello(connA, token = tokenOfSeat.getValue(1)))
                        }
                        check(room.onMessage(connA, move(s.hands[1].first())))
                    }
                    2 -> {
                        check(room.onMessage("b", move(s.hands[0].first()))) // someone else's card: rejected
                        check(room.onMessage("b", move(s.hands[2].first())))
                    }
                    else -> error("the bot seat should never be waited on")
                }
                moves++
            }
            check(h.hello("late")) // refused, must not leak either
            return room.phase
        }
    }

    @Test
    fun givenManySeededMatches_whenEveryMessageRead_thenNoSeatSeesAnotherSeatsCardsOrToken() {
        for (seed in 1L..25L) {
            val match = Match(FakeGame(cardsEach = 5), seed)
            assertEquals(Phase.OVER, match.play(), "seed $seed")
            assertEquals(emptyList(), match.leaks, "seed $seed")
        }
    }

    @Test
    fun givenGameThatShowsAllHands_whenChecked_thenLeaksAreFound() {
        val match = Match(LeakyGame(), seed = 1)
        match.play()
        assertTrue(match.leaks.any { "card" in it }, "the check must be able to fail")
    }
}
