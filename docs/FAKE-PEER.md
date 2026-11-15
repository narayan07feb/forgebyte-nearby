# Room checks on one phone: the fake peer

For the ForgeByte card games. `FakePeer` is a second player that lives inside the test build. It joins the room this
phone hosts over 127.0.0.1, says ready and plays by script. The game's real lobby, table and result screens run, so
one phone can check them. How the fixtures work and the general setup are in `ADAPTERS.md`, "A test player on one phone".

| Game | State | Next step |
|---|---|---|
| Game A | Done. Lava run 10 Nov on the R8 `lava` build: flag on, Test player joined and played; flag off, nobody came. | Use it in room checks (below). |
| Game B | Not started: its adapter PR is parked and it pins v0.3.5. | Do the steps below when that PR unparks. |

## What it can and cannot check

It checks the host's side of a room: the lobby fills, Ready shows, Start works, turns pass, the opponent's card count
changes, the round ends.

It does not replace a two-phone run. These still need the Lava and the Mi A3: discovery (the room list), QR and short
codes, a real Wi-Fi network, reconnect and Wi-Fi drops, and anything the second player sees on their own screen.

## Game A: how to use it

Build `assembleLava` or `assembleDebug`, then start the app with the flag. Force-stop first, because the flag is read
when the activity starts:

```
adb shell am force-stop <app id>
adb shell am start -n <app id>/.MainActivity --ez nearby_peer true
```

Host a room as usual. Test player comes into the lobby with no tap on Accept.

## Game B: steps when its adapter PR unparks

Game B's draft `AndroidNearbyPort` has the same shape as game A's, so this is game A's change with game B's names.

1. Bump `nearby` to v0.3.8 or later. `nearby-testing` does not exist before v0.3.8.
2. Add `nearby-testing` to the `debug`, `lava` and `benchmark` source sets, next to `src/androidScenario/kotlin`. These
   build types already carry game B's test scenarios. Never add it to `commonMain` or `androidMain`.
3. Add `verifyNoNearbyTestingInRelease` over the release runtime classpath. Copy it from game A's `androidApp/build.gradle.kts`.
4. Give `AndroidNearbyPort` an optional `onHostOpened` and call it right after `launch { host.advertise(port) }`. Make
   `GAME_VERSION` internal.
5. Build the hook in `src/androidScenario/kotlin`, and a twin in `src/androidRelease/kotlin` that returns null and
   never reads the flag. `MainActivity` passes the hook; `HostRoom` does not read the intent.

```kotlin
internal fun testNearbyPeer(flag: (String) -> Boolean): OnHostOpened? = if (!flag("nearby_peer")) null else { host, port, scope ->
    val game = MyNearbyGame()
    FakePeer(game.gameId, GAME_VERSION, game.moveSerializer, game.viewSerializer, scope) { v ->
        // Null: not this seat's turn, or the game is over. MyMove(null) is a pass, legal only with nothing to play.
        if (v.currentSeat == v.seat) MyMove(v.playable.firstOrNull()) else null
    }.join(host, port)
}
```

6. Test the store build never reads the flag, and mutation-check the guard. Game A's `TestHooksTest` and
   `TestNearbyPeerTest` are the pattern.

## Other room checks

| Check | What to do | Wired today |
|---|---|---|
| Lobby, Ready, Start, turns, card counts | The default script | Game A |
| Remove button | Tap Remove in the lobby; the peer's seat is freed | Game A |
| Accept and Decline buttons | `join(host, port, autoAccept = false)`, as a second flag | No |
| Turn timer runs out | A script that always returns null; the host plays that seat's `botMove` after `turnTimeoutMs` | No |
| A player leaves mid-game | Call `FakePeer.leave()`, from a second flag and a delay | No |
| Joiner screens | `FakeHost`, plus a hook that gives its `RoomAd` to the port's join path | No, and never run on a phone |

Add a row's flag only when a check needs it. A new flag name goes in game A's `test-tools-markers.txt`.

## Things that cost us a run

- **Scroll first.** "Play nearby" is below the fold on the menu, and a UI dump does not list it until it is on screen.
- **Install.** A build not signed by the key of the installed app will not install over it. Remove that app first and
  put a Play copy back after the run.
- **Start the game.** The button enables a moment after the peer says ready, so wait for it.
- **The script must return legal moves.** A refused move gets no new view, so the peer waits and the game seems stuck.
- **Join by code is not covered.** The peer joins through a `RoomAd`, so the request can show in the lobby for a moment
  before it accepts itself. Do not read that as a bug.
