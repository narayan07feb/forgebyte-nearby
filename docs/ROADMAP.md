# forgebyte-nearby roadmap

What is done, what is open and what is parked. The owner's decisions and the full test log are in `docs/PLAN.md`.
ceo picks the order of the open items.

## Done (tags on JitPack)

| Tag | Date | What it added |
|---|---|---|
| v0.1.0 | 2026-11-03 | Room, protocol, Ktor host and client, discovery, host-approved joins (Android, JVM) |
| v0.2.0 | 2026-11-03 | End-to-end encryption (P-256 ECDH, HKDF-SHA256, AES-256-GCM), key fingerprint in the join code, check code |
| v0.3.0 | 2026-11-03 | Bot pacing and turn timer, `NearbyGame.currentSeat`, nothing runs at app start (`NearbyAndroid.init`) |
| v0.3.1 | 2026-11-03 | Off-turn bot moves, "Too late" answers, seats given back after a bot takeover |
| v0.3.2 | 2026-11-03 | Test hosts bind loopback, leave traces, join id for the orphan seat |
| v0.3.3 | 2026-11-04 | `seqView` and `move(move, seenSeq)` on host and client |
| v0.3.4 | 2026-11-06 | Secure default deal seed, bot pacing under 1 s, AGP 8.11.2, room name and code-join warnings |
| v0.3.5 | 2026-11-07 | Short-code joins, discovery off the main thread |
| v0.3.6 | 2026-11-07 | Whole-character room names, R8 rule in the library |
| v0.3.7 | 2026-11-08 | Seat back after a kill, no endless Reconnecting, players stay players after the game |
| v0.3.8 | 2026-11-10 | Hosts can approve code joins, a fake second player for debug builds (`nearby-testing`) |
| v0.3.9 | 2026-11-14 | A joiner keeps its seat when a reconnect's Welcome is lost |

Docs: `docs/ADAPTERS.md` (how a game uses the library), `docs/FAKE-PEER.md`, `docs/DATA-SAFETY.md`.

## Open

1. **Device round: two phones and the Mac in one room.** One phone hosts a 3-seat room, the other phone and the Mac
   join, the host swaps each run, the phone joins by discovery and then by short code. No code change. Waits for a
   slot with the Lava on the network.
2. **Wi-Fi low-latency lock on the host during a match** (Order of work, week 2). Slow moves (203 ms and 347 ms on
   2026-11-03; p50 138 ms in the 2026-11-05 battery run) look like Wi-Fi power save on the phone. Code without a
   phone, then an A/B phone run (with and without the lock). Waits for ceo's yes.
3. **Discovery when two Android 9 to 13 hosts share the name `Android.local`** (draft PR #38: the host lists its
   IPv4 in the TXT record). Waits for a device slot with two such hosts.
4. **Causes still UNEXPLAINED or UNVERIFIED** (see the Device test log in `docs/PLAN.md`):
   - run 12: a joiner got no view after the start (no trace);
   - the 267 s leave (now bounded to 10 s);
   - the Mi A3 drops where every Mac connect timed out for about 52 s after the phone had its address back.

## Parked

- **iOS** (Bonjour, Local network permission): draft PR #24, Kotlin 2.3.20. Parked by ceo until after Android
  production.
- **In-app hotspot** ("Make a network"): draft PR #36, parked by the owner on 2026-11-07. Games show the "No Wi-Fi?"
  help text from `docs/ADAPTERS.md` instead.
- **Sony TV device tests**: the owner said on 2026-11-14 not to use the TV for now.
