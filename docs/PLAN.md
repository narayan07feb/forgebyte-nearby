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

## Libraries we build on (owner pick, 2026-11-03)

Owner: "instead of creating our own solution find it on github for nearby kmp support so no need to create from
scratch". ceo searched GitHub; the owner picked:

- **Discovery: `com.appstractive:dns-sd-kt`** (Appstractive/dns-sd-kt, Apache-2.0). Android NSD, iOS Bonjour and JVM
  JmDNS behind one KMP API. The JVM side lets a Mac test client find the Lava. We do not write our own NSD or mDNS code.
- **Connection: Ktor WebSockets** (server CIO on the host phone, client on joiners), Ktor 3.1.0 like both games. We do
  not write our own socket framing.
- **Ours:** the room state machine, protocol messages, `NearbyGame`, per-seat views and reconnect. No KMP library
  covers card-game rooms with a host referee and hidden hands; kuilt and P2pKit were looked at and are too new.
- Check before building on it: dns-sd-kt is built with Kotlin 2.3.20 and we use 2.2.20. It must resolve and compile
  for Android (and later the iOS klib). If it does not, report to ceo; do not fork it.
- dns-sd-kt check result (2026-11-03): 1.1.2 compiles for Android with Kotlin 2.2.20. Its iOS klib does not ("can
  consume libraries having ABI version <= 2.2.0"): 1.1.0 to 1.1.2 are built with Kotlin 2.3.20. 1.0.4 (Kotlin 2.1.0)
  compiles on both but misses later fixes, including a crash resolving services on Android below SDK 30 (our minSdk is
  24). Decision (ceo, option A): use 1.1.2 now, since weeks 1 and 2 are Android only. iOS is decided in week 3: ceo
  leans to moving uno and satti to Kotlin 2.3.20 then (this also unblocks firebase-bom 35); pinning 1.0.4 for iOS is
  the fallback.
- Side effects of dns-sd-kt 1.1.2 inside a game: kotlin-stdlib goes up to 2.3.20, and appcompat 1.7.1 and
  androidx.startup 1.2.0 are added at runtime. The uno session checks these inside its app when it adds 0.1.0.
- Seat drop past the reconnect window mid-game: a host bot takes the seat; in the lobby the seat is freed (owner
  approved 2026-11-03).

## What it is

Kotlin Multiplatform library (Android now, iOS later). No internet, no Supabase, no accounts.

1. **Discovery.** The host advertises `_forgebyte._tcp` on the local network (Android `NsdManager`, later iOS
   Bonjour) with TXT fields: game id (`uno`/`satti`), protocol version, room name, seats free. Joiners see a list of
   rooms nearby. Fallback when discovery is blocked (some routers, hotspots): the host screen shows a QR code and a
   short code (IP + port + room code); the joiner scans or types it. See "Joining" below.
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

## Joining: the host approves each join (owner, 2026-11-03)

- Rooms found nearby show in the list. Tapping one sends a join request with the player's name only; the host taps
  Accept or Decline. A request the host does not answer in 60 seconds is refused, and at most 4 requests wait at a time
  (more are refused straight away), so a flood cannot fill the host's screen.
- The QR or typed code (IP, port, room code) joins at once without a request.
- The mDNS TXT record never carries the room code: only game id, protocol version, game version, room name and seats
  free. A unit test fails if anything else is added.
- A seat that drops comes back with its reconnect token alone, whichever way it joined.
- Pending requests are refused when the game starts, and told "room closed" when the host leaves.

## Bot pacing and turn timer (owner, 2026-11-03)

- **Bot pacing:** bots play one move at a time, `RoomConfig.botDelayMs` apart (default 1 s, per game; 0 = all at
  once). Each bot move sends everyone a new view, so joiners see every move.
- **Turn timer:** `RoomConfig.turnTimeoutMs` (default 30 s; the host can set 0 when making the room). When a player's
  (or the host's) time runs out, the host plays that seat's `botMove` (Satti: a card or a pass; Crazy Eights: a bot
  move or a draw), like online play. A move that arrives at the deadline or later is too late. A dropped seat's clock
  keeps running.
- **Countdown:** each `View` carries `turn = (seat, endsInMs)`, the time left when the host sent it (not a clock time,
  since the phones' clocks differ); `NearbyHost.turnTimer` and `NearbyClient.turnTimer` turn it into a local deadline.
  No ticking messages.
- **Whose turn** comes from `NearbyGame.currentSeat(state, seatCount)`. Its default asks `botMove` for every seat
  (works for any adapter, but runs the bot up to N times per change); games should override it with a cheap lookup
  (ceo note, 2026-11-03).
- **Off-turn bot moves** (ceo, 2026-11-03, v0.3.1): `NearbyGame.nextBotMove(state, botSeats, seatCount)` returns the
  next move any bot wants, on or off turn (Crazy Eights: catch an uncalled last card, jump in); its default plays only
  the current seat, so Satti is unchanged; uno overrides it with its own `Bots.nextMove`. The room asks once after every
  applied move and plays the answer `botDelayMs` later without asking the AI again; any move landing first (a human's
  catch, the turn timer's auto-move, another bot) replaces the plan. In instant mode (0 ms) bots loop until none wants
  to move, capped at 10 000 moves (the cap is traced and stops the loop; it never throws or ends the room).
- **Fairness:** a human who acts within `botDelayMs` of the state that allows a catch wins; ties go by arrival at the
  host. The loser's move carries the seq of the view it was made on (`ClientMsg.Move.seenSeq`, optional), so the room
  answers "Too late, another player already did that." and sends the table as it is now, instead of the game's
  "illegal move" text. Joiners carry their network delay (about 25 to 50 ms) against the host's own player; a separate
  off-turn delay waits until testers ask.
- **Turn clock with off-turn moves:** the human whose turn it is keeps their deadline until the turn moves to another
  seat or they move themselves; an off-turn bot catch does not eat or reset it; a jump-in that takes the turn starts
  the next human's clock.
- **Takeover is not final:** a seat a bot plays after the reconnect window is in `botSeats`, and the player gets it back
  the moment they return with their token (any bot plan for it is dropped). Leaving or being kicked mid-game is final.
- **Wire:** compatible with v0.2.0 (same protocol version; `View.turn` is optional and ignored by v0.2.0 joiners, which
  then show no countdown). The host wakes exactly when the next bot move or deadline is due (`Room.nextWakeAt`).

## Security: end-to-end encryption (owner, 2026-11-03)

Owner: "you can add e2e encryption now only" (first "before release", then moved to now). Owner rule: no home-made
crypto, use a maintained library.

**Library:** `dev.whyoleg.cryptography` (cryptography-kotlin) **0.5.0**, Apache-2.0, repo active (last push 31 Oct
2026). Primitives: ECDH on P-256, HKDF-SHA256, AES-256-GCM, SHA-256, all from the platform's own crypto through the
JDK provider (Android: Conscrypt/BoringSSL; JVM: SunEC/SunJCE). No BouncyCastle. iOS (week 3) uses the library's Apple
or OpenSSL provider. Release APK (R8) grows by **17.9 KB** (sample-host: 1,462,066 to 1,479,957 bytes).

**Why not X25519** (ceo first named it; ceo OK'd P-256 on 2026-11-03): X25519 is only in cryptography-kotlin 0.6.0,
which is built with Kotlin 2.3.20 (its iOS klib will not load with our 2.2.20, like dns-sd-kt), and Android's own
crypto has X25519 (XDH) only from API 33 while our minSdk is 24, so it would need BouncyCastle. P-256 ECDH, HMAC-SHA256
and AES-GCM are in Android's crypto from API 11 or earlier.

**How it works** (`SecureChannel.kt`):
- Each room makes a new key pair when it opens; it lives only in memory and is gone when the room ends. Each joiner
  makes a new key pair per connection.
- Key exchange before any game message, commit then reveal (as in Bluetooth numeric comparison and ZRTP): the joiner
  sends a hash of its key, the host answers with the room key and 16 random bytes, then the joiner reveals its key and
  the host checks it against the hash. Keys come from HKDF over the ECDH secret, salted with a hash of both keys and
  the random bytes.
- **Code join (QR or typed):** the code carries 64 bits of the room key's fingerprint
  (`host:port:roomcode:fingerprint`). The joiner checks it before revealing its own key; a mismatch ends with
  `HostKeyMismatch`.
- **List join (found nearby):** both screens show the same 6-digit check code (from both keys and the random bytes)
  next to the request; the host accepts only if they match. Because of commit then reveal, an attacker in the middle
  cannot search for keys that make the codes match; the chance is 1 in a million per attempt.
- After the first join the joiner pins the room key, so every reconnect must reach the same room.
- Every frame after the key exchange is AES-GCM with its own key per direction and the frame's counter as the nonce.
  The receiver accepts only the next counter: a changed, replayed, dropped or reordered frame fails and the connection
  is closed (the seat comes back through the reconnect window).
- Names, moves, hands, the room code and reconnect tokens only ever travel inside encrypted frames. In the clear: the
  two public keys, the random bytes and the key hash (and the mDNS advertisement: game, versions, room name, seats free).

**Tests:** `SecureChannelTest` (11) and `EncryptionLoopbackTest` (5): a sniffer relay that unmasks WebSocket frames
sees no card, name, room code, token or message type in a full match; a changed frame and a replayed frame make the
host close the connection; a code with another room's fingerprint ends `HostKeyMismatch` with nothing reaching the
host; the check codes match on both ends and differ with someone in the middle. Mutation-checked: plaintext frames,
no counter, no fingerprint check, no commitment check, and a host that ignores bad frames each make a test fail.

**Fail closed** (`FailClosedTest`, ceo 2026-11-03): against a fake host that records every frame, a joiner whose code
does not match the host's key sends only its key commitment (never its key, an encrypted frame, a name, the room code
or a token), ends `HostKeyMismatch` at once and never retries; the same when the wrong host answers late; a host that
never answers ends `Unreachable` with only commitments sent. Each connect and each key exchange has its own 5 s limit.

**Residual risk after the fix:**
- The protocol is our composition of standard library parts, reviewed in this repo only, not audited. A maintained
  Noise-protocol library for KMP would replace it if one appears.
- A list join is only safe if the host compares the check code before tapping Accept. A host who does not look can
  let a man in the middle in. Games must show the code next to Accept, large and clear.
- Someone on the same Wi-Fi still sees that a room exists, its name, the game, how many seats are free, who connects
  to the host (IP addresses), and frame sizes and timing.
- A device on the network can still connect and send junk or many requests (handled: 10 s handshake limit, 4 waiting
  requests at most, 64 KB frames). Blocking or dropping traffic (denial of service) cannot be prevented.
- The room code in a QR shown on screen can be photographed by someone nearby; that only lets them join (the host sees
  them in the lobby and can kick).

## Permissions and store

- Android: `INTERNET` (local sockets) and `CHANGE_WIFI_MULTICAST_STATE` (multicast lock for NSD), both install-time,
  no prompt. `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` were dropped (2026-11-03): nothing used them.
- Android 16: dns-sd-kt logs that `NEARBY_WIFI_DEVICES` (local network permission) "will become an error in future
  Android releases". Today discovery works without it (Lava, Android 16); watch the Android 17 behaviour changes.
- **Nothing runs at app start** (ceo, 2026-11-03): dns-sd-kt's androidx.startup initializer is removed in our
  manifest and runs when the game calls `NearbyAndroid.init(context)` (on opening nearby play); discovery throws a
  clear error without it. appcompat (listed by dns-sd-kt, never used; it added emoji2 and lifecycle initializers to app
  start) is excluded. `sample-host:verifyNoNearbyStartup` fails if either initializer is back in the merged manifest.
  Sample release APK: 1,479,973 to 966,011 bytes.
- **Kotlin stdlib stays at the games' 2.2.20:** dns-sd-kt 1.1.2 is built with Kotlin 2.3.20 and pulled kotlin-stdlib
  2.3.20 into every game, which AGP 8.9 Lint (Kotlin 2.1 reader) cannot read. We exclude its stdlib: all 33 stdlib
  methods it calls exist in 2.2.20 (and 2.1.0), checked with reflection. Lint errors went from 7 to 1 (dns-sd-kt's own
  module metadata, so Lint only skips that library's insides). Other options, not taken: pin dns-sd-kt 1.0.4 (Kotlin
  2.1.0, loses the Android resolve fixes including a crash below SDK 30); move games to Kotlin 2.3.20 (big, and Lint
  also needs a newer AGP); ask upstream to build against an older stdlib (a GitHub issue, owner's call).
- **Residual risk of that exclusion** (ceo, 2026-11-03): the reflection check covered the 33 stdlib methods and fields
  dns-sd-kt calls, and every one of the 31 `kotlin/` class names in its bytecode loads from 2.2.20 (the 4 that do
  not are Kotlin metadata or inline debug info only, never JVM references). What is left: a stdlib method whose
  behaviour changed between 2.2 and 2.3 (same name and signature); and a future dns-sd-kt upgrade that calls something
  new, so rerun the check (scratchpad recipe: `javap -c -v` its classes, load each reference against the stdlib jar)
  on every dns-sd-kt upgrade. The minified release build keeps dns-sd-kt's initializer class (ceo checked R8).
- **Residual risk, Android:** if a future Android makes `NEARBY_WIFI_DEVICES` required for local network discovery,
  NSD stops finding rooms until the games declare and request it (the join code still works).
- iOS (week 3): `NSLocalNetworkUsageDescription` + `NSBonjourServices` in Info.plist.
- Play Data safety: no new data leaves the phone (local network only); re-check before release. Facts and suggested
  answers: `docs/DATA-SAFETY.md`. Satti: Families
  check (no data sent to other players beyond the display name typed in the room).

## Order of work

| When | Who | What |
|---|---|---|
| Day 1 (3 Nov) | nearby | Repo scaffold (KMP lib, Android target, JitPack `jitpack.yml`, Kotlin 2.2.20, Ktor 3.1.0); protocol messages; room state machine in commonMain with a fake test game; unit tests |
| Week 1 | nearby | Ktor host server + client, NSD advertise/discover, QR/code fallback, join approval, reconnect window, host-leaves, version refusal; tag `0.1.0` (done 3 Nov) |
| Week 1 | uno | `NearbyGame` adapter over `UnoRules` + "Play nearby" menu (host / join / lobby), behind a debug flag first |
| Week 2 | satti | `NearbyGame` adapter over Satti rules + "Play nearby" screens |
| Week 1 (3 Nov) | nearby | End-to-end encryption (owner moved it to now): P-256 ECDH + HKDF + AES-GCM, fingerprint in the code, check code for list joins; tag `0.2.0` (wire-incompatible with 0.1.0). Release blocker: no store release with nearby play without it |
| Week 2 | nearby | Bot pacing + turn timer (owner, 3 Nov), tag `0.3.0` (wire-compatible with 0.2.0) |
| Week 2 | nearby | Battery/heat check, Wi-Fi low-latency lock for slow moves, second real phone (test 4c) |
| Week 3 | nearby + games | iOS (Bonjour, Local network permission), Data safety, closed-test release |
| Week 2 (7 Nov) | nearby | Seat back after a kill: `SeatStore` + `NearbyClient.resume` (ceo, 7 Nov); no rematch in a room, games open a new room to play again |

**Parked**

- In-app hotspot ("Make a network", `docs/LOCAL-HOTSPOT.md`, draft PR #36): parked by the owner, 7 Nov. A phone
  hotspot is the same as any shared network, and nearby play already works when a player turns on a hotspot in
  Settings. No more work on it. Games show the help text instead (`docs/ADAPTERS.md`, "No Wi-Fi?").

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
   - A phone hotspot turned on in Settings + a second real phone joining it: an OWNER test (a settings change, so
     the owner taps it; sessions do not run it).
   Each run: room shows up in the list, QR join works, full match to the end, Wi-Fi off/on mid-game (reconnect),
   host leaves, move round-trip time (target under 150 ms on Wi-Fi).
5. **Before release**: usual Lava checks (no crash/ANR in the buffer, 20-minute session battery and temperature),
   then the closed tests.

## Device test log

**2026-11-03, test 4b: Lava (Android 16) hosts with `sample-host`, the Mac joins with `mac-client`, same Wi-Fi
(plain ws://, before encryption).**
- Discovery from the Mac (JmDNS): 4 of 4 runs found the room in 2.2 to 2.8 s, then join request, host accept and a
  full match: PASS. One earlier JmDNS run resolved only a link-local IPv6 address (fixed: rooms list only with an IPv4
  address or host name, and resolve again), and one found nothing in 30 s while macOS `dns-sd -B` saw the room, so
  JmDNS on the Mac is a test-tool weakness; phones use NSD.
- Join code: PASS, joined in 0.8 s.
- Move round trip (Mac move to the host's next view): p50 32 to 49 ms, p95 52 to 104 ms per run; worst single moves
  203 ms and 347 ms. Target is 150 ms, so most moves meet it. The slow ones look like Wi-Fi power save on the phone;
  next step is a Wi-Fi low-latency lock on the host during a match, then measure again.
- First contact: the Mac got "Host is down" until the phone itself sent traffic (Wi-Fi dozing). The first connect now
  retries for 10 s.
- Host Wi-Fi off for 8 s mid-match, then on: the joiner reconnected to the same seat and finished: PASS.
- Host leaves mid-match: the joiner ended at once with HostLeft: PASS.
- No crash from our app in the phone log. The test app was uninstalled, Wi-Fi and rotation were left as found.

**2026-11-03, encrypted path on the Lava (Android 16, Android's own crypto), Mac joiner, same Wi-Fi.**
- Join request via discovery: found in 3.2 s; check code on the host log and on the joiner both `760268`; accepted,
  full match PASS.
- Code join with `host:port:code:fingerprint`: joined in 0.7 s, full match PASS, move round trip p50 48 to 52 ms, max
  61 to 69 ms.
- Host Wi-Fi off 8 s mid-match: reconnect to the same seat (pinned room key) and finish: PASS. Host leaves: HostLeft:
  PASS. No crash in the log; app uninstalled, Wi-Fi and rotation as found, lock freed.

**2026-11-03, `host.leave()` timing (Mac, JVM).** One full-suite run spent 267 s in a test's `host.leave()` cleanup.
Every leave step is now traced (room closed sent, writers flushed, server stopped) and `LeaveTimingTest` times 20
leaves with two joiners mid-game per run: about 800 leaves over 41 full-suite runs, worst 20 ms; Ktor's CIO stop is
bounded (grace 500 ms + timeout 1 s). **UNEXPLAINED**: the Mac was heavily loaded that day (one adb tap took 28 min
at 12:15), so CPU starvation during a full-suite run is the working guess (ceo). The loopback cleanup is bounded to
10 s and prints the leave timeline if it ever hangs; the sample host logs how long leave takes on a phone.

**2026-11-03, first-connect flake on the Mac: root cause.** The trace recorder caught a test client getting HTTP 404
from `127.0.0.1:<port>`: a JetBrains process listened on loopback at the same random port our host had bound on
`0.0.0.0`, and macOS routes a loopback dial to the more specific listener. About 1 full-suite run in 20 collided.
Fixed in the tests: `NearbyHost.open(port, bindHost)` and test hosts bind `127.0.0.1`. Not a phone risk on Android
(joiners dial the host's Wi-Fi IP, and Linux refuses a specific-IP bind on a port held by `0.0.0.0`); iOS follows BSD
rules like macOS, so recheck in week 3.

**Orphan seat (evidence, 2026-11-03).** `CutBeforeWelcomeTest` cuts the first socket after the host seated the
joiner and before the Welcome arrives: the joiner retries without a token and is seated again, leaving
`[Host, Asha (offline), Asha]`; the game cannot start until the reconnect window frees the ghost. Same for a join
request. Fixed (v0.3.2): the joiner makes one random join id per `join()`/`requestJoin()` and sends it in every Hello
until its Welcome (optional field, wire-compatible); a token-less Hello with a known join id gets that seat back or
takes over its waiting request (same id, one entry on the host's list). The join id is a credential only until the
Welcome: the host forgets it at the seat's first message, at a token reconnect, and on a kick or leave, and never
hands back a bot seat that was not taken over (ceo's security review). A player turns ready by sending a message, so a
join-id retry can only happen in the lobby. `CutBeforeWelcomeTest` (3) and `JoinIdTest` (13) cover code joins,
requests accepted or still waiting, a token beating a join id, and a kicked or departed player refused both ways.
One earlier failure ("a joiner got no view after start", run 12) had no trace and stays **UNEXPLAINED**.

**Lost Welcome on a reconnect (evidence, 2026-11-10).** `SocketOrderTest` plays every order of the socket events around
one joiner on the real `Room` (4,112 orders with two cut sockets). It found a player losing their seat: the host swaps
the token on every Welcome, so when a reconnect's Welcome died with its socket, the next retry carried the old token
and got "Your seat is gone". Fixed: the host also takes the token the joiner came back with, until that joiner sends
anything on the new socket. The joiner now sends no Ready or Move before its Welcome, so a message really proves the
Welcome arrived. A kicked or departed player still cannot come back with it. What is left: a joiner who stays silent after a reconnect
keeps its previous token valid until it next speaks; tokens only travel inside encrypted frames. No order of these
events left a joiner without a view after start, so run 12 is not an ordering fault in `Room` with one joiner and up to
two cut sockets; the transport and timing side is not covered and run 12 stays **UNEXPLAINED** (the Mac was heavily
loaded that day).

**2026-11-04 00:08 to 00:11, night run: MINIFIED (R8) `sample-host` release on the Lava (Android 16), Mac joiner,
same Wi-Fi.** Code: main 29049bb (v0.3.2) plus the #11 test tools; APK 975,603 bytes.
- Phone discovery after `NearbyAndroid.init` in the R8 build: the phone's NSD found its own room 1.05 s after the
  host started listening (`discovered 'Nearby test LAVA LZG414' at 192.168.0.106:40961, free 3, canJoin true`):
  PASS. The first try in the script printed no line after 13 s, and the phone log had rotated before it could be read
  again, so that miss is UNEXPLAINED; the rerun read the log every 5 s and saw the line at once.
- Advertise and Mac discovery (JmDNS): found in 5.3 s, join request, host accept, check code `756316` on both ends,
  full match PASS. Move round trip p50 35 ms, p95 59 ms, max 59 ms (6 moves).
- Turn clock: every view carried the deadline, read on the Mac as 29,996 to 29,999 ms left (a 30 s turn, 1 to 4 ms
  in transit). The Mac sat on its turn for 40 s and the turn still moved on (host auto-play for the seat): PASS. The
  exact firing time on the phone (30 s) was not timed: UNVERIFIED.
- Bot pacing (1 s): NOT COVERED. The sample room has no bot seat, so `botDelayMs` never ran on the phone; covered only
  by the JVM tests.
- Host Wi-Fi off 8 s mid-match: the joiner showed Reconnecting, came back to its seat and finished (PASS, match over
  in 18.4 s).
- Host leaves mid-match: `leave()` took 120 ms on the phone; the joiner ended with HostLeft: PASS.
- Finding: after the turn timer played for the Mac, the Mac's late move got the game's own "You do not hold that
  card." and not "Too late, another player already did that.". The client sends the seq of the newest view it has
  received, not the seq of the view the move was chosen from, so a move chosen on an older view counts as current.
- No crash from our app in the log; app uninstalled, Home pressed, Wi-Fi on, lock freed.

**2026-11-04 00:25 to 00:28, two devices: Sony TV (BRAVIA VU31, Android 14, 32-bit) hosts, Lava (Android 16) joins,
same Wi-Fi.** Build: #17 head 9cc59be, R8 `sample-host` release (1,074,667 bytes, no native code). TV room with one
bot seat: host seat 0, bot seat 1, Lava seat 2.
- TV discovery after `NearbyAndroid.init` (Android 14 NSD): found its own room within 2.7 s of start: PASS.
- Lava finds and requests (`--ez find true`): found the TV room in 0.44 s, seated at 0.83 s; check code `875495` on
  both; full match to OVER: PASS. Round trip p50 35 ms, p95 79 ms, max 79 ms (6 moves).
- Bot pace, from the TV's turn lines (`turn 1` to the next turn): 14 gaps over 3 matches, all 1000 to 1003 ms: PASS.
- 30 s countdown: the Lava waited 40 s per move; TV `turn 2` at 00:26:15.583, next turn at 00:26:45.583, exactly
  30,000 ms; the Lava saw 29,997 ms left: PASS. Its late move got "You do not hold that card." (this build does not
  have #16; the sample calls `move(move)`).
- Wi-Fi drop on the Lava only (8 s; never the TV, it carries adb): Reconnecting, back in 10.2 s, finished: PASS. Round
  trip p50 72 ms, p95 257 ms around the drop.
- TV host leaves mid-match: `leave()` 66 ms on the TV; the Lava ended HostLeft: PASS.
- No crash lines on either device. Home on both; uninstalled from the Lava (the TV copy was removed at about 07:07,
  owner rule: uninstall from the TV after each test); Lava Wi-Fi on; lock freed.

**2026-11-13 09:21 to 09:24, host Wi-Fi drop during a reconnect: Lava (Android 16) hosts, Mac joins, same Wi-Fi.**
Build: main 2ccf643, `sample-host` release (2 seats), `mac-client` with a 6 s think. Wi-Fi off only over the Lava's
USB adb serial.
- Each run: the match starts, the Lava's Wi-Fi goes off for 8 s, and goes off again for 8 s as soon as its address is
  back, before the Mac has reconnected. The Mac showed Reconnecting once, then Connected, kept its seat (PLAYER) and
  the match ended: 3 of 3 PASS.
- The Lava got the same address back (192.168.0.108) after all 6 drops.
- No crash or ANR lines; volume 0; sample uninstalled (pm list 0); Play apps unchanged; lock freed.
- Mi A3 half: UNVERIFIED, owed. It needs the Mi A3 on USB, since Wi-Fi off is never sent over a Wi-Fi adb link.

## Rules for this repo

- No GitHub Actions; tests run locally (`./gradlew allTests` or the ci-local lane).
- No AI or Co-Authored trailers in commits; task branches and draft PRs; owner or ceo merges.
- Public repo (JitPack): no secrets, keys, tester data or analytics ids, ever.
- Easy language in docs and PRs, no em dash.
