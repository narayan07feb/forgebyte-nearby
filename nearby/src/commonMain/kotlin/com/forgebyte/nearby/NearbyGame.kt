package com.forgebyte.nearby

import kotlinx.serialization.KSerializer

/**
 * The rules a game plugs into a nearby room. The host phone is the referee: it keeps [State], applies every move
 * and sends each seat only [viewFor] that seat, so a joiner never receives another player's hand.
 */
public interface NearbyGame<State, Move, SeatView> {
    /** "uno" or "satti". A joiner of another game is refused. */
    public val gameId: String
    public val moveSerializer: KSerializer<Move>
    public val viewSerializer: KSerializer<SeatView>

    public fun newGame(seats: Int, seed: Long): State

    /** Applies [move] for [seat], or fails when the move is illegal (wrong turn, card not held, ...). */
    public fun apply(state: State, seat: Int, move: Move): Result<State>

    /** What [seat] may see. Must not hold any card or secret of another seat. */
    public fun viewFor(state: State, seat: Int): SeatView

    /** The move a bot plays for [seat], or null when [seat] has nothing to do now (not its turn). */
    public fun botMove(state: State, seat: Int): Move?

    public fun isOver(state: State): Boolean
}
