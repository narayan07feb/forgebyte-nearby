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

- Android: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (multicast lock for NSD),
  `INTERNET` (local sockets). NSD needs no "Nearby devices" permission; check this on Android 16 before release.
- iOS (week 3): `NSLocalNetworkUsageDescription` + `NSBonjourServices` in Info.plist.
- Play Data safety: no new data leaves the phone (local network only); re-check before release. Satti: Families
  check (no data sent to other players beyond the display name typed in the room).

## Order of work

| When | Who | What |
|---|---|---|
| Day 1 (3 Nov) | nearby | Repo scaffold (KMP lib, Android target, JitPack `jitpack.yml`, Kotlin 2.2.20, Ktor 3.1.0); protocol messages; room state machine in commonMain with a fake test game; unit tests |
| Week 1 | nearby | Ktor host server + client, NSD advertise/discover, QR/code fallback, join approval, reconnect window, host-leaves, version refusal; tag `0.1.0` (done 3 Nov) |
| Week 1 | uno | `NearbyGame` adapter over `UnoRules` + "Play nearby" menu (host / join / lobby), behind a debug flag first |
| Week 2 | satti | `NearbyGame` adapter over Satti rules + "Play nearby" screens |
| Week 1 (3 Nov) | nearby | End-to-end encryption (owner moved it to now): P-256 ECDH + HKDF + AES-GCM, fingerprint in the code, check code for list joins; tag `0.2.0` (wire-incompatible with 0.1.0). Release blocker: no store release with nearby play without it |
| Week 2 | nearby | Battery/heat check, Wi-Fi low-latency lock for slow moves, second real phone (test 4c) |
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

## Rules for this repo

- No GitHub Actions; tests run locally (`./gradlew allTests` or the ci-local lane).
- No AI or Co-Authored trailers in commits; task branches and draft PRs; owner or ceo merges.
- Public repo (JitPack): no secrets, keys, tester data or analytics ids, ever.
- Easy language in docs and PRs, no em dash.
