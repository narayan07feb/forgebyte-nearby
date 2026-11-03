package com.forgebyte.nearby

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class SnapState(val hands: List<List<String>>, val pile: List<String>, val turn: Int, val snapOpen: Boolean, val snaps: List<Int>)

@Serializable
sealed interface SnapMove {
    @Serializable @SerialName("play") data class Play(val card: String) : SnapMove

    /** Off turn: anyone may snap while the window is open; with [takeTurn] the snapper also takes the turn (a jump-in). */
    @Serializable @SerialName("snap") data class Snap(val takeTurn: Boolean = false) : SnapMove
}

@Serializable
data class SnapView(val hand: List<String>, val pile: List<String>, val turn: Int, val snapOpen: Boolean, val snaps: List<Int>)

/**
 * FakeGame with an off-turn move, like Crazy Eights' catch and jump-in: playing an even card ("C4Z") opens a snap
 * window any seat may take. Bots snap off turn (seat order after the current seat), [jump] makes their snap take the turn.
 */
class SnapGame(private val cardsEach: Int = 6, private val jump: Boolean = false) : NearbyGame<SnapState, SnapMove, SnapView> {
    override val gameId = "fake"
    override val moveSerializer: KSerializer<SnapMove> = SnapMove.serializer()
    override val viewSerializer: KSerializer<SnapView> = SnapView.serializer()

    override fun newGame(seats: Int, seed: Long): SnapState {
        val deck = (0 until seats * cardsEach).map { "C${it}Z" }.shuffled(Random(seed))
        return SnapState(deck.chunked(cardsEach), emptyList(), turn = 0, snapOpen = false, snaps = List(seats) { 0 })
    }

    override fun apply(state: SnapState, seat: Int, move: SnapMove): Result<SnapState> = when (move) {
        is SnapMove.Play -> when {
            seat != state.turn -> Result.failure(IllegalStateException("Not your turn."))
            move.card !in state.hands[seat] -> Result.failure(IllegalStateException("You do not hold that card."))
            else -> Result.success(
                state.copy(
                    hands = state.hands.mapIndexed { i, h -> if (i == seat) h - move.card else h },
                    pile = state.pile + move.card,
                    turn = (state.turn + 1) % state.hands.size,
                    snapOpen = even(move.card),
                ),
            )
        }
        is SnapMove.Snap -> if (!state.snapOpen) {
            Result.failure(IllegalStateException("Nothing to snap."))
        } else {
            Result.success(
                state.copy(
                    snapOpen = false,
                    snaps = state.snaps.mapIndexed { i, n -> if (i == seat) n + 1 else n },
                    turn = if (move.takeTurn) seat else state.turn,
                ),
            )
        }
    }

    override fun viewFor(state: SnapState, seat: Int) = SnapView(state.hands[seat], state.pile, state.turn, state.snapOpen, state.snaps)

    override fun botMove(state: SnapState, seat: Int): SnapMove? =
        if (seat == state.turn && !isOver(state)) SnapMove.Play(state.hands[seat].first()) else null

    override fun isOver(state: SnapState) = state.hands.any { it.isEmpty() }

    override fun currentSeat(state: SnapState, seatCount: Int): Int? = state.turn.takeIf { !isOver(state) }

    override fun nextBotMove(state: SnapState, botSeats: Set<Int>, seatCount: Int): BotMove<SnapMove>? {
        if (isOver(state)) return null
        if (state.snapOpen) {
            val order = (1..seatCount).map { (state.turn + it) % seatCount }
            order.firstOrNull { it in botSeats }?.let { return BotMove(it, SnapMove.Snap(takeTurn = jump)) }
        }
        return state.turn.takeIf { it in botSeats }?.let { BotMove(it, botMove(state, it)!!) }
    }

    companion object {
        fun even(card: String) = card.removePrefix("C").removeSuffix("Z").toInt() % 2 == 0
    }
}
