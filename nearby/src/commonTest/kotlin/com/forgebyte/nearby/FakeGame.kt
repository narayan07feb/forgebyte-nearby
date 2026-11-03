package com.forgebyte.nearby

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class FakeState(val hands: List<List<String>>, val pile: List<String>, val turn: Int)

@Serializable
data class FakeMove(val card: String)

@Serializable
data class FakeView(val hand: List<String>, val otherCounts: List<Int>, val pile: List<String>, val turn: Int)

/** A tiny shedding game: on your turn play any card you hold; the first empty hand ends it. Cards are "C<n>Z". */
open class FakeGame(private val cardsEach: Int = 3) : NearbyGame<FakeState, FakeMove, FakeView> {
    override val gameId = "fake"
    override val moveSerializer: KSerializer<FakeMove> = FakeMove.serializer()
    override val viewSerializer: KSerializer<FakeView> = FakeView.serializer()

    override fun newGame(seats: Int, seed: Long): FakeState {
        val deck = (0 until seats * cardsEach).map { "C${it}Z" }.shuffled(Random(seed))
        return FakeState(deck.chunked(cardsEach), emptyList(), turn = 0)
    }

    override fun apply(state: FakeState, seat: Int, move: FakeMove): Result<FakeState> = when {
        seat != state.turn -> Result.failure(IllegalStateException("Not your turn."))
        move.card !in state.hands[seat] -> Result.failure(IllegalStateException("You do not hold that card."))
        else -> Result.success(
            FakeState(
                hands = state.hands.mapIndexed { i, h -> if (i == seat) h - move.card else h },
                pile = state.pile + move.card,
                turn = (state.turn + 1) % state.hands.size,
            ),
        )
    }

    override fun viewFor(state: FakeState, seat: Int) = FakeView(
        hand = state.hands[seat],
        otherCounts = state.hands.mapIndexed { i, h -> if (i == seat) -1 else h.size },
        pile = state.pile,
        turn = state.turn,
    )

    override fun botMove(state: FakeState, seat: Int) =
        if (seat == state.turn && !isOver(state)) FakeMove(state.hands[seat].first()) else null

    override fun isOver(state: FakeState) = state.hands.any { it.isEmpty() }
}

/** Shows every hand to everyone. Only used to prove the hidden-information check can fail. */
class LeakyGame : FakeGame() {
    override fun viewFor(state: FakeState, seat: Int) =
        FakeView(state.hands.flatten(), emptyList(), state.pile, state.turn)
}
