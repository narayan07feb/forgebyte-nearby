# Writing a game adapter (NearbyGame)

For the uno and satti sessions. Library: `com.github.narayan07feb.forgebyte-nearby:nearby` (ship only on v0.2.0 or
later; setup below). The host phone is the referee: it keeps the full state, applies every move and sends each seat
only `viewFor(state, seat)`.

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

**No Wi-Fi.** A phone hotspot is a shared network like any other, so every join way above works on it. The library
does not start a hotspot (the in-app hotspot is parked, owner 7 Nov). Put this help text on the nearby screen, next
to the empty room list:

> No Wi-Fi? Turn on a phone hotspot in Settings, then the others join it.

The phone with the hotspot can host or join. A match on a hotspot is an owner test (it needs a settings change).

## Adding nearby play to a game (setup and store checklist)

1. **Dependency.** Add JitPack to `settings.gradle.kts` (`maven("https://jitpack.io")`) and
   `com.github.narayan07feb.forgebyte-nearby:nearby:<tag>` to the shared module. Use the newest tag in the GitHub
   releases (v0.3.5 on 7 Nov). It does not lift the game's kotlin-stdlib above 2.2.20.
2. **Start nothing at app start.** Call `NearbyAndroid.init(context)` when the player opens nearby play, before
   `discoverRooms` or `advertise`. It is safe to call more than once. Without it, discovery fails with
   "Call NearbyAndroid.init(context) before nearby discovery on Android."
3. **Manifest.** The library merges two install-time permissions, `INTERNET` and `CHANGE_WIFI_MULTICAST_STATE` (no
   prompt, no "Nearby devices" permission). It also removes dns-sd-kt's startup initializer. Do not add it back.
4. **R8 (release builds).** Nothing to add. The library ships its own R8 rules (`nearby/consumer-rules.pro`, from
   the next tag after v0.3.5), and the sample's minified release build has no rules of its own. On v0.3.5 or older,
   add `-dontwarn java.lang.management.**` to the game's `proguard-rules.pro`.
5. **Room name.** `RoomConfig.roomName` goes unencrypted to the local network, so never put a player name in it.
   The library cuts a player name to 20 characters and a room name to 40. Until PR #30 is in a tag, that cut can split
   an emoji in two, so keep the game's own limit at or below these.
6. **Your own room.** Discovery also lists the room this phone is hosting. Do not offer Join while the phone hosts.
7. **Play again.** There is no rematch in the library. After `Phase.OVER`, the host opens a new room and the players
   join it again.
8. **iOS (week 3, not built yet).** The plan is `NSLocalNetworkUsageDescription` and `NSBonjourServices` with
   `_forgebyte._tcp` in `Info.plist`.

**Play Data safety and Families: guidance only, check with the owner before filing.** These are facts about the
library, not answers for the form:

- Nothing leaves the local network. The library sends nothing to a server, keeps no account and no analytics.
- Players see each other's display names. Names, moves and views are encrypted end to end between the phones. The
  discovery advert goes in clear text to the local network: game id, game and protocol version, room name, free
  seats and the host's local IPv4 addresses (the network already sees those).
- Satti (Families): no data goes to other players beyond the display name typed in the room (and the moves).

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
