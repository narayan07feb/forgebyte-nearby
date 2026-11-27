# nearby

A Kotlin Multiplatform library for turn-based multiplayer rooms on the same Wi-Fi or a phone hotspot, with no internet.
One device hosts the room and keeps the only copy of the shared state; the others find it on the local network and join.

- **Discovery**: the host advertises the room over mDNS (DNS-SD), and joiners on the same network list it.
- **Joining**: by discovery with the host's Accept, by a full join code (QR or typed), or by a six-character short code.
- **Host is the referee**: players send moves; the host checks each move and sends every player only their own view.
- **Encryption**: everything after the join (names, moves, views) is encrypted end to end (ECDH P-256, HKDF-SHA256,
  AES-256-GCM).
- **Reconnect**: a player who drops gets the same seat back within the reconnect window; if the window runs out
  mid-session, a host-side bot takes over the seat.
- **Targets**: Android and JVM (the JVM target runs the loopback tests and a desktop test client).

Modules: `nearby` (the library), `nearby-testing` (test helpers), `sample-host` (Android sample app), `mac-client`
(desktop test client), `demo` (a small sample implementation).

Status: released (see the tags). Plan and test log: [docs/PLAN.md](docs/PLAN.md). Done, open and parked work:
[docs/ROADMAP.md](docs/ROADMAP.md). Version numbers and release steps: [docs/RELEASING.md](docs/RELEASING.md).

## Privacy

`RoomConfig.roomName` goes unencrypted to the local network in the discovery advert, so never put a player name in it.
Everything after joining is encrypted end to end between the devices.

A join with the full code (`JoinCode`, QR or typed) gets a seat with no host approval and no check code, until the
session starts: share the full code only in person or by QR. With `RoomConfig(approveCodeJoins = true)` a full-code
join waits for the host's Accept and shows the check code too.

A short code (`ShortCode`, six characters such as `K7Q-2MX`) only finds the host on the same Wi-Fi and holds no
secret, so a short-code join always waits for the host's Accept and shows the check code on both devices.

## Build and test

JDK 21, then:

```
./gradlew jvmTest
```
