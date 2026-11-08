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

## Seat back after the app was killed (ceo, 2026-11-07)

- Pass a `SeatStore` to `NearbyClient(..., seatStore = store)`. The client saves a `SavedSeat` on every welcome and
  clears it when the seat ends for good (left, kicked, refused, host left, unreachable). A kill does not clear it.
- On the next app start, if `store.load()` is not null, offer "Back to your game" and call `client.resume(saved)`. The
  room key is checked before the token is sent, so another host now on the same address never gets it
  (`EndReason.HostKeyMismatch`). The host gives the seat back, also after a bot took it over.
- The default store is memory only (a killed app loses the seat to a bot). Back it with private app storage, for
  example `SharedPreferences` holding `saved.toText()` and read back with `SavedSeat.parse`. The token is a seat
  secret: exclude that file from backup and device transfer (`android:dataExtractionRules` on Android 12+ and
  `android:fullBackupContent` before), so a restored or new phone never gets it.
- A killed host ends the room: there is nothing to resume, and the joiner ends `Unreachable`.
- `Reconnecting` never lasts more than about 2 minutes (`RETRY_FOR`), even when the host's address takes the
  connection and then hangs. After `Reconnecting`, `Ended(Unreachable)` means the host is gone: show "The host left.
  Back to the menu." with one button to the menu, not a retry spinner.

## Play again

There is no rematch in a room: after `Phase.OVER` the room is done. To play again, the host opens a new room and the
players join it (ceo, 2026-11-07: games open a new room).

## Motion for room screens (Compose recipe)

The library has no UI. These are the three moves a nearby screen needs and the values to use (Compose foundation
1.7 or newer for `animateItem`). Each one has a job: show that a room came or went, show what the join is waiting
for, and show who sat down. Use only the Compose
animation APIs below (no `delay` loops or timers for motion): they run on the frame clock and its
`MotionDurationScale`, so the games' test builds (which set `MotionDurationScale(1f)`) and a scale of 0 behave the
same as for every other animation in the game.

```kotlin
val EaseOutQuart = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)    // things that come or go
val EaseInOutCubic = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f) // things already on screen that move
fun <T> enter(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap<T>() else tween(240, easing = EaseOutQuart)
fun <T> exit(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap<T>() else tween(180, easing = EaseOutQuart)
fun <T> move(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap<T>() else tween(240, easing = EaseInOutCubic)
```

`reduce` is the game's own reduce-motion setting (or the system "remove animations" read by the game). With it on,
every change still happens, just without motion.

1. **Room list: a room comes or goes.** `discoverRooms` sends the whole list each time. Key the rows by `RoomAd.key`
   (one per advertised room), so a room whose free seats change keeps its row, and a new or gone room fades while the
   others slide:

   ```kotlin
   LazyColumn {
       items(rooms, key = { it.key }) { room ->
           RoomRow(room, Modifier.animateItem(fadeInSpec = enter(reduce), placementSpec = move(reduce), fadeOutSpec = exit(reduce)))
       }
   }
   ```

   Do not key by index or by room name (two rooms can share a name). The empty list ("No Wi-Fi?" help text) fades in
   with the same `enter` spec; do not animate the list when the player scrolls or moves focus with a TV remote.
2. **Join status.** Show one line per `ClientStatus` (Connecting, WaitingForHost with the check code, Connected,
   Reconnecting, Ended) and change it with a short crossfade plus a small rise (a sixth of the line height), never a
   slide across the screen:

   ```kotlin
   AnimatedContent(status, transitionSpec = {
       (fadeIn(enter(reduce)) + slideInVertically(enter(reduce)) { it / 6 }) togetherWith fadeOut(exit(reduce))
   }) { s -> StatusLine(s) }
   ```

   Keep the check code still while it shows (no pulse on it): the players read it out loud to each other.
3. **Seat fill.** Seats are positional (`Lobby.seats`, key by index). When a seat fills, empties or goes `connected =
   false`, animate only its colour and alpha, 180 ms (`animateColorAsState(target, exit(reduce))`). A new player's
   name can scale from 0.96 to 1 with `enter`; never from 0.

**One rule from uno's frame trace: no infinite pulse that redraws the whole screen.** A waiting dot or a
"Reconnecting" pulse is fine only in its own small layer: read the animated value inside `Modifier.graphicsLayer { }`
(or `drawBehind { }`), not in the composable body, so each frame redraws that one layer and recomposes nothing:

```kotlin
@Composable fun WaitingDot(color: Color, reduce: Boolean) {
    if (reduce) return Box(Modifier.size(8.dp).background(color, CircleShape)) // still dot, no transition running
    val pulse by rememberInfiniteTransition().animateFloat(1f, 0.4f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.background(color, CircleShape))
}
```

Stop it (leave the composition) as soon as the status changes. Animate alpha and translation, not size or padding, so
no frame re-measures the list. Check before a merge: `adb shell dumpsys gfxinfo <package>` on the Lava with the room
screen open shows no new janky frames compared with the build before the change.

## Adding nearby play to a game (setup and store checklist)

1. **Dependency.** Add JitPack to `settings.gradle.kts` (`maven("https://jitpack.io")`) and
   `com.github.narayan07feb.forgebyte-nearby:nearby:<tag>` to the shared module. Use the newest tag in the GitHub
   releases (v0.3.6 on 7 Nov). It does not lift the game's kotlin-stdlib above 2.2.20.
2. **Start nothing at app start.** Call `NearbyAndroid.init(context)` when the player opens nearby play, before
   `discoverRooms` or `advertise`. It is safe to call more than once. Without it, discovery fails with
   "Call NearbyAndroid.init(context) before nearby discovery on Android."
3. **Manifest.** The library merges two install-time permissions, `INTERNET` and `CHANGE_WIFI_MULTICAST_STATE` (no
   prompt, no "Nearby devices" permission). It also removes dns-sd-kt's startup initializer. Do not add it back.
4. **R8 (release builds).** Nothing to add. The library ships its own R8 rules (`nearby/consumer-rules.pro`, from
   v0.3.6), and the sample's minified release build has no rules of its own. On v0.3.5 or older,
   add `-dontwarn java.lang.management.**` to the game's `proguard-rules.pro`.
5. **Room name.** `RoomConfig.roomName` goes unencrypted to the local network, so never put a player name in it.
   The library cuts a player name to 20 characters and a room name to 40, on whole characters (v0.3.6), so keep the
   game's own limit at or below these.
6. **Your own room.** Discovery also lists the room this phone is hosting. Do not offer Join while the phone hosts.
7. **Play again.** There is no rematch in the library. After `Phase.OVER`, the host opens a new room and the players
   join it again.
8. **iOS (week 3, not built yet).** The plan is `NSLocalNetworkUsageDescription` and `NSBonjourServices` with
   `_forgebyte._tcp` in `Info.plist`.
9. **Debug log (optional, debug builds only).** `NearbyTrace.sink = { Log.d("NearbyTrace", it) }` shows the
   library's connect, key exchange, drop and retry lines, so a join that hangs on a phone can be read from logcat. The
   lines hold no keys, codes, names, moves or views. Leave it null in release builds.

**Play Data safety and Families: guidance only, check with the owner before filing.** These are facts about the
library, not answers for the form:

- Nothing leaves the local network. The library sends nothing to a server, keeps no account and no analytics.
- Players see each other's display names. Names, moves and views are encrypted end to end between the phones. The
  discovery advert goes in clear text to the local network: game id, game and protocol version, room name and free
  seats.
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
