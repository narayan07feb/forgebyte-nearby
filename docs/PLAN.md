# forgebyte-nearby: offline play on the same Wi-Fi or hotspot

Owner ask (2026-11-02 22:00): "we can also run the app in intranet instead of internet. a group creator can ask
other to join its network ... for running offline ... for uno and satti". 2026-11-03: "plan intra net playing for
uno and satti and start implementation from today", "please create a module so both can share".

Owner decisions (2026-11-03):
- One shared module in its own repo (`narayan07feb/forgebyte-nearby`), versioned by git tag and pulled from JitPack,
  the same way Crazy Eights uses FrameReady.
- Android first; iOS in week 3.
- A `nearby` session builds this module; the `uno` and `satti` sessions add "Play nearby" to their games; ceo
  coordinates and runs the phone tests.

## What it is

Kotlin Multiplatform library (Android now, iOS later). No internet, no Supabase, no accounts.

1. **Discovery.** The host advertises `_forgebyte._tcp` on the local network (Android `NsdManager`, later iOS
   Bonjour) with TXT fields: game id (`uno`/`satti`), protocol version, room name, seats free. Joiners see a list of
   rooms nearby. Fallback when discovery is blocked (some routers, hotspots): the host screen shows a QR code and a
   short code (IP + port + room secret); the joiner scans or types it.
2. **Transport.** The host phone runs a small WebSocket server (Ktor server CIO, Ktor 3.1.0 like both games);
   joiners connect with the Ktor client. Same Wi-Fi, or the host's hotspot. Not Wi-Fi Direct (Android only).
3. **Room.** Seats, names, bots, ready/start, leave, kick, a reconnect window (same seat comes back after a drop,
   by a per-seat token), host leaves = room ends cleanly for everyone. Protocol and game version check on join:
   a mismatched app is refused with a clear message.
4. **Host is the referee.** The game plugs in its rules through one interface; the host applies every move and
   sends each seat only what that seat may see. A joiner never receives another player's hand.

```kotlin
interface NearbyGame<State, Move, SeatView> {
    val gameId: String                       // "uno" or "satti"
    val moveSerializer: KSerializer<Move>
    val viewSerializer: KSerializer<SeatView>
    fun newGame(seats: Int, seed: Long): State
    fun apply(state: State, seat: Int, move: Move): Result<State>   // refuse illegal moves
    fun viewFor(state: State, seat: Int): SeatView
    fun botMove(state: State, seat: Int): Move?                     // host runs bots
    fun isOver(state: State): Boolean
}
```

   - Crazy Eights plugs in `engine` (`UnoRules`, `Move`, `SeatView`) and `ai` for bots.
   - Satti plugs in its `shared` rules (`PlayCardUseCase`, `RunBotTurnsUseCase`).
5. **Not in scope.** Web (browsers cannot host or discover), Wi-Fi Direct, internet relay, accounts, chat.

## Permissions and store

- Android: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (multicast lock for NSD),
  `INTERNET` (local sockets). NSD needs no "Nearby devices" permission; check this on Android 16 before release.
- iOS (week 3): `NSLocalNetworkUsageDescription` + `NSBonjourServices` in Info.plist.
- Play Data safety: no new data leaves the phone (local network only); re-check before release. Satti: Families
  check (no data sent to other players beyond the display name typed in the room).

## Order of work

| When | Who | What |
|---|---|---|
| Day 1 (3 Nov) | nearby | Repo scaffold (KMP lib, Android target, JitPack `jitpack.yml`, Kotlin 2.2.20, Ktor 3.1.0); protocol messages; room state machine in commonMain with a fake test game; unit tests |
| Week 1 | nearby | Ktor host server + client, NSD advertise/discover, QR/code fallback, tag `0.1.0` |
| Week 1 | uno | `NearbyGame` adapter over `UnoRules` + "Play nearby" menu (host / join / lobby), behind a debug flag first |
| Week 2 | satti | `NearbyGame` adapter over Satti rules + "Play nearby" screens |
| Week 2 | nearby | Reconnect window, host-leaves flow, version refusal, battery/heat check |
| Week 3 | nearby + games | iOS (Bonjour, Local network permission), Data safety, closed-test release |

## How we test

1. **Unit tests (Mac, every commit, `studio/tools/ci-local.sh`)**: room state machine (join, full room, leave,
   kick, reconnect inside and outside the window, host leaves, wrong version, wrong room secret); message encoding
   round trips; "no hidden information": for every message the host sends to seat N, assert it holds no card of
   another seat.
2. **Rules parity (each game)**: replay seeded games through the `NearbyGame` adapter and through the game's own
   offline path; final states must match move for move. Bots only, many seeds.
3. **Loopback match (Mac, JVM)**: one host + 3 clients over localhost WebSocket play full bot matches; inject
   drops (close a socket mid-turn, delay messages) and check every client ends with the same state as the host.
4. **Real devices**:
   - Lava as host + Android emulator as joiner on the same Wi-Fi (emulator only with the owner's yes for that run).
   - Lava as host + Mac JVM test client joining over Wi-Fi (no second phone needed).
   - Lava hotspot + a second real phone (owner's or borrowed: we have one test phone).
   Each run: room shows up in the list, QR join works, full match to the end, Wi-Fi off/on mid-game (reconnect),
   host leaves, move round-trip time (target under 150 ms on Wi-Fi).
5. **Before release**: usual Lava checks (no crash/ANR in the buffer, 20-minute session battery and temperature),
   then the closed tests.

## Rules for this repo

- No GitHub Actions; tests run locally (`./gradlew allTests` or the ci-local lane).
- No AI or Co-Authored trailers in commits; task branches and draft PRs; owner or ceo merges.
- Public repo (JitPack): no secrets, keys, tester data or analytics ids, ever.
- Easy language in docs and PRs, no em dash.
