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

## Ways to join (screens)

- **Room found nearby:** `discoverRooms(gameId)` lists rooms; `requestJoin(room, name)` sends a join request.
- **Short code:** the host shows `host.shortCodes(port)` (six characters, such as `K7Q-2MX`); the joiner types it and
  calls `NearbyClient.requestJoin(ShortCode.parse(text) ?: return, name)`. It only finds the host on the joiner's own
  Wi-Fi (same first three address bytes) and carries no secret, so it is a join request too: the host accepts or
  declines it and both screens show the same check code. A null from `parse` means a typo: ask the player to check the
  code. It ends `EndReason.Unreachable` when the phones are on different networks.
- **Full code or QR:** `host.joinCodes(port)` and `NearbyClient.join(code, name)`. It holds the room secret, so it
  seats the joiner with no host approval: show it only to the people at the table.

For a request (nearby or short code), show `checkCode` on the joiner while it waits, and the request's `checkCode`
next to Accept/Decline on the host.

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

## Bot pacing and turn timer (owner, 2026-11-03; v0.3.0, PR #8)

- **Bot pacing:** the room plays bots one move at a time, `RoomConfig.botDelayMs` apart (default 1 s; 0 = all at
  once). Do not add delays in the adapter.
- **Turn timer:** `RoomConfig.turnTimeoutMs` (default 30 s; 0 = off). When a seat's time runs out the host plays that
  seat's `botMove` for it, so `botMove` must give a sensible move for a human seat too (Satti: a card or a pass;
  Crazy Eights: a bot move or a draw).
- **Countdown:** show `NearbyHost.turnTimer` / `NearbyClient.turnTimer` (`seat`, `remaining`); read `remaining` on
  each frame. No ticking messages are sent.
- **Whose turn:** override `currentSeat(state, seatCount)` with your engine's current player (Satti:
  `currentPlayerIndex` unless the game is over). The default asks `botMove` for every seat after each change.
- **Off-turn bot moves (v0.3.1):** if bots may act out of turn (Crazy Eights: catch an uncalled last card, jump in),
  override `nextBotMove(state, botSeats, seatCount)` with your own bot order and return `BotMove(seat, move)` (uno:
  `Bots.nextMove`, reading the seat off the move). The room plays it `botDelayMs` later unless another move lands
  first, then asks again. Satti keeps the default (current seat only).
- **Race results:** `apply` just refuses a move that is no longer legal; the room tells a player who lost a race "Too
  late, another player already did that." and sends a fresh view. Do not add your own race handling.
- **Which view the move was picked on (after v0.3.2):** draw the table from `seqView` (`SeqView(seq, view)`, on both
  `NearbyHost` and `NearbyClient`) and send moves with `move(move, seenSeq = shown.seq)`, where `shown` is the
  `SeqView` the player was looking at. A newer view can arrive between drawing and the tap (the turn timer played for
  the player, a bot caught a card); with the seq the room answers "Too late" instead of the game's own error.
  Without it, `move(move)` uses the newest view received, as before.
