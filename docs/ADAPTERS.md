# Writing a game adapter (NearbyGame)

For the uno and satti sessions. Library: `com.github.narayan07feb.forgebyte-nearby:nearby:v0.2.0` (ship only on
v0.2.0 or later). The host phone is the referee: it keeps the full state, applies every move and sends each seat only
`viewFor(state, seat)`.

## Rules every adapter must follow

1. **`newGame(seats, seed)` is deterministic.** Shuffle with `Random(seed)`, never `shuffled()` with no argument, so a
   seeded replay matches the game's own offline path (PLAN test 2, rules parity).
2. **`apply` checks everything a cheating joiner could send:** right seat's turn, game not over, the card is in that
   seat's hand, and the move is legal. Return `Result.failure(...)` (the message is shown to that player); never throw,
   never quietly drop an impossible card.
3. **`viewFor` holds only what that seat may see:** its own hand, other seats' card counts, the public table. Watch for
   whole-player objects in the state (a `winner: Player` carries a hand). Write a test like `HiddenInfoTest`: for
   every view, no card of another seat's hand appears in the JSON.
4. **`botMove` returns `null` only when that seat has nothing to do now** (not its turn, or game over). A pass is a
   real move: return it as one.
5. **The room runs the bots.** Do not use the game's own "bot thinking" phase or loop inside the adapter; bots are
   seats the room asks through `botMove`.
6. **`isOver`** is true exactly when the game has ended (the room then shows `Phase.OVER`).

## Satti: fit check (read-only, 2026-11-03, satti main)

`shared/.../domain`: `GameEngine`, `GameState` (serializable), `Card(suit, value)`, `SuitPile`, `SmartAI`, `EasyAI`.

| NearbyGame | Satti | Watch out |
|---|---|---|
| `State` | `GameState` | `players[i].hand` holds every hand: host only |
| `Move` | new `@Serializable data class SattiMove(val card: Card?)` | `card = null` is a pass |
| `SeatView` | new view: own hand, other hand sizes, names, `piles`, `currentPlayerIndex`, winner seat index | `GameState.winner` is a whole `Player` with a hand (a stuck-table winner still holds cards): send only the seat index |
| `newGame` | `Deck.createFullDeck().shuffled(Random(seed))`, `GameEngine.dealCards`, players `isHuman = true`, start at `findStartingPlayerIndex` | `GameEngine.createShuffledDeck()` has no seed: do not use it |
| `apply` | card: check `card in hand` and `card in engine.getValidMoves(hand, piles)`, then `applyMove`; pass: check `engine.canPass(hand, piles)`, then `applyPass` | `applyMove` does not check the hand (it filters the card out, so a card the player does not hold would still go on a pile); `applyPass` and `SuitPile.place` throw on illegal input |
| `viewFor` | build from `GameState` | never include `players[j].hand` for `j != seat` |
| `botMove` | `SmartAI().selectCard(hand, piles, engine)` wrapped as `SattiMove(it)` when it is that seat's turn | a `null` card from the AI is a pass: return `SattiMove(null)`, not `null` |
| `isOver` | `state.phase == GamePhase.GameOver` | |

Mark every seat `isHuman = true` so `applyMove` never switches to `BotThinking`; the room decides who is a bot.
`GameEngine` logs every call through `AppLogger`; fine on the host.

## Gaps in the library (to decide)

- **Bot pacing.** Room bots move inside the same call, at once. Satti's own bots "think" 1.2 s, so in a nearby game
  several bot moves would land as one jump. Proposal: `NearbyHost` plays bots one move at a time with a configurable
  delay (default about 1 s).
- **Turn timer.** A connected player who never moves blocks the game (only a drop reaches the reconnect window).
  Proposal: optional `RoomConfig.turnTimeoutMs`; when it runs out the host plays that seat's `botMove` (a pass in
  Satti), like the online games' turn timer.
