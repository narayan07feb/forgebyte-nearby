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

    /**
     * The seat that must act now, or null when nobody must (game over). Override it with a cheap lookup (Satti:
     * `currentPlayerIndex`): the default asks [botMove] for every seat, which runs the bot up to [seatCount] times
     * after each change (bot pacing and the turn timer need it every time).
     */
    public fun currentSeat(state: State, seatCount: Int): Int? = (0 until seatCount).firstOrNull { botMove(state, it) != null }

    /**
     * The next move any of [botSeats] wants to make now, on its turn or off it (Crazy Eights: catch an uncalled last
     * card, jump in), or null when none does. The room asks once after every applied move and plays the answer
     * [RoomConfig.botDelayMs] later, unless another move lands first. The default only plays the current seat's turn;
     * a game with off-turn moves overrides it with its own bot order (uno: `Bots.nextMove`).
     */
    public fun nextBotMove(state: State, botSeats: Set<Int>, seatCount: Int): BotMove<Move>? =
        currentSeat(state, seatCount)?.takeIf { it in botSeats }?.let { seat -> botMove(state, seat)?.let { BotMove(seat, it) } }
}

/** A bot seat and the move it wants to make. */
public data class BotMove<Move>(val seat: Int, val move: Move)
