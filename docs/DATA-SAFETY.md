# Nearby play and the Play Data safety form

Facts about what the library sends, keeps and logs, for each game's Data safety form and privacy page. Checked
against main `2ccf643` (v0.3.8 line). This is not legal advice: each game fills its own form, and the owner makes the
final call. Re-check this page and Google Play's Data safety help page before each release.

## What leaves the phone

Nearby play has no ForgeByte server, no internet relay and no account. Data goes only to other phones on the same
local network, and the developer never receives it.

**In the open, to every device on the local network (mDNS advert, `Discovery.kt` `roomTxt`):**

| Field | Value |
|---|---|
| `g` | the game id |
| `pv`, `gv` | protocol and game versions |
| `n` | the room name, cut to 40 characters (the service name is cut to 63 UTF-8 bytes) |
| `f` | the number of free seats |
| address | the host phone's local IP address and port (mDNS A record and SRV) |

No room code, key, check code or player name is in the advert. The room name is set by the game. Games use a
neutral label, never the player's name (README and `RoomConfig.roomName` KDoc). uno does this today
(`nearbyRoomLabel`: "Crazy Eights" plus a random word).

**In the join code, the QR and the short code:** the host's local IP address, port, room code and key fingerprint
(`JoinCode`), or the last byte of the IP address and the port (`ShortCode`). A player shows these on purpose to the
people at the table.

**End-to-end encrypted, between the joiner's phone and the host's phone (`SecureChannel.kt`):** the display name
the player typed (cut to 20 characters; blank becomes "Player N"), a random join id, the reconnect token, ready
flags, moves and each player's own view of the table. The host makes a key pair for each room, the joiner makes a
fresh one for each connection, and the message keys come from both (ECDH P-256, HKDF-SHA256, AES-256-GCM). The host is a player at the table, so the host phone is a recipient, not a
middleman.

## What stays on the phone

- **The library writes no files and no preferences.** Room keys, tokens and names live in memory and go when the
  room ends or the app closes.
- **Seat after a kill (`SeatStore`, v0.3.7):** the default `InMemorySeatStore` keeps the saved seat in memory only.
  A game that wants the seat back after a kill passes its own store. That store holds `SavedSeat`: the host's local
  IP address and port, the key fingerprint, the game id, the player's display name and the reconnect token. The
  KDoc tells games to keep it in private app storage, out of backups and device transfer. It never leaves the phone,
  and the client clears it when the seat ends for good. Data stored only on the device is not "collected" in Play's
  definition.

## Logs

- The library logs nothing about players. `NearbyTrace.sink` is off by default. When a debug build turns it on, it
  gets connect, key exchange, drop, retry and leave-timing lines, with no keys, room codes, check codes, player
  names, moves or views (`SecureChannel.kt`).
- The `sample-host` test app logs lobby lines with player names. It is a test tool, not a game.

## Permissions

| Permission | Prompt | Why |
|---|---|---|
| `INTERNET` | No, install-time | Local sockets between the phones. Games already have it. |
| `CHANGE_WIFI_MULTICAST_STATE` | No, install-time | Lets Android's NSD hear room adverts. |

`NEARBY_WIFI_DEVICES` is not declared. Discovery works without it today (Lava, Android 16). If a future Android
requires it, games must declare it and ask the player; the join code keeps working meanwhile.

## Suggested Data safety answers for the nearby part

For the owner to confirm. Earlier draft with the help page quotes: `studio/ceo/drafts/nearby-data-safety-2026-11-05.md`.

1. **Display name, moves, game views:** end-to-end encrypted, keys only on the two phones. Play's end-to-end
   encryption exception applies, so nearby play adds no new collected data type for these.
2. **Sharing:** none. There is no third party, and a player who taps Join expects the other players to see their
   name and moves.
3. **Room name in the advert:** a neutral label, so no personal data in the open.
4. **Local IP address and port:** network plumbing to reach the host on the same Wi-Fi, never sent to ForgeByte or
   stored by the library. Draft view: no "Device or other IDs" answer. UNVERIFIED: the help page does not name this
   case.
5. **Saved seat (only if a game passes a persistent `SeatStore`):** on-device only, so not collected. Leave it out of
   backups.
6. **Data deletion:** nearby play keeps nothing off the phone, so there is nothing to delete.

## Pending changes that touch this page

- #38 (draft) adds a TXT key `a` with the host's local IPv4 addresses. The advert already carries the address, so
  the answers above do not change. Update the table when it merges.
