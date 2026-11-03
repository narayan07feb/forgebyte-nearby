package com.forgebyte.nearby.demo

import com.forgebyte.nearby.NearbyGame
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
public data class DemoState(val hands: List<List<String>>, val pile: List<String>, val turn: Int)

@Serializable
public data class DemoMove(val card: String)

@Serializable
public data class DemoView(val hand: List<String>, val otherCounts: List<Int>, val pile: List<String>, val turn: Int)

/** Each seat gets 7 cards; on your turn play any card you hold; the first empty hand ends the game. */
public class DemoGame : NearbyGame<DemoState, DemoMove, DemoView> {
    override val gameId: String = ID
    override val moveSerializer: KSerializer<DemoMove> = DemoMove.serializer()
    override val viewSerializer: KSerializer<DemoView> = DemoView.serializer()

    override fun newGame(seats: Int, seed: Long): DemoState {
        val deck = (0 until seats * CARDS).map { "C${it}Z" }.shuffled(Random(seed))
        return DemoState(deck.chunked(CARDS), emptyList(), turn = 0)
    }

    override fun apply(state: DemoState, seat: Int, move: DemoMove): Result<DemoState> = when {
        seat != state.turn -> Result.failure(IllegalStateException("Not your turn."))
        move.card !in state.hands[seat] -> Result.failure(IllegalStateException("You do not hold that card."))
        else -> Result.success(
            DemoState(
                hands = state.hands.mapIndexed { i, h -> if (i == seat) h - move.card else h },
                pile = state.pile + move.card,
                turn = (state.turn + 1) % state.hands.size,
            ),
        )
    }

    override fun viewFor(state: DemoState, seat: Int): DemoView = DemoView(
        hand = state.hands[seat],
        otherCounts = state.hands.mapIndexed { i, h -> if (i == seat) -1 else h.size },
        pile = state.pile,
        turn = state.turn,
    )

    override fun botMove(state: DemoState, seat: Int): DemoMove? =
        if (seat == state.turn && !isOver(state)) DemoMove(state.hands[seat].first()) else null

    override fun isOver(state: DemoState): Boolean = state.hands.any { it.isEmpty() }

    public companion object {
        public const val ID: String = "demo"
        public const val VERSION: Int = 1
        private const val CARDS = 7
    }
}
